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

  val arn = "AARN0000002"
  val mtditid = "ABCDEF123456789"
  val nino = "AB123456C"

  "GET /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:identifierValue" should {

    "return 200 when relationship exists in gg" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtditid, "bar")
      val result = await(doAgentRequest())
      result.status shouldBe 200
    }

    "return 200 when relationship exists only in cesa" in {
      givenAgentCredentialsAreNotFoundFor(Arn(arn))
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgent(Nino(nino), "foo")
      val result = await(doAgentRequest())
      result.status shouldBe 200
    }

    //UNHAPPY PATHS

    "return 404 when credentials are not found in gg" in {
      givenAgentCredentialsAreNotFoundFor(Arn(arn))
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtditid, "bar")
      val result = await(doAgentRequest())
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "INVALID_ARN"
    }

    "return 404 when agent code is not found in gg" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsNotInTheResponseFor("foo")
      givenAgentIsAllocatedAndAssignedToClient(mtditid, "bar")
      val result = await(doAgentRequest())
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "UNKNOWN_AGENT_CODE"
    }

    //CESA CHECK UNHAPPY PATHS

    "return 404 when agent not allocated to client in gg nor nino not found in des" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenNinoIsUnknownFor(MtdItId(mtditid))
      val result = await(doAgentRequest())
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in gg nor cesa" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenNinoIsKnownFor(MtdItId(mtditid),Nino(nino))
      givenClientHasNoActiveRelationshipWithAgent(Nino(nino))
      val result = await(doAgentRequest())
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in gg and also cesa mapping not found" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenNinoIsKnownFor(MtdItId(mtditid),Nino(nino))
      givenClientHasRelationshipWithAgent(Nino(nino), "foo")
      givenArnIsUnknownFor(Arn(arn))
      val result = await(doAgentRequest())
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    //FAILURE CASES

    "return 502 when GsoAdminGetCredentialsForDirectEnrolments returns 5xx" in {
      whenGetCredentialsReturns(500)
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtditid, "bar")
      val result = await(doAgentRequest())
      result.status shouldBe 502
    }

    "return 502 when GsoAdminGetUserDetails returns 5xx" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      whenGetUserDetailReturns(500)
      givenAgentIsAllocatedAndAssignedToClient(mtditid, "bar")
      val result = await(doAgentRequest())
      result.status shouldBe 502
    }

    "return 502 when GsoAdminGetAssignedAgents returns 5xx" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      whenGetAssignedAgentsReturns(500)
      val result = await(doAgentRequest())
      result.status shouldBe 502
    }

    "return 400 when GsoAdminGetCredentialsForDirectEnrolments returns 4xx" in {
      whenGetCredentialsReturns(400)
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtditid, "bar")
      val result = await(doAgentRequest())
      result.status shouldBe 400
    }

    "return 400 when GsoAdminGetUserDetails returns 4xx" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      whenGetUserDetailReturns(400)
      givenAgentIsAllocatedAndAssignedToClient(mtditid, "bar")
      val result = await(doAgentRequest())
      result.status shouldBe 400
    }

    "return 400 when GsoAdminGetAssignedAgents returns 4xx" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      whenGetAssignedAgentsReturns(400)
      val result = await(doAgentRequest())
      result.status shouldBe 400
    }
  }

  private def doAgentRequest() = new Resource(s"/agent-client-relationships/agent/$arn/service/HMRC-MTD-IT/client/MTDITID/$mtditid", port).get()

}
