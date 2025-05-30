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

package uk.gov.hmrc.agentclientrelationships.model.clientDetails.vat

import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

import java.time.LocalDate

class VatCustomerDetailsSpec
extends UnitSpec {

  "VatCustomerDetails" should {

    "read from JSON" when {

      "optional fields are present" in {
        val json = Json.obj(
          "approvedInformation" ->
            Json.obj(
              "customerDetails" ->
                Json.obj(
                  "organisationName" -> "CFG",
                  "tradingName" -> "CFG Solutions",
                  "individual" ->
                    Json.obj(
                      "title" -> "0001",
                      "firstName" -> "Ilkay",
                      "middleName" -> "Silky",
                      "lastName" -> "Gundo"
                    ),
                  "effectiveRegistrationDate" -> "2020-01-01",
                  "isInsolvent" -> false
                )
            )
        )

        val expectedModel = VatCustomerDetails(
          Some("CFG"),
          Some(
            VatIndividual(
              Some("Mr"),
              Some("Ilkay"),
              Some("Silky"),
              Some("Gundo")
            )
          ),
          Some("CFG Solutions"),
          Some(LocalDate.parse("2020-01-01")),
          isInsolvent = false
        )

        json.as[VatCustomerDetails] shouldBe expectedModel
      }

      "optional fields are not present" in {
        val json = Json.obj("approvedInformation" -> Json.obj("customerDetails" -> Json.obj("isInsolvent" -> false)))

        json.as[VatCustomerDetails] shouldBe
          VatCustomerDetails(
            None,
            None,
            None,
            None,
            isInsolvent = false
          )
      }
    }
  }

  "VatIndividual.name" should {

    "combine the fields in the individual record into a readable String" when {

      "all fields are present" in {
        VatIndividual(
          Some("Mr"),
          Some("Ilkay"),
          Some("Silky"),
          Some("Gundo")
        ).name shouldBe "Mr Ilkay Silky Gundo"
      }

      "some fields are missing" in {
        VatIndividual(
          None,
          Some("Ilkay"),
          None,
          Some("Gundo")
        ).name shouldBe "Ilkay Gundo"
      }
    }
  }

}
