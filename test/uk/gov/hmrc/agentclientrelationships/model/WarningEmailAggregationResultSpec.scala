/*
 * Copyright 2025 HM Revenue & Customs
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

import play.api.libs.json.JsObject
import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentclientrelationships.util.CryptoUtil.encryptedString
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.Hasher
import uk.gov.hmrc.crypto.OnewayCryptoFactory
import uk.gov.hmrc.crypto.PlainText
import uk.gov.hmrc.crypto.SymmetricCryptoFactory
import uk.gov.hmrc.crypto.Verifier

import java.time.Instant
import java.time.LocalDate

class WarningEmailAggregationResultSpec
extends UnitSpec {

  given crypto: (Encrypter & Decrypter) = SymmetricCryptoFactory.aesGcmCrypto("edkOOwt7uvzw1TXnFIN6aRVHkfWcgiOrbBvkEQvO65g=")
  given hmac: (Hasher & Verifier) = OnewayCryptoFactory.sha("oqrDUsOsuJ7hKctdLpQ5vQ==")

  val invitation: Invitation = Invitation(
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

  val invitation2: Invitation = invitation.copy(
    invitationId = "456",
    suppliedClientId = "888888888"
  )

  val json: JsObject = Json.obj(
    "_id" -> "TARN0000001",
    "invitations" ->
      Json.arr(
        Json.obj(
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
          "lastUpdated" -> Json.obj("$date" -> Json.obj("$numberLong" -> "1583193600000")),
          "arnHash" -> hmac.hash(PlainText("TARN0000001")).value,
          "suppliedClientIdHash" -> hmac.hash(PlainText("234567890")).value,
          "clientNameHash" -> hmac.hash(PlainText("Macrosoft")).value
        ),
        Json.obj(
          "invitationId" -> "456",
          "arn" -> encryptedString("TARN0000001"),
          "service" -> "HMRC-MTD-VAT",
          "suppliedClientId" -> encryptedString("888888888"),
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
          "lastUpdated" -> Json.obj("$date" -> Json.obj("$numberLong" -> "1583193600000")),
          "arnHash" -> hmac.hash(PlainText("TARN0000001")).value,
          "suppliedClientIdHash" -> hmac.hash(PlainText("888888888")).value,
          "clientNameHash" -> hmac.hash(PlainText("Macrosoft")).value
        )
      )
  )

  val model: WarningEmailAggregationResult = WarningEmailAggregationResult("TARN0000001", Seq(invitation, invitation2))

  "WarningEmailAggregationResult" should {

    "read from JSON" in {
      val js = json.as[WarningEmailAggregationResult]
      js shouldBe model
    }
  }

}
