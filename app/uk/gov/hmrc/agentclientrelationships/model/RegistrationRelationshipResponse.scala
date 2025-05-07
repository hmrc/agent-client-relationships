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

package uk.gov.hmrc.agentclientrelationships.model
import play.api.libs.json.{JsPath, Reads}

case class RegistrationRelationshipResponse(processingDate: String)

object RegistrationRelationshipResponse {
  // TODO: this reads is used only in one http connector but it is exposed globally. Move it to this connector
  implicit val regReads: Reads[RegistrationRelationshipResponse] =
    (JsPath \ "success" \ "processingDate")
      .read[String]
      .orElse((JsPath \ "processingDate").read[String])
      .map(r => RegistrationRelationshipResponse(r))
}
