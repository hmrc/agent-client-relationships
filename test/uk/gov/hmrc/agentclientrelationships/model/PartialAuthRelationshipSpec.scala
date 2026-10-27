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
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.Hasher
import uk.gov.hmrc.crypto.OnewayCryptoFactory
import uk.gov.hmrc.crypto.PlainText
import uk.gov.hmrc.crypto.SymmetricCryptoFactory
import uk.gov.hmrc.crypto.Verifier

import java.time.Instant

class PartialAuthRelationshipSpec
extends UnitSpec:

  given crypto: (Encrypter & Decrypter) = SymmetricCryptoFactory.aesGcmCrypto("k6g1M7QnNpknAtFuTIpFtQ==")
  given hmac: (Hasher & Verifier) = OnewayCryptoFactory.sha("oqrDUsOsuJ7hKctdLpQ5vQ==")

  val activeTestModel: PartialAuthRelationship = PartialAuthRelationship(
    Instant.parse("2020-02-02T00:00:00.000Z"),
    "XARN1234567",
    "HMRC-MTD-VAT",
    "123456789",
    active = true,
    Instant.parse("2020-02-02T00:00:00.000Z")
  )

  "PartialAuthModel" when:
    "using the API format" should:
      given Format[PartialAuthRelationship] = Json.format[PartialAuthRelationship]

      "support JSON round-trip" in:
        val json = Json.toJson(activeTestModel)
        val result = json.as[PartialAuthRelationship]

        result shouldBe activeTestModel

      "not use encryption for sensitive fields" in:
        val json = Json.toJson(activeTestModel)

        (json \ "nino").as[String] shouldBe "123456789"
        (json \ "arn").as[String] shouldBe "XARN1234567"

    "using the database format" should:
      given Format[PartialAuthRelationship] = PartialAuthRelationship.mongoFormat

      "support JSON round-trip" in:
        val json = Json.toJson(activeTestModel)
        val result = json.as[PartialAuthRelationship]

        result shouldBe activeTestModel

      "encrypt sensitive fields" in:
        val json = Json.toJson(activeTestModel)

        (json \ "nino").as[String] should not be "123456789"
        (json \ "arn").as[String] should not be "XARN1234567"

      "verify hashes" in:
        val json = Json.toJson(activeTestModel)
        val tampered =
          json.as[JsObject]
            + ("nino" -> Json.toJson(crypto.encrypt(PlainText("987654321")).value))
            + ("arn" -> Json.toJson(crypto.encrypt(PlainText("XARN7654321")).value))

        Json.fromJson[PartialAuthRelationship](tampered) shouldBe JsError("could not verify hash integrity")
