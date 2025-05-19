/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.model.invitation

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.agentclientrelationships.model.{Invitation, InvitationStatus}

import java.time.{Instant, LocalDate}

case class ApiInvitationResponse(
  uid: String,
  normalizedAgentName: String,
  created: Instant,
  service: String,
  status: InvitationStatus,
  expiresOn: LocalDate,
  invitationId: String,
  lastUpdated: Instant
)

object ApiInvitationResponse {

  implicit val format: Format[ApiInvitationResponse] = Json.format[ApiInvitationResponse]

  def createApiInvitationResponse(
    invitation: Invitation,
    uid: String,
    normalizedAgentName: String
  ): ApiInvitationResponse = ApiInvitationResponse(
    uid = uid,
    normalizedAgentName = normalizedAgentName,
    created = invitation.created,
    service = invitation.service,
    status = invitation.status,
    expiresOn = invitation.expiryDate,
    invitationId = invitation.invitationId,
    lastUpdated = invitation.lastUpdated
  )

}
