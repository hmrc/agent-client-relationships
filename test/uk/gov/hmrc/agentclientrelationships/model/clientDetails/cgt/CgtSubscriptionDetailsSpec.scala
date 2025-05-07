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

package uk.gov.hmrc.agentclientrelationships.model.clientDetails.cgt

import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

class CgtSubscriptionDetailsSpec
extends UnitSpec {

  "CgtSubscriptionDetails" should {

    "read from JSON" when {

      "the data indicates the client is an individual" in {
        val json = Json.obj(
          "subscriptionDetails" -> Json.obj(
            "typeOfPersonDetails" -> Json.obj(
              "typeOfPerson" -> "Individual",
              "firstName"    -> "Erling",
              "lastName"     -> "Haal"
            ),
            "addressDetails" -> Json.obj("postalCode" -> "AA1 1AA", "countryCode" -> "GB")
          )
        )

        json.as[CgtSubscriptionDetails] shouldBe CgtSubscriptionDetails("Erling Haal", Some("AA1 1AA"), "GB")
      }

      "the data indicates the client is an organisation" in {
        val json = Json.obj(
          "subscriptionDetails" -> Json.obj(
            "typeOfPersonDetails" -> Json.obj("typeOfPerson" -> "Trustee", "organisationName" -> "CFG Solutions"),
            "addressDetails"      -> Json.obj("postalCode" -> "AA1 1AA", "countryCode" -> "GB")
          )
        )

        json.as[CgtSubscriptionDetails] shouldBe CgtSubscriptionDetails("CFG Solutions", Some("AA1 1AA"), "GB")
      }

      "the optional fields are missing" in {
        val json = Json.obj(
          "subscriptionDetails" -> Json.obj(
            "typeOfPersonDetails" -> Json.obj("typeOfPerson" -> "Trustee", "organisationName" -> "CFG Solutions"),
            "addressDetails"      -> Json.obj("countryCode" -> "GB")
          )
        )

        json.as[CgtSubscriptionDetails] shouldBe CgtSubscriptionDetails("CFG Solutions", None, "GB")
      }
    }
  }
}
