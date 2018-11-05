/*
 * Copyright 2018 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.agentclientrelationships.support

import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import javax.inject.{Inject, Named, Singleton}
import org.joda.time.{DateTime, Interval, PeriodType}
import play.api.Logger
import play.api.libs.concurrent.ExecutionContextProvider
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.repository.{DeleteRecord, MongoDeleteRecordRepository, MongoRecoveryScheduleRepository, RecoveryRecord}
import uk.gov.hmrc.agentclientrelationships.services.DeleteRelationshipsService
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

@Singleton
class RecoveryScheduler @Inject()(
  mongoRecoveryScheduleRepository: MongoRecoveryScheduleRepository,
  mongoDeleteRecordRepository: MongoDeleteRecordRepository,
  deleteRelationshipsService: DeleteRelationshipsService,
  actorSystem: ActorSystem,
  @Named("recovery-interval") recoveryInterval: Int,
  @Named("features.recovery-enable") recoveryEnable: Boolean,
  executionContextProvider: ExecutionContextProvider) {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  implicit val ec: ExecutionContext = executionContextProvider.get()

  implicit val ad: AuditData = new AuditData

  if (recoveryEnable) {
    val taskActor: ActorRef = actorSystem.actorOf(Props {
      new TaskActor(mongoRecoveryScheduleRepository, executionContextProvider, recoveryInterval, recover)
    })

    actorSystem.scheduler.scheduleOnce(5.seconds, taskActor, "<start>")
  } else
    Logger(getClass).warn("Recovery job scheduler not enabled.")

  def recover: Future[Unit] =
    mongoDeleteRecordRepository.findAll().map(_.headOption).flatMap {
      case Some(record) =>
        record.clientIdentifierType match {
          case "MTDITID" =>
            deleteRelationshipsService
              .checkDeleteRecordAndEventuallyResume(MtdItId(record.clientIdentifier), Arn(record.arn))
              .map(_ => ())
          case "VRN" =>
            deleteRelationshipsService
              .checkDeleteRecordAndEventuallyResume(Vrn(record.clientIdentifier), Arn(record.arn))
              .map(_ => ())
        }
      case None =>
        Logger(getClass).info("No Delete Record Found")
        Future.successful(())
    }
}

class TaskActor(
  mongoRecoveryScheduleRepository: MongoRecoveryScheduleRepository,
  executionContextProvider: ExecutionContextProvider,
  recoveryInterval: Int,
  recover: => Future[Unit])
    extends Actor {

  implicit val ec: ExecutionContext = executionContextProvider.get()

  def receive = {
    case uid: String =>
      mongoRecoveryScheduleRepository.read.flatMap {
        case RecoveryRecord(recordUid, runAt) =>
          val now = DateTime.now()
          if (uid == recordUid) {
            val newUid = UUID.randomUUID().toString
            val nextRunAt = runAt.plusSeconds(recoveryInterval)
            val delay = (if (nextRunAt.isBefore(now)) recoveryInterval
                         else
                           new Interval(now, nextRunAt).toPeriod(PeriodType.seconds()).getValue(0) + Random
                             .nextInt(60)).seconds
            mongoRecoveryScheduleRepository
              .write(newUid, nextRunAt)
              .map(_ => {
                context.system.scheduler.scheduleOnce(delay, self, newUid)
                Logger(getClass).info("About to start recovery job.")
                recover
              })
          } else {
            val delay = (if (runAt.isBefore(now)) recoveryInterval
                         else
                           new Interval(DateTime.now(), runAt).toPeriod(PeriodType.seconds()).getValue(0) + Random
                             .nextInt(60)).seconds
            context.system.scheduler.scheduleOnce(delay, self, recordUid)
            Future.successful(())
          }
      }
  }
}
