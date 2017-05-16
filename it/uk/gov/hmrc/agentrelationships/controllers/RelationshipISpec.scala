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
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.agentrelationships.stubs.{DesStubs, GovernmentGatewayProxyStubs, MappingStubs}
import uk.gov.hmrc.agentrelationships.support.{Resource, WireMockSupport}
import uk.gov.hmrc.domain.{Nino, SaAgentReference}
import uk.gov.hmrc.play.test.UnitSpec

class RelationshipISpec extends UnitSpec with OneServerPerSuite with WireMockSupport with GovernmentGatewayProxyStubs with DesStubs with MappingStubs {

  override implicit lazy val app: Application = appBuilder
    .build()

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.government-gateway-proxy.port" -> wireMockPort,
        "microservice.services.des.port" -> wireMockPort,
        "microservice.services.agent-mapping.port" -> wireMockPort,
        "auditing.enabled" -> false
      )

  "GET /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:identifierValue" should {

    "return 200 when relationship exists in GG" in {
      givenAgentCredentialsAreFoundFor(Arn("AARN0000002"), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient("ABCDEF123456789", "bar")
      val result = await(doAgentRequest())
      result.status shouldBe 200
    }

    "return 200 when relationship exists only in CESA" in {
      val arn = Arn("AARN0000002")
      val nino = Nino("AB123456C")
      givenAgentCredentialsAreNotFoundFor(arn)
      givenNinoIsKnownFor(MtdItId("ABCDEF123456789"), nino)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgent(nino, "foo")
      val result = await(doAgentRequest())
      result.status shouldBe 200
    }

    //UNHAPPY PATHS

    "return 404 when credentials are not found in GG" in {
      givenAgentCredentialsAreNotFoundFor(Arn("AARN0000002"))
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient("ABCDEF123456789", "bar")
      val result = await(doAgentRequest())
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "INVALID_ARN"
    }

    "return 404 when agent code is not found in GG" in {
      givenAgentCredentialsAreFoundFor(Arn("AARN0000002"), "foo")
      givenAgentCodeIsNotInTheResponseFor("foo")
      givenAgentIsAllocatedAndAssignedToClient("ABCDEF123456789", "bar")
      val result = await(doAgentRequest())
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "UNKNOWN_AGENT_CODE"
    }

    "return 404 when agent not allocated to client in GG nor CESA" in {
      givenAgentCredentialsAreFoundFor(Arn("AARN0000002"), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient("ABCDEF123456789")
      givenNinoIsUnknownFor(MtdItId("ABCDEF123456789"))
      val result = await(doAgentRequest())
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    //FAILURE CASES

    "return 502 when GsoAdminGetCredentialsForDirectEnrolments returns 5xx" in {
      whenGetCredentialsReturns(500)
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient("ABCDEF123456789", "bar")
      val result = await(doAgentRequest())
      result.status shouldBe 502
    }

    "return 502 when GsoAdminGetUserDetails returns 5xx" in {
      givenAgentCredentialsAreFoundFor(Arn("AARN0000002"), "foo")
      whenGetUserDetailReturns(500)
      givenAgentIsAllocatedAndAssignedToClient("ABCDEF123456789", "bar")
      val result = await(doAgentRequest())
      result.status shouldBe 502
    }

    "return 502 when GsoAdminGetAssignedAgents returns 5xx" in {
      givenAgentCredentialsAreFoundFor(Arn("AARN0000002"), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      whenGetAssignedAgentsReturns(500)
      val result = await(doAgentRequest())
      result.status shouldBe 502
    }

    "return 400 when GsoAdminGetCredentialsForDirectEnrolments returns 4xx" in {
      whenGetCredentialsReturns(400)
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient("ABCDEF123456789", "bar")
      val result = await(doAgentRequest())
      result.status shouldBe 400
    }

    "return 400 when GsoAdminGetUserDetails returns 4xx" in {
      givenAgentCredentialsAreFoundFor(Arn("AARN0000002"), "foo")
      whenGetUserDetailReturns(400)
      givenAgentIsAllocatedAndAssignedToClient("ABCDEF123456789", "bar")
      val result = await(doAgentRequest())
      result.status shouldBe 400
    }

    "return 400 when GsoAdminGetAssignedAgents returns 4xx" in {
      givenAgentCredentialsAreFoundFor(Arn("AARN0000002"), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      whenGetAssignedAgentsReturns(400)
      val result = await(doAgentRequest())
      result.status shouldBe 400
    }
  }

  private def doAgentRequest() = new Resource(s"/agent-client-relationships/agent/AARN0000002/service/HMRC-MTD-IT/client/MTDITID/ABCDEF123456789", port).get()

}
