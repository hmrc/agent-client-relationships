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
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientStatus.Insolvent
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.KnownFactType.Email
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

class ClientDetailsResponseSpec
extends UnitSpec {

  "ClientDetailsResponse" should {

    "write to JSON" when {

      "optional fields are present" in {

        val model = ClientDetailsResponse(
          "Ilkay Gundo",
          Some(Insolvent),
          isOverseas = Some(true),
          Seq("test@email.com"),
          Some(Email),
          hasPendingInvitation = true,
          Some("HMRC-MTD-VAT")
        )

        val expectedJson = Json.obj(
          "name" -> "Ilkay Gundo",
          "status" -> "Insolvent",
          "isOverseas" -> true,
          "knownFacts" -> Json.arr("test@email.com"),
          "knownFactType" -> "Email",
          "hasPendingInvitation" -> true,
          "hasExistingRelationshipFor" -> "HMRC-MTD-VAT"
        )

        Json.toJson(model) shouldBe expectedJson
      }

      "optional fields are not present" in {
        val model = ClientDetailsResponse(
          "Ilkay Gundo",
          None,
          isOverseas = Some(true),
          Seq(),
          None
        )

        val expectedJson = Json.obj(
          "name" -> "Ilkay Gundo",
          "isOverseas" -> true,
          "knownFacts" -> Json.arr(),
          "hasPendingInvitation" -> false
        )

        Json.toJson(model) shouldBe expectedJson
      }
    }
  }

}
