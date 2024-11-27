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

package uk.gov.hmrc.agentclientrelationships.model.invitationLink

import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.model.Pending
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

import java.time.Instant

class ValidateInvitationResponseSpec extends UnitSpec {

  "ValidateInvitationResponse" should {

    "write to JSON" in {

      val model =
        ValidateInvitationResponse(
          "ABC123",
          "HMRC-MTD-IT",
          "ABC Accountants",
          Pending,
          Instant.parse("2020-01-01T00:00:00Z")
        )

      val json = Json.obj(
        "invitationId"     -> "ABC123",
        "serviceKey"       -> "HMRC-MTD-IT",
        "agentName"        -> "ABC Accountants",
        "status"           -> "Pending",
        "lastModifiedDate" -> "2020-01-01T00:00:00Z"
      )

      Json.toJson(model) shouldBe json
    }
  }
}
