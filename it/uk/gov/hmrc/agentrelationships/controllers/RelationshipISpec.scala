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

import org.scalatestplus.play.OneServerPerSuite
import uk.gov.hmrc.agentsubscription.support.Resource
import uk.gov.hmrc.play.test.UnitSpec

class RelationshipISpec extends UnitSpec with OneServerPerSuite {

  "GET /agent/:ARN/service/:serviceName/client/:identifierKey/:identifierValue" in {

    "return 200 when relationship exists in GG" in {
      val result = await(doAgentRequest())
      result.status shouldBe 200
    }

    "return 404 when relationship doesn't exist in GG" in {
      val result = await(doAgentRequest())
      result.status shouldBe 404
    }

  }

  private def doAgentRequest() = new Resource(s"/agent-client-relationships/agent/AARN0000002/service/HMRC-MTD-IT/client/MTDITID/ABCDEF123456789", port).get()

}
