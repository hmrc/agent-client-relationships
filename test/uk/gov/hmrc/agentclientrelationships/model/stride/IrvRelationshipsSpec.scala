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

package uk.gov.hmrc.agentclientrelationships.model.stride

import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

class IrvRelationshipsSpec
extends UnitSpec {

  "IrvRelationships" should {

    "write to JSON" in {
      val model = IrvRelationships(
        "Kevin De Burner",
        "AA110011A",
        Seq(IrvAgent("Pep Guardi", "XARN1234567"), IrvAgent("Erling Haal", "TARN1234567"))
      )
      val json = Json.obj(
        "clientName" -> "Kevin De Burner",
        "nino" -> "AA110011A",
        "agents" -> Json.arr(
          Json.obj("name" -> "Pep Guardi", "arn" -> "XARN1234567"),
          Json.obj("name" -> "Erling Haal", "arn" -> "TARN1234567")
        )
      )

      Json.toJson(model) shouldBe json
    }
  }
}
