/*
 * Copyright 2017 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships

import play.api.libs.json.JsValue
import uk.gov.hmrc.agentclientrelationships.support.MongoApp
import uk.gov.hmrc.play.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.test.UnitSpec

class RelationshipISpec extends UnitSpec with MongoApp {

  implicit val hc: HeaderCarrier = new HeaderCarrier()

  "PUT to /relationships/mtd-sa/:clientId/:arn" should {
    "create a relationship" in {
      val response = await(WSHttp.PUT[String, JsValue](urlFor("0123456789", "A9999B"), ""))

      (response \ "clientId").as[String] shouldBe "0123456789"
      (response \ "regime").as[String] shouldBe "mtd-sa"
      (response \ "arn").as[String] shouldBe "A9999B"
      (response \ "created").as[Long] should be >= 0L
    }

    "not create a duplicate relationship" in {
      val response1 = await(WSHttp.PUT[String, JsValue](urlFor("0123456788", "A9999B"), ""))
      val response2 = await(WSHttp.PUT[String, JsValue](urlFor("0123456788", "A9999B"), ""))

      val id1 = (response1 \ "id" \ "$oid").as[String]
      val id2 = (response2 \ "id" \ "$oid").as[String]

      id1 shouldBe id2
    }
  }

  "GET to /relationships/mtd-sa/:clientId/:arn" should {
    "Return an existing relationship" in {
      val response1 = await(WSHttp.PUT[String, JsValue](urlFor("0123456788", "A9999B"), ""))
      val response2 = await(WSHttp.GET[JsValue](urlFor("0123456788", "A9999B")))

      val id1 = (response1 \ "id" \ "$oid").as[String]
      val id2 = (response2 \ "id" \ "$oid").as[String]

      id1 shouldBe id2
    }

    "Not return a relationship which doesn't exist" in {
      val response = await(WSHttp.GET[Option[JsValue]](urlFor("0123456788", "A9999B")))

      response shouldBe None
    }

    "Not return a deleted relationship" in {
      await(WSHttp.PUT[String, JsValue](urlFor("0123456788", "A9999B"), ""))
      val deleteResponse = await(WSHttp.DELETE[HttpResponse](urlFor("0123456788", "A9999B")))
      val response = await(WSHttp.GET[Option[JsValue]](urlFor("0123456788", "A9999B")))

      deleteResponse.status shouldBe 204
      response shouldBe None
    }
  }

  "GET to /relationships/agent/:arn" should {
    "return all relationships for the agency" in {
      await(WSHttp.PUT[String, JsValue](urlFor("0123456788", "A9999B"), ""))
      await(WSHttp.PUT[String, JsValue](urlFor("0123456789", "A9999B"), ""))

      val response = await(WSHttp.GET[List[JsValue]](s"http://localhost:$port/agent-client-relationships/relationships/agent/A9999B"))

      response.size shouldBe 2
    }
  }

  "DELETE to /relationships/mtd-sa/:clientId/:arn" should {
    "remove an existing relationship" in {
      await(WSHttp.PUT[String, JsValue](urlFor("0123456788", "A9999B"), ""))
      val deleteResponse = await(WSHttp.DELETE[HttpResponse](urlFor("0123456788", "A9999B")))
      val response = await(WSHttp.GET[Option[JsValue]](urlFor("0123456788", "A9999B")))

      deleteResponse.status shouldBe 204
      response shouldBe None
    }

    "do nothing if a relationship doesn't exist" in {
      val deleteResponse = await(WSHttp.DELETE[HttpResponse](urlFor("0123456798", "C9999B")))

      deleteResponse.status shouldBe 204
    }
  }

  def urlFor(clientId: String, arn: String) = {
    s"http://localhost:$port/agent-client-relationships/relationships/mtd-sa/$clientId/$arn"
  }
}
