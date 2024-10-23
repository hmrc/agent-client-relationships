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

class ItsaDesignatoryDetailsSpec extends UnitSpec {

  "ItsaDesignatoryDetails" should {

    "read from JSON" when {

      "optional fields are present" in {
        val json = Json.obj(
          "address" -> Json.obj(
            "postcode" -> "AA1 1AA"
          )
        )

        json.as[ItsaDesignatoryDetails] shouldBe ItsaDesignatoryDetails(Some("AA1 1AA"))
      }

      "optional fields are not present" in {
        val json = Json.obj()

        json.as[ItsaDesignatoryDetails] shouldBe ItsaDesignatoryDetails(None)
      }
    }
  }
}
