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
import uk.gov.hmrc.agentmtdidentifiers.model.Service.Vat
import uk.gov.hmrc.agentmtdidentifiers.model.Vrn

import java.time.LocalDate
import java.time.Instant

class InvitationSpec extends UnitSpec {

  val fullModel: Invitation = Invitation(
    "123",
    "XARN1234567",
    "HMRC-MTD-VAT",
    "123456789",
    "vrn",
    "234567890",
    "vrn",
    "Macrosoft",
    Pending,
    Some("Me"),
    Some("personal"),
    LocalDate.parse("2020-01-01"),
    Instant.parse("2020-02-02T00:00:00.000Z"),
    Instant.parse("2020-03-03T00:00:00.000Z")
  )

  val fullJson: JsObject = Json.obj(
    "invitationId"         -> "123",
    "arn"                  -> "XARN1234567",
    "service"              -> "HMRC-MTD-VAT",
    "clientId"             -> "123456789",
    "clientIdType"         -> "vrn",
    "suppliedClientId"     -> "234567890",
    "suppliedClientIdType" -> "vrn",
    "clientName"           -> "Macrosoft",
    "status"               -> "Pending",
    "relationshipEndedBy"  -> "Me",
    "clientType"           -> "personal",
    "expiryDate"           -> Json.obj("$date" -> Json.obj("$numberLong" -> "1577836800000")),
    "created"              -> Json.obj("$date" -> Json.obj("$numberLong" -> "1580601600000")),
    "lastUpdated"          -> Json.obj("$date" -> Json.obj("$numberLong" -> "1583193600000"))
  )

  val optionalJson: JsObject = fullJson.-("relationshipEndedBy").-("clientType")
  val optionalModel: Invitation = fullModel.copy(relationshipEndedBy = None, clientType = None)

  "Invitation" should {

    "read from JSON" when {

      "all optional fields are present" in {
        fullJson.as[Invitation](Invitation.mongoFormat) shouldBe fullModel
      }

      "all optional fields are missing" in {
        optionalJson.as[Invitation](Invitation.mongoFormat) shouldBe optionalModel
      }
    }

    "write to JSON" when {

      "all optional fields are present" in {
        Json.toJson(fullModel)(Invitation.mongoFormat) shouldBe fullJson
      }

      "all optional fields are missing" in {
        Json.toJson(optionalModel)(Invitation.mongoFormat) shouldBe optionalJson
      }
    }
  }

  "Invitation.createNew" should {

    "instantiate a new Invitation using the given values, along with some default and generated values" in {
      val newModel = Invitation.createNew(
        "XARN1234567",
        Vat,
        Vrn("123456789"),
        Vrn("234567890"),
        "Macrosoft",
        LocalDate.parse("2020-01-01"),
        None
      )

      val expectedModel = optionalModel.copy(
        invitationId = newModel.invitationId,
        created = newModel.created,
        lastUpdated = newModel.lastUpdated
      )

      newModel shouldBe expectedModel
    }
  }
}
