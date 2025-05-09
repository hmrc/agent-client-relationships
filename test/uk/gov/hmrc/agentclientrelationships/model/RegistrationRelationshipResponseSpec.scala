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

package uk.gov.hmrc.agentclientrelationships.model

import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

class RegistrationRelationshipResponseSpec
extends UnitSpec {

  "RegistrationRelationshipResponse" should {
    val successResult = RegistrationRelationshipResponse("2025-02-07T10:10:10.000Z")
    "reads JSON as per IF response type" in {

      val jsonStr = """{"processingDate": "2025-02-07T10:10:10.000Z"}"""

      Json.parse(jsonStr).asOpt[RegistrationRelationshipResponse] shouldBe Some(successResult)
    }
    "reads JSON as per HIP response type" in {

      val jsonStr = """{"success":{"processingDate": "2025-02-07T10:10:10.000Z"}}"""

      Json.parse(jsonStr).asOpt[RegistrationRelationshipResponse] shouldBe Some(successResult)
    }

    "not read if incorrect JSON supplied" in {

      val jsonStr = """{"failed":{"processingDate": "2025-02-07T10:10:10.000Z"}}"""

      Json.parse(jsonStr).asOpt[RegistrationRelationshipResponse] shouldBe None

    }

  }

}
