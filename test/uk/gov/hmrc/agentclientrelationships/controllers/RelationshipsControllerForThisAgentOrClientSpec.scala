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

package uk.gov.hmrc.agentclientrelationships.controllers

import play.api.mvc.{Result, Results}
import uk.gov.hmrc.agentclientrelationships.auth.AgentOrClientRequest
import uk.gov.hmrc.agentclientrelationships.controllers.ErrorResults.NoPermissionOnAgencyOrClient
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.Future

class RelationshipsControllerForThisAgentOrClientSpec extends UnitSpec with ResettingMockitoSugar with Results {
  private val controller = new Relationships(null, null)

  private val blockThatShouldBeCalled = Future successful Ok
  private val blockThatShouldNotCalled = Future failed new RuntimeException("Permission to call this block should be denied")

  private val matchingArn = Arn("TARN0000001")
  private val nonMatchingArn = Arn("AARN0000002")
  private val matchingMtdItId = MtdItId("AAAAAAAAAAAAAAA")
  private val nonMatchingMtdItId = MtdItId("BBBBBBBBBBBBBBB")

  "forThisAgentOrClient" should {
    "call the block when the logged in user's tax identifier matches the supplied arn or mtdItId" in {
      callShouldBeAllowed(matchingArn)
      callShouldBeAllowed(matchingMtdItId)
    }

    "return NoPermissionOnAgencyOrClient when logged in user's tax identifier do not match the supplied arn or mtdItId" in {
      callShouldBeDenied(nonMatchingArn)
      callShouldBeDenied(nonMatchingMtdItId)
    }
  }

  private def callShouldBeAllowed(taxIdentifier: TaxIdentifier) = {
    await(forThisAgentOrClient(blockThatShouldBeCalled)(taxIdentifier)) shouldBe Ok
  }

  private def callShouldBeDenied(taxIdentifier: TaxIdentifier) = {
    await(forThisAgentOrClient(blockThatShouldNotCalled)(taxIdentifier)) shouldBe NoPermissionOnAgencyOrClient
  }

  private def forThisAgentOrClient(block: Future[Result])(taxIdentifier: TaxIdentifier) = {
    controller.forThisAgentOrClient(matchingArn, matchingMtdItId)(block)(request(taxIdentifier))
  }

  private def request(taxIdentifier: TaxIdentifier) =
    AgentOrClientRequest[Unit](taxIdentifier, null)
}
