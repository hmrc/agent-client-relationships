/*
 * Copyright 2025 HM Revenue & Customs
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

import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, Props}
import org.apache.pekko.extension.quartz.QuartzSchedulerExtension
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.Expired
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.services.EmailService

import java.util.TimeZone
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class EmailScheduler @Inject()(actorSystem: ActorSystem,
                               appConfig: AppConfig,
                               emailService: EmailService,
                               invitationsRepository: InvitationsRepository)
                              (implicit ec: ExecutionContext) extends Logging {

  if (appConfig.emailSchedulerEnabled) {

    val scheduler: QuartzSchedulerExtension = QuartzSchedulerExtension(actorSystem)

    logger.info("[EmailScheduler] Scheduler is enabled")

    val warningEmailActorRef: ActorRef = actorSystem.actorOf(Props {
      new WarningEmailActor(invitationsRepository, emailService)
    })

    scheduler.createJobSchedule(
      name = "WarningEmailSchedule",
      description = Some("This job will send emails to warn of an invitation expiring soon"),
      cronExpression = appConfig.emailSchedulerWarningCronExp,
      timezone = TimeZone.getTimeZone("Europe/London"),
      receiver = warningEmailActorRef,
      msg = "<start>"
    )

    val expiredEmailActorRef: ActorRef = actorSystem.actorOf(Props {
      new ExpiredEmailActor(invitationsRepository, emailService)
    })

    scheduler.createJobSchedule(
      name = "ExpiredEmailSchedule",
      description = Some("This job will set the status of invitations to Expired and send emails to notify of this"),
      cronExpression = appConfig.emailSchedulerExpiredCronExp,
      timezone = TimeZone.getTimeZone("Europe/London"),
      receiver = expiredEmailActorRef,
      msg = "<start>"
    )
  } else {
    logger.info("[EmailScheduler] Scheduler is disabled")
  }
}

class WarningEmailActor(invitationsRepository: InvitationsRepository,
                        emailService: EmailService)(implicit ec: ExecutionContext) extends Actor with Logging {

  def receive: Receive = { case _ =>
    logger.info("[EmailScheduler] Warning email scheduled job is running")
    invitationsRepository.findAllForWarningEmail.map { invitations =>
      invitations.groupBy(_.arn).foreach { case (_, list) =>
        emailService.sendWarningEmail(list).map {
          case true =>
            invitations.foreach { invitation =>
              invitationsRepository.updateWarningEmailSent(invitation.invitationId)
            }
          case false =>
            logger.warn(s"[EmailScheduler] Warning email failed to send for ARN: ${list.headOption.map(_.arn)}")
        }
      }
    }
    ()
  }
}

class ExpiredEmailActor(invitationsRepository: InvitationsRepository,
                        emailService: EmailService)(implicit ec: ExecutionContext) extends Actor with Logging {

  def receive: Receive = { case _ =>
    logger.info("[EmailScheduler] Expired email scheduled job is running")
    invitationsRepository.findAllForExpiredEmail.map { invitations =>
      invitations.foreach { invitation =>
        invitationsRepository.updateStatus(invitation.invitationId, Expired)
        emailService.sendExpiredEmail(invitation).map {
          case true =>
            invitationsRepository.updateExpiredEmailSent(invitation.invitationId)
            ()
          case false =>
            logger.warn(s"[EmailScheduler] Expiry email failed to send for invitation: ${invitation.invitationId}")
        }
      }
    }
    ()
  }
}
