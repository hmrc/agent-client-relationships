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

import play.api.Logging
import play.api.i18n.{Lang, Langs, MessagesApi}
import uk.gov.hmrc.agentclientrelationships.connectors.EmailConnector
import uk.gov.hmrc.agentclientrelationships.model.{EmailInformation, Invitation}
import uk.gov.hmrc.agentclientrelationships.util.DateTimeHelper
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class EmailService @Inject() (
  emailConnector: EmailConnector,
  messagesApi: MessagesApi
)(implicit langs: Langs)
    extends Logging {

  implicit val lang: Lang = langs.availables.head

  def sendWarningEmail(invitations: Seq[Invitation])(implicit ec: ExecutionContext): Future[Boolean] = {
    implicit val hc: HeaderCarrier =
      HeaderCarrier(extraHeaders = Seq("Warning-aboutToExpire-email-size" -> s"${invitations.size}"))
    logger.info(
      s"[EmailService] sending warning of expiry email for ${invitations.size} invitations for ARN ${invitations.head.arn}"
    )
    val numberOfInvitations = invitations.size
    val templateId =
      if (invitations.size > 1) "agent_invitations_about_to_expire" else "agent_invitation_about_to_expire_single"
    emailConnector.sendEmail(
      EmailInformation(
        to = Seq(invitations.head.agencyEmail),
        templateId = templateId,
        parameters = Map(
          "agencyName"          -> invitations.head.agencyName,
          "numberOfInvitations" -> numberOfInvitations.toString,
          "createdDate"         -> DateTimeHelper.displayDate(invitations.head.created),
          "expiryDate"          -> DateTimeHelper.displayDate(invitations.head.expiryDate)
        )
      )
    )
  }

  def sendExpiredEmail(invitation: Invitation)(implicit ec: ExecutionContext): Future[Boolean] = {
    implicit val hc: HeaderCarrier =
      HeaderCarrier(extraHeaders = Seq("Expired-Invitation" -> s"${invitation.invitationId}"))
    logger.info(s"[EmailService] sending email for invitation ${invitation.invitationId} that has expired")
    emailConnector.sendEmail(emailInformation("client_expired_authorisation_request", invitation))
  }

  def sendAcceptedEmail(invitation: Invitation)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Boolean] =
    emailConnector.sendEmail(emailInformation("client_accepted_authorisation_request", invitation))

  def sendRejectedEmail(invitation: Invitation)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Boolean] =
    emailConnector.sendEmail(emailInformation("client_rejected_authorisation_request", invitation))

  private def emailInformation(templateId: String, invitation: Invitation) = {
    val altItsaParam =
      if (invitation.isAltItsa)
        Map("additionalInfo" -> s"You must now sign ${invitation.clientName} up to Making Tax Digital for Income Tax.")
      else Map()

    EmailInformation(
      Seq(invitation.agencyEmail),
      templateId,
      Map(
        "agencyName" -> invitation.agencyName,
        "clientName" -> invitation.clientName,
        "expiryDate" -> DateTimeHelper.displayDate(invitation.expiryDate),
        "service"    -> messagesApi(s"service.${invitation.service}")
      ) ++ altItsaParam
    )
  }
}
