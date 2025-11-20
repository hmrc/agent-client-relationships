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

package uk.gov.hmrc.agentclientrelationships.model.clientDetails

import play.api.libs.json.Json
import play.api.libs.json.OFormat
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientStatus.ClientStatus
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.KnownFactType.KnownFactType

case class ClientDetailsResponse(
  name: String,
  status: Option[ClientStatus],
  isOverseas: Option[Boolean],
  knownFacts: Seq[String],
  knownFactType: Option[KnownFactType],
  hasPendingInvitation: Boolean = false,
  hasExistingRelationshipFor: Option[String] = None,
  isMapped: Option[Boolean] = None,
  clientsLegacyRelationships: Option[Seq[String]] = None
) {
  def containsKnownFact(knownFact: String): Boolean = knownFacts
    .map(_.replaceAll("\\s", "").toUpperCase)
    .contains(knownFact.replaceAll("\\s", "").toUpperCase)
}

object ClientDetailsResponse {
  implicit val format: OFormat[ClientDetailsResponse] = Json.format[ClientDetailsResponse]
}
