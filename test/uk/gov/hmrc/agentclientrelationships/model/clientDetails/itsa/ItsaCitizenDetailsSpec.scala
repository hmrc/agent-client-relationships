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

package uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa

import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

import java.time.LocalDate

class ItsaCitizenDetailsSpec extends UnitSpec {

  "ItsaCitizenDetails" should {

    "read from JSON" when {

      "optional fields are present" in {
        val json = Json.obj(
          "name" -> Json.obj(
            "current" -> Json.obj(
              "firstName" -> "Matthew",
              "lastName"  -> "Kovacic"
            )
          ),
          "dateOfBirth" -> "01012000",
          "ids" -> Json.obj(
            "sautr" -> "11223344"
          )
        )

        json.as[ItsaCitizenDetails] shouldBe
          ItsaCitizenDetails(Some("Matthew"), Some("Kovacic"), Some(LocalDate.parse("2000-01-01")), Some("11223344"))
      }

      "optional fields are not present" in {
        val json = Json.obj(
          "name" -> Json.obj(
            "current" -> Json.obj()
          )
        )

        json.as[ItsaCitizenDetails] shouldBe ItsaCitizenDetails(None, None, None, None)
      }
    }
  }
}
