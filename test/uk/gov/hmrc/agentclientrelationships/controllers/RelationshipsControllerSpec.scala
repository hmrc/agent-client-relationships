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
import uk.gov.hmrc.agentclientrelationships.controllers.ErrorResults.NoPermissionOnAgencyOrClient
import uk.gov.hmrc.agentclientrelationships.controllers.actions.AgentOrClientRequest
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.Future

class RelationshipsControllerSpec extends UnitSpec with ResettingMockitoSugar with Results {
  private val controller = new Relationships(null, null)

  private val blockThatShouldBeCalled = Future successful Ok
  private val blockThatShouldNotCalled = Future failed new RuntimeException("Permission to call this block should be denied")

  private val matchingArn = Arn("TARN0000001")
  private val nonMatchingArn = Arn("AARN0000002")
  private val matchingMtdItId = MtdItId("AAAAAAAAAAAAAAA")
  private val nonMatchingMtdItId = MtdItId("BBBBBBBBBBBBBBB")

  "forThisAgentOrClient" should {
    "call the block when ARN matches but not MTDITID" in {
      callShouldBeAllowed(Some(matchingArn), None)
      callShouldBeAllowed(Some(matchingArn), Some(nonMatchingMtdItId))
    }

    "call the block when MTDITID matches but not ARN" in {
      callShouldBeAllowed(None, Some(matchingMtdItId))
      callShouldBeAllowed(Some(nonMatchingArn), Some(matchingMtdItId))
    }

    "call the block when ARN and MTDITID both match" in {
      callShouldBeAllowed(Some(matchingArn), Some(matchingMtdItId))
    }

    "return NoPermissionOnAgencyOrClient when neither ARN nor MTDITID match" in {
      callShouldBeDenied(Some(nonMatchingArn), Some(nonMatchingMtdItId))
    }

    "return NoPermissionOnAgencyOrClient when ARN and MTDITID are both None" in {
      callShouldBeDenied(None, None)
    }
  }

  private def callShouldBeAllowed(callingArn: Option[Arn], callingMtdItId: Option[MtdItId]) = {
    await(forThisAgentOrClient(blockThatShouldBeCalled)(callingArn, callingMtdItId)) shouldBe Ok
  }

  private def callShouldBeDenied(callingArn: Option[Arn], callingMtdItId: Option[MtdItId]) = {
    await(forThisAgentOrClient(blockThatShouldNotCalled)(callingArn, callingMtdItId)) shouldBe NoPermissionOnAgencyOrClient
  }

  private def forThisAgentOrClient(block: Future[Result])(callingArn: Option[Arn], callingMtdItId: Option[MtdItId]) = {
    controller.forThisAgentOrClient(matchingArn, matchingMtdItId)(block)(request(callingArn, callingMtdItId))
  }

  private def request(arn: Option[Arn], mtdItId: Option[MtdItId]) =
    AgentOrClientRequest[Unit](arn, mtdItId, null)
}
