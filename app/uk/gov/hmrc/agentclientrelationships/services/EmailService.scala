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

import uk.gov.hmrc.agentclientrelationships.connectors.{AgentAssuranceConnector, EmailConnector}
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.agentclientrelationships.model.{EmailInformation, Invitation}
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.http.HeaderCarrier

import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import scala.annotation.unused
import scala.concurrent.{ExecutionContext, Future}

class EmailService @Inject() (
  agentAssuranceConnector: AgentAssuranceConnector,
  emailConnector: EmailConnector
) {

  @unused
  def sendExpiredEmail(invitation: Invitation)(implicit ec: ExecutionContext): Future[Unit] = {
    implicit val hc: HeaderCarrier =
      HeaderCarrier(extraHeaders = Seq("Expired-Invitation" -> s"${invitation.invitationId}"))
    sendEmail(invitation, "client_expired_authorisation_request")
  }

  def sendAcceptedEmail(invitation: Invitation)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Unit] =
    sendEmail(invitation, "client_accepted_authorisation_request")

  def sendRejectedEmail(invitation: Invitation)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Unit] =
    sendEmail(invitation, "client_rejected_authorisation_request")

  def sendEmail(invitation: Invitation, templateId: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit] =
    for {
      agentDetailsDesResponse <- agentAssuranceConnector.getAgentRecordWithChecks(Arn(invitation.arn))
      emailInfo = emailInformation(templateId, invitation, agentDetailsDesResponse)
      _ <- emailConnector.sendEmail(emailInfo)
    } yield ()

  private def emailInformation(
    templateId: String,
    invitation: Invitation,
    agentDetailsDesResponse: AgentDetailsDesResponse
  ) = {
    val dateFormatter: DateTimeFormatter =
      DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.UK)

    EmailInformation(
      Seq(agentDetailsDesResponse.agencyDetails.agencyEmail),
      templateId,
      Map(
        "agencyName" -> agentDetailsDesResponse.agencyDetails.agencyName,
        "clientName" -> invitation.clientName,
        "expiryDate" -> invitation.expiryDate.format(dateFormatter),
        "service"    -> invitation.service,
        "additionalInfo" -> {
          if (invitation.isAltItsa)
            s"You must now sign ${invitation.clientName} up to Making Tax Digital for Income Tax."
          else ""
        }
      )
    )
  }
}
