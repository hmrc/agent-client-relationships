/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.services

import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.agentclientrelationships.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCMTDIT
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCPIR
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import play.api.mvc.RequestHeader

import java.net.URLEncoder
import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class FriendlyNameService @Inject() (enrolmentStoreProxyConnector: EnrolmentStoreProxyConnector)(implicit
  executionContext: ExecutionContext
)
extends RequestAwareLogging {

  def updateFriendlyName(
    invitation: Invitation,
    enrolment: EnrolmentKey
  )(implicit request: RequestHeader): Future[Unit] =
    invitation.service match {
      case `HMRCPIR` => Future.unit
      case `HMRCMTDITSUPP` | `HMRCMTDIT` if invitation.isAltItsa => Future.unit
      case _ => doUpdateFriendlyName(invitation, enrolment)
    }

  private def doUpdateFriendlyName(
    invitation: Invitation,
    enrolment: EnrolmentKey
  )(implicit request: RequestHeader): Future[Unit] = {
    val clientName: String = URLEncoder.encode(invitation.clientName, "UTF-8")

    (
      for {
        groupId <- enrolmentStoreProxyConnector
          .getPrincipalGroupIdFor(Arn(invitation.arn))
          .recover { case _ => throw GroupIdError }
        _ <- enrolmentStoreProxyConnector.updateEnrolmentFriendlyName(
          groupId,
          enrolment.toString,
          clientName
        )
      } yield logger.info(s"updateFriendlyName succeeded for client ${invitation.clientId}, agent ${invitation.arn}")
    ).recover {
      case GroupIdError =>
        logger.warn(
          s"updateFriendlyName not attempted due to error retrieving agent's group id for client ${invitation.clientId}, agent ${invitation.arn}"
        )
      case exception =>
        logger.warn(
          s"updateFriendlyName failed due to ES19 error for client ${invitation.clientId}, agent ${invitation.arn}: $exception"
        )
    }
  }

}

sealed trait FriendlyNameUpdateError
extends Exception
case object GroupIdError
extends FriendlyNameUpdateError
