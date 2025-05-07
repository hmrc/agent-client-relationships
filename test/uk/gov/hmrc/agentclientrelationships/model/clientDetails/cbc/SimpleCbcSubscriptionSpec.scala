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

package uk.gov.hmrc.agentclientrelationships.model.clientDetails.cbc

import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

class SimpleCbcSubscriptionSpec extends UnitSpec {

  "SimpleCbcSubscription" should {

    "read from JSON" when {

      "optional fields are present" in {

        val json = Json.obj(
          "displaySubscriptionForCBCResponse" -> Json.obj(
            "responseDetail" -> Json.obj(
              "isGBUser"    -> true,
              "tradingName" -> "CFG Solutions",
              "primaryContact" -> Json.arr(
                Json.obj(
                  "email"        -> "test@email.com",
                  "individual"   -> Json.obj("firstName" -> "Erling", "lastName" -> "Haal"),
                  "organisation" -> Json.obj("organisationName" -> "CFG")
                )
              ),
              "secondaryContact" -> Json.arr(
                Json.obj(
                  "email"        -> "test2@email.com",
                  "individual"   -> Json.obj("firstName" -> "Kevin", "lastName" -> "De Burner"),
                  "organisation" -> Json.obj("organisationName" -> "CFG")
                )
              )
            )
          )
        )

        json.as[SimpleCbcSubscription] shouldBe SimpleCbcSubscription(
          Some("CFG Solutions"),
          Seq("Erling Haal", "Kevin De Burner"),
          isGBUser = true,
          Seq("test@email.com", "test2@email.com")
        )
      }

      "optional fields are not present" in {

        val json = Json.obj(
          "displaySubscriptionForCBCResponse" -> Json.obj(
            "responseDetail" -> Json
              .obj("isGBUser" -> true, "primaryContact" -> Json.arr(), "secondaryContact" -> Json.arr())
          )
        )

        json.as[SimpleCbcSubscription] shouldBe SimpleCbcSubscription(None, Seq(), isGBUser = true, Seq())
      }
    }
  }

  "DisplaySubscriptionForCBCRequest" should {

    "write to JSON" in {

      val model = DisplaySubscriptionForCBCRequest(
        DisplaySubscriptionDetails(
          RequestCommonForSubscription("CBC", "2020-01-01T00:00:00Z", "abc123", "MDTP", Some("abc123")),
          ReadSubscriptionRequestDetail("CBC", "XCBCX1234567890")
        )
      )

      val expectedJson = Json.obj(
        "displaySubscriptionForCBCRequest" -> Json.obj(
          "requestCommon" -> Json.obj(
            "regime"                   -> "CBC",
            "receiptDate"              -> "2020-01-01T00:00:00Z",
            "acknowledgementReference" -> "abc123",
            "originatingSystem"        -> "MDTP",
            "conversationID"           -> "abc123"
          ),
          "requestDetail" -> Json.obj("IDType" -> "CBC", "IDNumber" -> "XCBCX1234567890")
        )
      )

      Json.toJson(model) shouldBe expectedJson
    }
  }
}
