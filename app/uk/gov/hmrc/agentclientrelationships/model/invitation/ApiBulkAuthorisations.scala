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

case class ApiBulkAuthorisation(
  created: Instant,
  service: String,
  status: InvitationStatus,
  expiresOn: LocalDate,
  invitationId: String,
  lastUpdated: Instant
)

object ApiBulkAuthorisation {
  implicit val format: Format[ApiBulkAuthorisation] = Json.format[ApiBulkAuthorisation]

  def createApiSeqAuthorisation(invitation: Invitation): ApiBulkAuthorisation =
    ApiBulkAuthorisation(
      created = invitation.created,
      service = invitation.service,
      status = invitation.status,
      expiresOn = invitation.expiryDate,
      invitationId = invitation.invitationId,
      lastUpdated = invitation.lastUpdated
    )

}

case class ApiSeqAuthorisations(
  uid: String,
  normalizedAgentName: String,
  invitations: Seq[ApiBulkAuthorisation]
)

object ApiSeqAuthorisations {
  implicit val format: Format[ApiSeqAuthorisations] = Json.format[ApiSeqAuthorisations]

  def createApiSeqAuthorisations(
    invitations: Seq[Invitation],
    uid: String,
    normalizedAgentName: String
  ): ApiSeqAuthorisations =
    ApiSeqAuthorisations(
      uid = uid,
      normalizedAgentName = normalizedAgentName,
      invitations = invitations
        .map(ApiBulkAuthorisation.createApiSeqAuthorisation)
    )
}
