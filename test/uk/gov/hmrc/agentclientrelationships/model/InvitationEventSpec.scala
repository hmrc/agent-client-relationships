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

package uk.gov.hmrc.agentclientrelationships.model

import play.api.libs.json.{JsObject, Json}
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

import java.time.Instant

class InvitationEventSpec extends UnitSpec {

  val fullModel: InvitationEvent = InvitationEvent(
    Pending,
    Instant.parse("2020-02-02T00:00:00.000Z"),
    "XARN1234567",
    "HMRC-MTD-VAT",
    "123456789",
    Some("Me")
  )

  val fullJson: JsObject = Json.obj(
    "status"         -> "Pending",
    "created"        -> Json.obj("$date" -> Json.obj("$numberLong" -> "1580601600000")),
    "arn"            -> "XARN1234567",
    "service"        -> "HMRC-MTD-VAT",
    "clientId"       -> "123456789",
    "deauthorisedBy" -> "Me"
  )

  val optionalJson: JsObject = fullJson.-("deauthorisedBy")
  val optionalModel: InvitationEvent = fullModel.copy(deauthorisedBy = None)

  "InvitationEvent" should {

    "read from JSON" when {

      "all optional fields are present" in {
        fullJson.as[InvitationEvent] shouldBe fullModel
      }

      "all optional fields are missing" in {
        optionalJson.as[InvitationEvent] shouldBe optionalModel
      }
    }

    "write to JSON" when {

      "all optional fields are present" in {
        Json.toJson(fullModel) shouldBe fullJson
      }

      "all optional fields are missing" in {
        Json.toJson(optionalModel) shouldBe optionalJson
      }
    }
  }
}
