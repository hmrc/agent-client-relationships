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

import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.helpers.CorrelationIdGenerator
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.agentclientrelationships.stubs.DataStreamStub
import uk.gov.hmrc.agentclientrelationships.stubs.DesStubs
import uk.gov.hmrc.agentclientrelationships.stubs.DesStubsGet
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentclientrelationships.support.WireMockSupport
import uk.gov.hmrc.domain.SaAgentReference
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import scala.concurrent.ExecutionContext

class DesConnectorISpec
extends UnitSpec
with GuiceOneServerPerSuite
with WireMockSupport
with DesStubs
with DesStubsGet
with DataStreamStub {

  override implicit lazy val app: Application = appBuilder.build()

  val httpClient: HttpClientV2 = app.injector.instanceOf[HttpClientV2]
  val metrics: Metrics = app.injector.instanceOf[Metrics]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  protected def appBuilder: GuiceApplicationBuilder = new GuiceApplicationBuilder().configure(
    "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
    "microservice.services.tax-enrolments.port" -> wireMockPort,
    "microservice.services.users-groups-search.port" -> wireMockPort,
    "microservice.services.des.port" -> wireMockPort,
    "microservice.services.auth.port" -> wireMockPort,
    "microservice.services.des.environment" -> "stub",
    "microservice.services.des.authorization-token" -> "token",
    "microservice.services.agent-mapping.port" -> wireMockPort,
    "auditing.consumer.baseUri.host" -> wireMockHost,
    "auditing.consumer.baseUri.port" -> wireMockPort,
    "features.copy-relationship.mtd-it" -> true,
    "features.recovery-enable" -> false,
    "agent.cache.expires" -> "1 millis",
    "agent.cache.enabled" -> false
  )

  private implicit val request: RequestHeader = FakeRequest()
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  val desConnector =
    new DesConnector(
      httpClient,
      app.injector.instanceOf[CorrelationIdGenerator],
      appConfig
    )(metrics, ec)

  val mtdItId: MtdItId = MtdItId("ABCDEF123456789")
  val agentARN: Arn = Arn("ABCDE123456")
  val utr: Utr = Utr("1704066305")
  val cgt: CgtRef = CgtRef("XMCGTP837878749")

  "DesConnector GetStatusAgentRelationship" should {

    val nino = NinoWithoutSuffix("AB123456")

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

    "return empty seq when client's nino is invalid" in {
      givenNinoIsInvalid(nino)
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe empty
    }

    "return empty seq when client is unknown" in {
      givenClientIsUnknownInCESAFor(nino)
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe empty
    }

    "return empty seq when DES is unavailable" in {
      givenDesReturnsServiceUnavailable()
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe empty
    }

    "return empty seq when DES is throwing errors" in {
      givenDesReturnsServerError()
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe empty
    }
  }

}
