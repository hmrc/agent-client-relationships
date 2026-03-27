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

package uk.gov.hmrc.agentclientrelationships.connectors

import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.controllers.BaseControllerISpec
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.domain.SaAgentReference
import uk.gov.hmrc.http.UpstreamErrorResponse

import scala.concurrent.ExecutionContext

class DesConnectorISpec
extends BaseControllerISpec {

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  val desConnector: DesConnector = app.injector.instanceOf[DesConnector]

  "DesConnector GetStatusAgentRelationship" should {

    val nino = NinoWithoutSuffix("AB123456D")

    "return a CESA identifier when client has an active agent" in {
      val agentId = "bar"
      givenClientHasRelationshipWithAgentInCESA(nino, agentId)
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe Seq(SaAgentReference(agentId))
    }

    "return multiple CESA identifiers when client has multiple active agents" in {
      val agentIds = Seq(
        "001",
        "002",
        "003",
        "004",
        "005",
        "005",
        "007"
      )
      givenClientHasRelationshipWithMultipleAgentsInCESA(nino, agentIds)
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) should contain theSameElementsAs agentIds.map(
        SaAgentReference.apply
      )
    }

    "return empty seq when client has no active relationship with an agent" in {
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe empty
    }

    "return empty seq when client has/had no relationship with any agent" in {
      givenClientHasNoRelationshipWithAnyAgentInCESA(nino)
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe empty
    }

    "return empty seq when client relationship with agent ceased" in {
      givenClientRelationshipWithAgentCeasedInCESA(nino, "foo")
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe empty
    }

    "return empty seq when all client's relationships with agents ceased" in {
      givenAllClientRelationshipsWithAgentsCeasedInCESA(
        nino,
        Seq(
          "001",
          "002",
          "003",
          "004",
          "005",
          "005",
          "007"
        )
      )
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe empty
    }

    "throw error when client's nino is invalid" in {
      givenNinoIsInvalid(nino)
      givenAuditConnector()
      intercept[UpstreamErrorResponse](await(desConnector.getClientSaAgentSaReferences(nino))).statusCode shouldBe 400
    }

    "return a CESA identifier when client has an active agent but with a different NINO suffix" in {
      givenClientIsUnknownInCESAFor(nino)
      givenClientIsUnknownInCESAFor(NinoWithoutSuffix("AB123456A"))
      givenClientIsUnknownInCESAFor(NinoWithoutSuffix("AB123456B"))
      val agentId = "bar"

      givenClientHasRelationshipWithAgentInCESA(NinoWithoutSuffix("AB123456C"), agentId)
      givenAuditConnector()

      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe Seq(SaAgentReference(agentId))
    }

    "return empty seq when client is unknown for all nino variations" in {
      givenClientIsUnknownInCESAFor(nino)
      givenClientIsUnknownInCESAFor(NinoWithoutSuffix("AB123456A"))
      givenClientIsUnknownInCESAFor(NinoWithoutSuffix("AB123456B"))
      givenClientIsUnknownInCESAFor(NinoWithoutSuffix("AB123456C"))
      givenClientIsUnknownInCESAFor(NinoWithoutSuffix("AB123456"))

      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe empty
    }

    "throw error when DES is unavailable" in {
      givenDesReturnsServiceUnavailable()
      givenAuditConnector()
      intercept[UpstreamErrorResponse](await(desConnector.getClientSaAgentSaReferences(nino))).statusCode shouldBe 503
    }

    "throw error when DES is throwing errors" in {
      givenDesReturnsServerError()
      givenAuditConnector()
      intercept[UpstreamErrorResponse](await(desConnector.getClientSaAgentSaReferences(nino))).statusCode shouldBe 500
    }
  }

}
