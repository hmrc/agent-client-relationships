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

package uk.gov.hmrc.agentclientrelationships.model.invitationLink

import play.api.libs.json._
import uk.gov.hmrc.agentmtdidentifiers.model.SuspensionDetails

case class AgentDetailsDesResponse(
  agencyDetails: Option[AgencyDetails],
  suspensionDetails: Option[SuspensionDetails]
)

object AgentDetailsDesResponse {

  // When AgencyDetails Json read fails with JsError than parse as None
  private val reads: Reads[AgentDetailsDesResponse] = Reads { json =>
    for {
      agencyDetails <- (json \ "agencyDetails").validate[AgencyDetails].asOpt match {
                         case Some(p) => JsSuccess(Some(p))
                         case None    => JsSuccess(None)
                       }
      suspensionDetails <- (json \ "suspensionDetails").validateOpt[SuspensionDetails]
    } yield AgentDetailsDesResponse(agencyDetails, suspensionDetails)
  }

  private val writes: Writes[AgentDetailsDesResponse] = Json.writes[AgentDetailsDesResponse]

  implicit val agentDetailsDesResponseFormat: Format[AgentDetailsDesResponse] = Format(reads, writes)
}
