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

import play.api.libs.json.Format
import play.api.libs.json.JsResult
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.OFormat
import uk.gov.hmrc.agentmtdidentifiers.model.Arn

case class ValidateLinkResponse(
  arn: Arn,
  name: String
)

object ValidateLinkResponse {

  implicit val arnFormat: Format[Arn] =
    new Format[Arn] {
      def writes(arn: Arn): JsValue = JsString(arn.value)
      def reads(json: JsValue): JsResult[Arn] = json.validate[String].map(Arn.apply)
    }

  implicit val format: OFormat[ValidateLinkResponse] = Json.format[ValidateLinkResponse]

}
