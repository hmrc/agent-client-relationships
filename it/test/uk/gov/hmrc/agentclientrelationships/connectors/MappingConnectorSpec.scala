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

import org.scalamock.scalatest.MockFactory
import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.stubs.{DataStreamStub, MappingStubs}
import uk.gov.hmrc.agentclientrelationships.support.{MetricTestSupport, UnitSpec, WireMockSupport}
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.{AgentCode, SaAgentReference}
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}

import scala.concurrent.ExecutionContext

class MappingConnectorSpec(implicit ec: ExecutionContext)
    extends UnitSpec
    with GuiceOneServerPerSuite
    with WireMockSupport
    with MappingStubs
    with DataStreamStub
    with MetricTestSupport
    with MockFactory {

  override implicit lazy val app: Application = appBuilder
    .build()
  val metrics: Metrics = mock[Metrics]
  val httpClient: HttpClient = app.injector.instanceOf[HttpClient]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
        "microservice.services.tax-enrolments.port"        -> wireMockPort,
        "microservice.services.users-groups-search.port"   -> wireMockPort,
        "microservice.services.des.port"                   -> wireMockPort,
        "microservice.services.auth.port"                  -> wireMockPort,
        "microservice.services.agent-mapping.port"         -> wireMockPort,
        "auditing.consumer.baseUri.host"                   -> wireMockHost,
        "auditing.consumer.baseUri.port"                   -> wireMockPort,
        "features.copy-relationship.mtd-it"                -> true,
        "features.copy-relationship.mtd-vat"               -> true,
        "features.recovery-enable"                         -> false,
        "agent.cache.size"                                 -> 1,
        "agent.cache.expires"                              -> "1 millis",
        "agent.cache.enabled"                              -> true,
        "agent.trackPage.cache.size"                       -> 1,
        "agent.trackPage.cache.expires"                    -> "1 millis",
        "agent.trackPage.cache.enabled"                    -> true
      )

  private implicit val hc: HeaderCarrier = HeaderCarrier()
  val mappingConnector = new MappingConnector(httpClient)(metrics, appConfig, ec)

  "MappingConnector" should {

    val arn = Arn("foo")

    "return CESA agent reference for some known ARN" in {
      givenAuditConnector()
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      await(mappingConnector.getSaAgentReferencesFor(arn)) shouldBe Seq(SaAgentReference("foo"))
    }

    "return multiple CESA agent reference for some known ARN" in {
      val references = Seq(SaAgentReference("001"), SaAgentReference("002"))
      givenArnIsKnownFor(arn, references)
      givenAuditConnector()
      await(mappingConnector.getSaAgentReferencesFor(arn)) shouldBe references
    }

    "return empty sequence when arn is unknown in " in {
      givenArnIsUnknownFor(arn)
      givenAuditConnector()
      await(mappingConnector.getSaAgentReferencesFor(arn)) shouldBe empty
    }

    "return empty sequence when mapping service is unavailable" in {
      givenServiceReturnsServiceUnavailable()
      givenAuditConnector()
      await(mappingConnector.getSaAgentReferencesFor(arn)) shouldBe empty
    }

    "return empty sequence when mapping service is throwing errors" in {
      givenServiceReturnsServerError()
      givenAuditConnector()
      await(mappingConnector.getSaAgentReferencesFor(arn)) shouldBe empty
    }

    "record metrics for Mappings" in {
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenCleanMetricRegistry()
      givenAuditConnector()
      await(mappingConnector.getSaAgentReferencesFor(arn))
      timerShouldExistsAndBeenUpdated("ConsumedAPI-Digital-Mappings-GET")
    }

    "return agent codes for some known ARN" in {
      givenArnIsKnownFor(arn, AgentCode("foo"))
      givenAuditConnector()
      await(mappingConnector.getAgentCodesFor(arn)) shouldBe Seq(AgentCode("foo"))
    }

    "return multiple agent codes for some known ARN" in {
      val oldAgentCodes = Seq(AgentCode("001"), AgentCode("002"))
      givenArnIsKnownForAgentCodes(arn, oldAgentCodes)
      givenAuditConnector()
      await(mappingConnector.getAgentCodesFor(arn)) shouldBe oldAgentCodes
    }
  }
}
