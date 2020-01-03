/*
 * Copyright 2020 HM Revenue & Customs
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
import uk.gov.hmrc.agentclientrelationships.repository.{MongoDeleteRecordRepository, MongoRecoveryScheduleRepository, RecoveryRecord}
import uk.gov.hmrc.agentclientrelationships.services.DeleteRelationshipsService
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

  implicit val ec: ExecutionContext = executionContextProvider.get()

  if (recoveryEnable) {
    val taskActor: ActorRef = actorSystem.actorOf(Props {
      new TaskActor(mongoRecoveryScheduleRepository, executionContextProvider, recoveryInterval, recover)
    })
    actorSystem.scheduler.scheduleOnce(5.seconds, taskActor, "<start>")
  } else
    Logger(getClass).warn("Recovery job scheduler not enabled.")

  def recover: Future[Unit] =
    deleteRelationshipsService.tryToResume(ec, new AuditData).map(_ => ())
}

class TaskActor(
  mongoRecoveryScheduleRepository: MongoRecoveryScheduleRepository,
  executionContextProvider: ExecutionContextProvider,
  recoveryInterval: Int,
  recover: => Future[Unit])
    extends Actor {

  implicit val ec: ExecutionContext = executionContextProvider.get()

  def receive: PartialFunction[Any, Unit] = {
    case uid: String =>
      mongoRecoveryScheduleRepository.read.foreach {
        case RecoveryRecord(recordUid, runAt) =>
          val now = DateTime.now()
          if (uid == recordUid) {
            val newUid = UUID.randomUUID().toString
            val nextRunAt = (if (runAt.isBefore(now)) now else runAt)
              .plusSeconds(recoveryInterval + Random.nextInt(Math.min(60, recoveryInterval)))
            val delay = new Interval(now, nextRunAt).toPeriod(PeriodType.seconds()).getValue(0).seconds
            mongoRecoveryScheduleRepository
              .write(newUid, nextRunAt)
              .map(_ => {
                context.system.scheduler.scheduleOnce(delay, self, newUid)
                Logger(getClass).info("About to start recovery job.")
                recover
              })
          } else {
            val dateTime = if (runAt.isBefore(now)) now else runAt
            val delay = (new Interval(now, dateTime).toPeriod(PeriodType.seconds()).getValue(0) + Random.nextInt(
              Math.min(60, recoveryInterval))).seconds
            context.system.scheduler.scheduleOnce(delay, self, recordUid)
            Future.successful(())
          }
      }
  }
}
