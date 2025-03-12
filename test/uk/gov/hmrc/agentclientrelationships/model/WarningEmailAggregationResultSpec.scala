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

import play.api.libs.json.{JsObject, Json}
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentclientrelationships.util.CryptoUtil.encryptedString
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, SymmetricCryptoFactory}

import java.time.{Instant, LocalDate}

class WarningEmailAggregationResultSpec extends UnitSpec {

  implicit val crypto: Encrypter with Decrypter =
    SymmetricCryptoFactory.aesCrypto("edkOOwt7uvzw1TXnFIN6aRVHkfWcgiOrbBvkEQvO65g=")

  val invitation: Invitation = Invitation(
    "123",
    "XARN1234567",
    "HMRC-MTD-VAT",
    "123456789",
    "vrn",
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

  val invitation2: Invitation =
    invitation.copy(invitationId = "456", clientId = "999999999", suppliedClientId = "888888888")

  val json: JsObject = Json.obj(
    "_id" -> "XARN1234567",
    "invitations" -> Json.arr(
      Json.obj(
        "invitationId"         -> "123",
        "arn"                  -> "XARN1234567",
        "service"              -> "HMRC-MTD-VAT",
        "clientId"             -> encryptedString("123456789"),
        "clientIdType"         -> "vrn",
        "suppliedClientId"     -> encryptedString("234567890"),
        "suppliedClientIdType" -> "vrn",
        "clientName"           -> encryptedString("Macrosoft"),
        "agencyName"           -> encryptedString("testAgentName"),
        "agencyEmail"          -> encryptedString("agent@email.com"),
        "warningEmailSent"     -> false,
        "expiredEmailSent"     -> false,
        "status"               -> "Pending",
        "relationshipEndedBy"  -> "Me",
        "clientType"           -> "personal",
        "expiryDate"           -> Json.obj("$date" -> Json.obj("$numberLong" -> "1577836800000")),
        "created"              -> Json.obj("$date" -> Json.obj("$numberLong" -> "1580601600000")),
        "lastUpdated"          -> Json.obj("$date" -> Json.obj("$numberLong" -> "1583193600000"))
      ),
      Json.obj(
        "invitationId"         -> "456",
        "arn"                  -> "XARN1234567",
        "service"              -> "HMRC-MTD-VAT",
        "clientId"             -> encryptedString("999999999"),
        "clientIdType"         -> "vrn",
        "suppliedClientId"     -> encryptedString("888888888"),
        "suppliedClientIdType" -> "vrn",
        "clientName"           -> encryptedString("Macrosoft"),
        "agencyName"           -> encryptedString("testAgentName"),
        "agencyEmail"          -> encryptedString("agent@email.com"),
        "warningEmailSent"     -> false,
        "expiredEmailSent"     -> false,
        "status"               -> "Pending",
        "relationshipEndedBy"  -> "Me",
        "clientType"           -> "personal",
        "expiryDate"           -> Json.obj("$date" -> Json.obj("$numberLong" -> "1577836800000")),
        "created"              -> Json.obj("$date" -> Json.obj("$numberLong" -> "1580601600000")),
        "lastUpdated"          -> Json.obj("$date" -> Json.obj("$numberLong" -> "1583193600000"))
      )
    )
  )

  val model: WarningEmailAggregationResult = WarningEmailAggregationResult("XARN1234567", Seq(invitation, invitation2))

  "WarningEmailAggregationResult" should {

    "read from JSON" in {
      json.as[WarningEmailAggregationResult] shouldBe model
    }
  }
}
