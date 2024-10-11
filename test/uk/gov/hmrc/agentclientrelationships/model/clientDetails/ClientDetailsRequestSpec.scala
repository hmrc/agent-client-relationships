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

package uk.gov.hmrc.agentclientrelationships.model.clientDetails

import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

class ClientDetailsRequestSpec extends UnitSpec {

  "ClientDetailsRequest" should {

    "read from JSON" in {

      val json = Json.obj(
        "clientDetails" -> Json.arr(
          Json.obj(
            "key" -> "postcode",
            "value" -> "AA1 1AA"
          ),
          Json.obj(
            "key" -> "nino",
            "value" -> "AA000001B"
          )
        )
      )

      json.as[ClientDetailsRequest] shouldBe ClientDetailsRequest(Map("postcode" -> "AA1 1AA", "nino" -> "AA000001B"))
    }
  }
}
