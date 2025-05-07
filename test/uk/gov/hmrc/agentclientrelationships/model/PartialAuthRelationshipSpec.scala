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
import uk.gov.hmrc.agentclientrelationships.util.CryptoUtil.encryptedString
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, SymmetricCryptoFactory}

import java.time.Instant

class PartialAuthRelationshipSpec
extends UnitSpec {

  implicit val crypto: Encrypter
    with Decrypter = SymmetricCryptoFactory.aesCrypto(
    "edkOOwt7uvzw1TXnFIN6aRVHkfWcgiOrbBvkEQvO65g="
  )

  val activeTestModel: PartialAuthRelationship = PartialAuthRelationship(
    Instant.parse("2020-02-02T00:00:00.000Z"),
    "XARN1234567",
    "HMRC-MTD-VAT",
    "123456789",
    active = true,
    Instant.parse("2020-02-02T00:00:00.000Z")
  )

  val testJsonResponse: JsObject = Json.obj(
    "created"     -> Json.obj("$date" -> Json.obj("$numberLong" -> "1580601600000")),
    "arn"         -> "XARN1234567",
    "service"     -> "HMRC-MTD-VAT",
    "nino"        -> encryptedString("123456789"),
    "active"      -> true,
    "lastUpdated" -> Json.obj("$date" -> Json.obj("$numberLong" -> "1580601600000"))
  )

  "PartialAuthModel" should {

    "read from JSON" when {

      "all fields are present" in {
        testJsonResponse.as[PartialAuthRelationship](PartialAuthRelationship.mongoFormat) shouldBe activeTestModel
      }
    }

    "write to JSON" when {

      "all fields are present" in {
        Json.toJson(activeTestModel)(PartialAuthRelationship.mongoFormat) shouldBe testJsonResponse
      }
    }
  }
}
