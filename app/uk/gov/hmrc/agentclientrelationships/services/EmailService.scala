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
import uk.gov.hmrc.agentclientrelationships.connectors.{AgentAssuranceConnector, EmailConnector}
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.agentclientrelationships.model.{EmailInformation, Invitation}
import uk.gov.hmrc.agentclientrelationships.util.DateTimeHelper
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class EmailService @Inject() (
  agentAssuranceConnector: AgentAssuranceConnector,
  emailConnector: EmailConnector,
  messagesApi: MessagesApi
)(implicit langs: Langs)
    extends Logging {

  implicit val lang: Lang = langs.availables.head

  def sendWarningEmail(invitations: Seq[Invitation])(implicit ec: ExecutionContext): Future[Boolean] = {
    implicit val hc: HeaderCarrier =
      HeaderCarrier(extraHeaders = Seq("Warning-aboutToExpire-email-size" -> s"${invitations.size}"))
    invitations.headOption match {
      case None =>
        logger.info("[EmailService] empty list - no warning to expire emails to send")
        Future.successful(false)
      case Some(headInv) =>
        logger.info(
          s"[EmailService] sending warning of expiry email for ${invitations.size} invitations for ARN ${headInv.arn}"
        )
        val numberOfInvitations = invitations.size
        val templateId =
          if (invitations.size > 1) "agent_invitations_about_to_expire" else "agent_invitation_about_to_expire_single"
        emailConnector.sendEmail(
          EmailInformation(
            to = Seq(headInv.agencyEmail),
            templateId = templateId,
            parameters = Map(
              "agencyName"          -> headInv.agencyName,
              "numberOfInvitations" -> numberOfInvitations.toString,
              "createdDate"         -> DateTimeHelper.displayDate(headInv.created),
              "expiryDate"          -> DateTimeHelper.displayDate(headInv.expiryDate)
            )
          )
        )
    }
  }

  def sendExpiredEmail(invitation: Invitation)(implicit ec: ExecutionContext): Future[Boolean] = {
    implicit val hc: HeaderCarrier =
      HeaderCarrier(extraHeaders = Seq("Expired-Invitation" -> s"${invitation.invitationId}"))
    logger.info(s"[EmailService] sending email for invitation ${invitation.invitationId} that has expired")
    emailConnector.sendEmail(
      EmailInformation(
        to = Seq(invitation.agencyEmail),
        templateId = "client_expired_authorisation_request",
        parameters = Map(
          "agencyName" -> invitation.agencyName,
          "clientName" -> invitation.clientName,
          "expiryDate" -> DateTimeHelper.displayDate(invitation.expiryDate),
          "service"    -> messagesApi(s"service.${invitation.service}")
        )
      )
    )
  }

  def sendAcceptedEmail(invitation: Invitation)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Boolean] =
    sendEmail(invitation, "client_accepted_authorisation_request")

  def sendRejectedEmail(invitation: Invitation)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Boolean] =
    sendEmail(invitation, "client_rejected_authorisation_request")

  def sendEmail(invitation: Invitation, templateId: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Boolean] =
    for {
      agentDetailsDesResponse <- agentAssuranceConnector.getAgentRecordWithChecks(Arn(invitation.arn))
      emailInfo = emailInformation(templateId, invitation, agentDetailsDesResponse)
      emailResult <- emailConnector.sendEmail(emailInfo)
    } yield emailResult

  private def emailInformation(
    templateId: String,
    invitation: Invitation,
    agentDetailsDesResponse: AgentDetailsDesResponse
  ) = EmailInformation(
    Seq(agentDetailsDesResponse.agencyDetails.agencyEmail),
    templateId,
    Map(
      "agencyName" -> agentDetailsDesResponse.agencyDetails.agencyName,
      "clientName" -> invitation.clientName,
      "expiryDate" -> DateTimeHelper.displayDate(invitation.expiryDate),
      "service"    -> invitation.service,
      "additionalInfo" -> {
        if (invitation.isAltItsa)
          s"You must now sign ${invitation.clientName} up to Making Tax Digital for Income Tax."
        else ""
      }
    )
  )
}
