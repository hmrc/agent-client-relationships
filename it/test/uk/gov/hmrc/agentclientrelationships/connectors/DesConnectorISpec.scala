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

import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.AgentRecord
import uk.gov.hmrc.agentclientrelationships.services.AgentCacheProvider
import uk.gov.hmrc.agentclientrelationships.stubs.{DataStreamStub, DesStubs, DesStubsGet, IFStubs}
import uk.gov.hmrc.agentclientrelationships.support.{MetricTestSupport, UnitSpec, WireMockSupport}
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.{Nino, SaAgentReference}
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}

import scala.concurrent.ExecutionContext

class DesConnectorISpec(implicit val ec: ExecutionContext)
    extends UnitSpec
    with GuiceOneServerPerSuite
    with WireMockSupport
    with DesStubs
    with DesStubsGet
    with DataStreamStub
    with IFStubs
    with MetricTestSupport {

  override implicit lazy val app: Application = appBuilder
    .build()

  val httpClient: HttpClient = app.injector.instanceOf[HttpClient]
  val metrics: Metrics = app.injector.instanceOf[Metrics]
  val agentCacheProvider: AgentCacheProvider = app.injector.instanceOf[AgentCacheProvider]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
        "microservice.services.tax-enrolments.port"        -> wireMockPort,
        "microservice.services.users-groups-search.port"   -> wireMockPort,
        "microservice.services.des.port"                   -> wireMockPort,
        "microservice.services.auth.port"                  -> wireMockPort,
        "microservice.services.des.environment"            -> "stub",
        "microservice.services.des.authorization-token"    -> "token",
        "microservice.services.agent-mapping.port"         -> wireMockPort,
        "auditing.consumer.baseUri.host"                   -> wireMockHost,
        "auditing.consumer.baseUri.port"                   -> wireMockPort,
        "features.copy-relationship.mtd-it"                -> true,
        "features.copy-relationship.mtd-vat"               -> true,
        "features.recovery-enable"                         -> false,
        "agent.cache.expires"                              -> "1 millis",
        "agent.cache.enabled"                              -> false,
        "agent.trackPage.cache.expires"                    -> "1 millis",
        "agent.trackPage.cache.enabled"                    -> false
      )

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  val desConnector =
    new DesConnector(httpClient, agentCacheProvider, ec)(metrics, appConfig)

  val mtdItId: MtdItId = MtdItId("ABCDEF123456789")
  val vrn: Vrn = Vrn("101747641")
  val agentARN: Arn = Arn("ABCDE123456")
  val utr: Utr = Utr("1704066305")
  val cgt: CgtRef = CgtRef("XMCGTP837878749")

  "DesConnector GetStatusAgentRelationship" should {

    val nino = Nino("AB123456C")

    "return a CESA identifier when client has an active agent" in {
      val agentId = "bar"
      givenClientHasRelationshipWithAgentInCESA(nino, agentId)
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe Seq(SaAgentReference(agentId))
    }

    "return multiple CESA identifiers when client has multiple active agents" in {
      val agentIds = Seq("001", "002", "003", "004", "005", "005", "007")
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
      givenAllClientRelationshipsWithAgentsCeasedInCESA(nino, Seq("001", "002", "003", "004", "005", "005", "007"))
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

    "record metrics for GetStatusAgentRelationship Cesa" in {
      givenClientHasRelationshipWithAgentInCESA(nino, "bar")
      givenCleanMetricRegistry()
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino))
      timerShouldExistsAndBeenUpdated("ConsumedAPI-DES-GetStatusAgentRelationship-GET")
    }
  }

  "DesConnector GetAgentRecord" should {

    "get agentRecord detail should retrieve agent record from DES" in {
      getAgentRecordForClient(agentARN)

      await(desConnector.getAgentRecord(agentARN)) should be(
        Some(AgentRecord(Some(SuspensionDetails(suspensionStatus = false, Some(Set.empty)))))
      )
    }

    "throw an IllegalArgumentException when the tax identifier is not supported" in {
      an[Exception] should be thrownBy await(desConnector.getAgentRecord(Eori("foo")))
    }

  }

  "Des Connector vrnIsKnownInETMP" should {
    "return true when the vrn is known in ETMP" in {
      givenAuditConnector()
      getVrnIsKnownInETMPFor(vrn)
      val result = await(desConnector.vrnIsKnownInEtmp(vrn))
      result shouldBe true
    }

    "return false when the vrn is not known in ETMP" in {
      givenAuditConnector()
      getVrnIsNotKnownInETMPFor(vrn)
      val result = await(desConnector.vrnIsKnownInEtmp(vrn))
      result shouldBe false
    }

    "return false when DES is unavailable" in {
      givenAuditConnector()
      givenDESRespondsWithStatusForVrn(vrn, 503)
      val result = await(desConnector.vrnIsKnownInEtmp(vrn))
      result shouldBe false
    }
  }
}
