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

package uk.gov.hmrc.agentclientrelationships.model.clientDetails.ppt

import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

import java.time.LocalDate

class PptSubscriptionDetailsSpec extends UnitSpec {

  "PptSubscriptionDetails" should {

    "read from JSON" when {

      "the data indicates the client is an individual" in {

        val json = Json.obj(
          "legalEntityDetails" -> Json.obj(
            "dateOfApplication" -> "2020-01-01",
            "customerDetails" -> Json.obj(
              "customerType"      -> "Individual",
              "individualDetails" -> Json.obj("firstName" -> "Bernard", "lastName" -> "Silver")
            )
          ),
          "changeOfCircumstanceDetails" -> Json.obj(
            "deregistrationDetails" -> Json.obj("deregistrationDate" -> "2030-01-01")
          )
        )

        val expectedModel =
          PptSubscriptionDetails("Bernard Silver", LocalDate.parse("2020-01-01"), Some(LocalDate.parse("2030-01-01")))

        json.as[PptSubscriptionDetails] shouldBe expectedModel
      }

      "the data indicates the client is an organisation" in {

        val json = Json.obj(
          "legalEntityDetails" -> Json.obj(
            "dateOfApplication" -> "2020-01-01",
            "customerDetails" -> Json.obj(
              "customerType"        -> "Organisation",
              "organisationDetails" -> Json.obj("organisationName" -> "CFG Solutions")
            )
          ),
          "changeOfCircumstanceDetails" -> Json.obj(
            "deregistrationDetails" -> Json.obj("deregistrationDate" -> "2030-01-01")
          )
        )

        val expectedModel =
          PptSubscriptionDetails("CFG Solutions", LocalDate.parse("2020-01-01"), Some(LocalDate.parse("2030-01-01")))

        json.as[PptSubscriptionDetails] shouldBe expectedModel
      }

      "optional fields are missing" in {

        val json = Json.obj(
          "legalEntityDetails" -> Json.obj(
            "dateOfApplication" -> "2020-01-01",
            "customerDetails" -> Json.obj(
              "customerType"        -> "Organisation",
              "organisationDetails" -> Json.obj("organisationName" -> "CFG Solutions")
            )
          )
        )

        val expectedModel =
          PptSubscriptionDetails("CFG Solutions", LocalDate.parse("2020-01-01"), None)

        json.as[PptSubscriptionDetails] shouldBe expectedModel
      }
    }
  }
}
