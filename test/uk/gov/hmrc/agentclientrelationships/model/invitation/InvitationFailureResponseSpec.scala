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

package uk.gov.hmrc.agentclientrelationships.model.invitation

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.http.HttpEntity
import play.api.libs.json.Json
import play.api.test.Helpers

class InvitationFailureResponseSpec
extends AnyWordSpec
with Matchers {

  "RelationshipNotFound" should {
    "map to a 404 response with the expected body" in {
      val result = InvitationFailureResponse.RelationshipNotFound.getResult("")

      result.header.status shouldBe 404

      val json = Json.parse(bodyAsString(result))
      (json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
      (json \ "message").as[String] shouldBe "The specified relationship was not found."
    }
  }

  "RelationshipDeleteFailed" should {
    "map to a 500 response containing the failure message" in {
      val result = InvitationFailureResponse.RelationshipDeleteFailed("boom").getResult("")

      result.header.status shouldBe 500
      Json.parse(bodyAsString(result)).as[String] shouldBe "boom"
    }
  }

  private def bodyAsString(result: play.api.mvc.Result): String =
    result.body match {
      case HttpEntity.Strict(data, _) => data.utf8String
      case other => fail(s"Expected strict body but got $other")
    }

}
