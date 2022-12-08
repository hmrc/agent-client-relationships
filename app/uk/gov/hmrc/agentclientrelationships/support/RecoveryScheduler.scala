/*
 * Copyright 2022 HM Revenue & Customs
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

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.repository.{MongoRecoveryScheduleRepository, RecoveryRecord}
import uk.gov.hmrc.agentclientrelationships.services.DeleteRelationshipsService

import java.time.{Instant, ZoneOffset}
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

@Singleton
class RecoveryScheduler @Inject()(
  mongoRecoveryScheduleRepository: MongoRecoveryScheduleRepository,
  deleteRelationshipsService: DeleteRelationshipsService,
  actorSystem: ActorSystem)(implicit appConfig: AppConfig, ec: ExecutionContext)
    extends Logging {

  val recoveryInterval = appConfig.recoveryInterval
  val recoveryEnable = appConfig.recoveryEnabled

  if (recoveryEnable) {
    val taskActor: ActorRef = actorSystem.actorOf(Props {
      new TaskActor(mongoRecoveryScheduleRepository, recoveryInterval, recover)
    })
    actorSystem.scheduler.scheduleOnce(5.seconds, taskActor, "<start>")
  } else
    logger.warn("Recovery job scheduler not enabled.")

  def recover: Future[Unit] =
    deleteRelationshipsService.tryToResume(ec, new AuditData).map(_ => ())
}

class TaskActor(
  mongoRecoveryScheduleRepository: MongoRecoveryScheduleRepository,
  recoveryInterval: Int,
  recover: => Future[Unit])(implicit ec: ExecutionContext)
    extends Actor
    with Logging {

  def receive: PartialFunction[Any, Unit] = {
    case uid: String =>
      mongoRecoveryScheduleRepository.read.foreach {
        case RecoveryRecord(recordUid, runAt) =>
          val now = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime
          if (uid == recordUid) {
            val newUid = UUID.randomUUID().toString
            val nextRunAt = (if (runAt.isBefore(now)) now else runAt)
              .plusSeconds(recoveryInterval + Random.nextInt(Math.min(60, recoveryInterval)))
            val delay = nextRunAt.toEpochSecond(ZoneOffset.UTC) - now.toEpochSecond(ZoneOffset.UTC)
            mongoRecoveryScheduleRepository
              .write(newUid, nextRunAt)
              .map(_ => {
                context.system.scheduler.scheduleOnce(delay.seconds, self, newUid)
                logger.info("About to start recovery job.")
                recover
              })
          } else {
            val dateTime = if (runAt.isBefore(now)) now else runAt
            val delay = (dateTime.toEpochSecond(ZoneOffset.UTC) - now.toEpochSecond(ZoneOffset.UTC)) + Random.nextInt(
              Math.min(60, recoveryInterval))
            context.system.scheduler.scheduleOnce(delay.seconds, self, recordUid)
            Future.successful(())
          }
      }
  }
}
