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

import play.api.libs.json.Format
import play.api.libs.json.JsError
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentclientrelationships.util.CryptoUtil.encryptedString
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Vat
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Vrn
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.Hasher
import uk.gov.hmrc.crypto.OnewayCryptoFactory
import uk.gov.hmrc.crypto.PlainText
import uk.gov.hmrc.crypto.SymmetricCryptoFactory
import uk.gov.hmrc.crypto.Verifier

import java.time.Instant
import java.time.LocalDate

class InvitationSpec
extends UnitSpec {

  given crypto: (Encrypter & Decrypter) = SymmetricCryptoFactory.aesGcmCrypto("edkOOwt7uvzw1TXnFIN6aRVHkfWcgiOrbBvkEQvO65g=")
  given hmac: (Hasher & Verifier) = OnewayCryptoFactory.sha("oqrDUsOsuJ7hKctdLpQ5vQ==")

  val fullModel: Invitation = Invitation(
    "123",
    "TARN0000001",
    "HMRC-MTD-VAT",
    "234567890",
    "vrn",
    "Macrosoft",
    "testAgentName",
    "agent@email.com",
    warningEmailSent = false,
    expiredEmailSent = false,
    Pending,
    Some("Me"),
    Some("personal"),
    LocalDate.parse("2020-01-01"),
    Instant.parse("2020-02-02T00:00:00.000Z"),
    Instant.parse("2020-03-03T00:00:00.000Z")
  )

  val fullJson: JsObject = Json.obj(
    "invitationId" -> "123",
    "arn" -> encryptedString("TARN0000001"),
    "service" -> "HMRC-MTD-VAT",
    "suppliedClientId" -> encryptedString("234567890"),
    "suppliedClientIdType" -> "vrn",
    "clientName" -> encryptedString("Macrosoft"),
    "agencyName" -> encryptedString("testAgentName"),
    "agencyEmail" -> encryptedString("agent@email.com"),
    "warningEmailSent" -> false,
    "expiredEmailSent" -> false,
    "status" -> "Pending",
    "relationshipEndedBy" -> "Me",
    "clientType" -> "personal",
    "expiryDate" -> Json.obj("$date" -> Json.obj("$numberLong" -> "1577836800000")),
    "created" -> Json.obj("$date" -> Json.obj("$numberLong" -> "1580601600000")),
    "lastUpdated" -> Json.obj("$date" -> Json.obj("$numberLong" -> "1583193600000"))
  )

  val optionalJson: JsObject = fullJson.-("relationshipEndedBy").-("clientType")
  val optionalModel: Invitation = fullModel.copy(relationshipEndedBy = None, clientType = None)
  given Format[Invitation] = Invitation.mongoFormat

  "Invitation" should {
    "support JSON round trip" when {
      "all optional fields are present" in {
        val json = Json.toJson(fullModel)
        val result = json.as[Invitation]

        result shouldBe fullModel
      }
      "all optional fields are missing" in {
        val json = Json.toJson(optionalModel)
        val result = json.as[Invitation]

        result shouldBe optionalModel
      }
    }

    "encrypt sensitive fields" in {
      val json = Json.toJson(fullModel)

      (json \ "arn").as[String] should not be "TARN0000001"
      (json \ "suppliedClientId").as[String] should not be "234567890"
      (json \ "clientName").as[String] should not be "Macrosoft"
      (json \ "agencyName").as[String] should not be "testAgentName"
      (json \ "agencyEmail").as[String] should not be "agent@email.com"
    }

    "verify hashes" in {
      val json = Json.toJson(fullModel)
      val tampered =
        json.as[JsObject]
          + ("arn" -> Json.toJson(crypto.encrypt(PlainText("TARN0000002")).value))
          + ("suppliedClientId" -> Json.toJson(crypto.encrypt(PlainText("445577880")).value))
          + ("clientName" -> Json.toJson(crypto.encrypt(PlainText("Apple")).value))

      Json.fromJson[Invitation](tampered) shouldBe JsError("could not verify hash integrity")
    }

    "read an encrypted ARN" in {
      val json =
        Json.toJson(fullModel).as[JsObject] +
          ("arn" -> Json.toJson(crypto.encrypt(PlainText(fullModel.arn)).value))

      json.as[Invitation] shouldBe fullModel
    }

    "verify hashes" in {
      val json = Json.toJson(fullModel)
      val tampered =
        json.as[JsObject]
          + ("arn" -> Json.toJson(crypto.encrypt(PlainText("TARN0000002")).value))
          + ("suppliedClientId" -> Json.toJson(crypto.encrypt(PlainText("445577880")).value))
          + ("clientName" -> Json.toJson(crypto.encrypt(PlainText("Apple")).value))

      Json.fromJson[Invitation](tampered) shouldBe JsError("could not verify hash integrity")
    }

    "read an encrypted ARN" in {
      val json =
        Json.toJson(fullModel).as[JsObject] +
          ("arn" -> Json.toJson(crypto.encrypt(PlainText(fullModel.arn)).value))

      json.as[Invitation] shouldBe fullModel
    }

    "read legacy records without hashes" in:
      val json = Json.toJson(fullModel).as[JsObject] - "arnHash" - "suppliedClientIdHash" - "clientNameHash"

      json.as[Invitation] shouldBe fullModel

    "read a plaintext ARN" in:
      val json =
        Json.toJson(fullModel).as[JsObject] +
          ("arn" -> Json.toJson(fullModel.arn))

      json.as[Invitation] shouldBe fullModel
  }

  "Invitation.createNew" should {

    "instantiate a new Invitation using the given values, along with some default and generated values" in {
      val newModel = Invitation.createNew(
        "TARN0000001",
        Vat,
        Vrn("234567890"),
        "Macrosoft",
        "testAgentName",
        "agent@email.com",
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
