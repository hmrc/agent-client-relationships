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

package uk.gov.hmrc.agentrelationships.controllers

import play.api.libs.json.JsObject
import play.api.test.FakeRequest
import uk.gov.hmrc.http.HttpResponse

class RelationshipsControllerGetActiveISpec extends RelationshipsControllerISpec {

  "GET /relationships/active" should {

    val requestPath: String = s"/agent-client-relationships/relationships/active"

    def doRequest: HttpResponse = doAgentGetRequest(requestPath)

    val req = FakeRequest()

    //HAPPY PATH :-)

    "return 200 with a map of active relationships when all exist for the same agent" in {
      givenAuthorisedAsClient(req, mtdItId, vrn)

      givenClientHasActiveAgentRelationshipForITSA(mtdItIdUriEncoded, arn.value)
      givenClientHasActiveAgentRelationshipForVAT(vrnUriEncoded, arn.value)

      val result = await(doRequest)
      result.status shouldBe 200
      val response = result.json.as[JsObject]

      response should havePropertyArrayOf[String]("HMRC-MTD-IT", be(arn.value))
      response should havePropertyArrayOf[String]("HMRC-MTD-VAT", be(arn.value))
    }

    "return 200 with a map of active relationships when all exist for different agents" in {
      givenAuthorisedAsClient(req, mtdItId, vrn)

      givenClientHasActiveAgentRelationshipForITSA(mtdItIdUriEncoded, arn2.value)
      givenClientHasActiveAgentRelationshipForVAT(vrnUriEncoded, arn3.value)

      val result = await(doRequest)
      result.status shouldBe 200
      val response = result.json.as[JsObject]

      response should havePropertyArrayOf[String]("HMRC-MTD-IT", be(arn2.value))
      response should havePropertyArrayOf[String]("HMRC-MTD-VAT", be(arn3.value))
    }

    "return 200 with a map of active relationships when only mtd-it is active" in {
      givenAuthorisedAsClient(req, mtdItId, vrn)

      givenClientHasActiveAgentRelationshipForITSA(mtdItIdUriEncoded, arn.value)
      givenClientHasInactiveAgentRelationshipForVAT(vrnUriEncoded, arn3.value)

      val result = await(doRequest)
      result.status shouldBe 200
      val response = result.json.as[JsObject]

      response should havePropertyArrayOf[String]("HMRC-MTD-IT", be(arn.value))
      response should notHaveProperty("HMRC-MTD-VAT")
    }

    "return 200 with a map of active relationships when only mtd-it exists" in {
      givenAuthorisedAsClient(req, mtdItId, vrn)

      givenClientHasActiveAgentRelationshipForITSA(mtdItIdUriEncoded, arn.value)
      givenClientAgentRelationshipCheckForVATFailsWith(vrnUriEncoded, status = 404)

      val result = await(doRequest)
      result.status shouldBe 200
      val response = result.json.as[JsObject]

      response should havePropertyArrayOf[String]("HMRC-MTD-IT", be(arn.value))
      response should notHaveProperty("HMRC-MTD-VAT")
    }

    "return 200 with a map of active relationships when only vat is active" in {
      givenAuthorisedAsClient(req, mtdItId, vrn)

      givenClientHasActiveAgentRelationshipForVAT(vrnUriEncoded, arn3.value)
      givenClientHasInactiveAgentRelationshipForITSA(mtdItIdUriEncoded, arn2.value)

      val result = await(doRequest)
      result.status shouldBe 200
      val response = result.json.as[JsObject]

      response should havePropertyArrayOf[String]("HMRC-MTD-VAT", be(arn3.value))
      response should notHaveProperty("HMRC-MTD-IT")
    }

    "return 200 with a map of active relationships when only vat exists" in {
      givenAuthorisedAsClient(req, mtdItId, vrn)

      givenClientHasActiveAgentRelationshipForVAT(vrnUriEncoded, arn.value)
      givenClientAgentRelationshipCheckForITSAFailsWith(mtdItIdUriEncoded, status = 404)

      val result = await(doRequest)
      result.status shouldBe 200
      val response = result.json.as[JsObject]

      response should havePropertyArrayOf[String]("HMRC-MTD-VAT", be(arn.value))
      response should notHaveProperty("HMRC-MTD-IT")
    }

    "return 200 with an empty map of active relationships when all are inactive" in {
      givenAuthorisedAsClient(req, mtdItId, vrn)

      givenClientHasInactiveAgentRelationshipForITSA(mtdItIdUriEncoded, arn2.value)
      givenClientHasInactiveAgentRelationshipForVAT(vrnUriEncoded, arn3.value)

      val result = await(doRequest)
      result.status shouldBe 200
      val response = result.json.as[JsObject]

      response should notHaveProperty("HMRC-MTD-IT")
      response should notHaveProperty("HMRC-MTD-VAT")
    }

    "return 200 with an empty map of active relationships when none exists" in {
      givenAuthorisedAsClient(req, mtdItId, vrn)

      givenClientAgentRelationshipCheckForITSAFailsWith(mtdItIdUriEncoded, status = 404)
      givenClientAgentRelationshipCheckForVATFailsWith(vrnUriEncoded, status = 404)

      val result = await(doRequest)
      result.status shouldBe 200
      val response = result.json.as[JsObject]

      response should notHaveProperty("HMRC-MTD-IT")
      response should notHaveProperty("HMRC-MTD-VAT")
    }
  }

}
