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

package uk.gov.hmrc.agentclientrelationships.model.stride

import play.api.libs.json.Json
import play.api.libs.json.OFormat
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

//for helpdesk auth journey
case class InvitationWithAgentName(
  clientType: String,
  arn: Arn,
  service: String,
  status: String,
  expiryDate: LocalDate,
  lastUpdated: LocalDateTime,
  invitationId: String,
  agencyName: String,
  clientName: String,
  agentIsSuspended: Boolean = false,
  isAltItsa: Boolean = false
)

object InvitationWithAgentName {

  implicit val formats: OFormat[InvitationWithAgentName] = Json.format[InvitationWithAgentName]

  def fromInvitationAndAgentRecord(
    invitation: Invitation,
    agentRecord: AgentDetailsDesResponse
  ): InvitationWithAgentName = InvitationWithAgentName(
    clientType = invitation.clientType.getOrElse("personal"),
    arn = Arn(invitation.arn),
    service = invitation.service,
    status = invitation.status.toString,
    expiryDate = invitation.expiryDate,
    lastUpdated = LocalDateTime.ofInstant(invitation.lastUpdated, ZoneId.of("UTC")),
    invitationId = invitation.invitationId,
    agencyName = agentRecord.agencyDetails.agencyName,
    clientName = invitation.clientName,
    agentIsSuspended = agentRecord.suspensionDetails.exists(_.suspensionStatus),
    isAltItsa = invitation.isAltItsa
  )

}
