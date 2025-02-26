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

package uk.gov.hmrc.agentclientrelationships.model.stride

import play.api.libs.json.{Json, OFormat}
import uk.gov.hmrc.agentclientrelationships.model.ActiveRelationship

case class ActiveClientRelationship(
  clientId: String,
  clientName: String,
  arn: String,
  agentName: String,
  service: String
)

object ActiveClientRelationship {
  implicit val activeRelationshipformat: OFormat[ActiveRelationship] = Json.format[ActiveRelationship]
  implicit val format: OFormat[ActiveClientRelationship] = Json.format[ActiveClientRelationship]
}

case class ActiveClientsRelationshipResponse(activeClientRelationships: Seq[ActiveClientRelationship])

object ActiveClientsRelationshipResponse {
  implicit val format: OFormat[ActiveClientsRelationshipResponse] = Json.format[ActiveClientsRelationshipResponse]
}
