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

package uk.gov.hmrc.agentclientrelationships.model.clientDetails.pillar2

import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

class Pillar2RecordSpec extends UnitSpec {

  "Pillar2Record" should {

    "read from JSON" when {

      "all optional fields are present" in {

        val json = Json.obj(
          "success" ->
            Json.obj(
              "upeDetails" -> Json.obj("organisationName" -> "CFG Solutions", "registrationDate" -> "2020-01-01"),
              "upeCorrespAddressDetails" -> Json.obj("countryCode" -> "GB"),
              "accountStatus"            -> Json.obj("inactive" -> true)
            )
        )

        json.as[Pillar2Record] shouldBe
          Pillar2Record(
            "CFG Solutions",
            "2020-01-01",
            "GB",
            inactive = true
          )
      }
    }

    "all optional fields are missing" in {

      val json = Json.obj(
        "success" ->
          Json.obj(
            "upeDetails" -> Json.obj("organisationName" -> "CFG Solutions", "registrationDate" -> "2020-01-01"),
            "upeCorrespAddressDetails" -> Json.obj("countryCode" -> "GB")
          )
      )

      json.as[Pillar2Record] shouldBe
        Pillar2Record(
          "CFG Solutions",
          "2020-01-01",
          "GB",
          inactive = false
        )
    }
  }
}
