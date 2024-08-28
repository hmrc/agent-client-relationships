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
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentclientrelationships.stubs.ACAStubs
import uk.gov.hmrc.agentclientrelationships.support.{MetricTestSupport, WireMockSupport}
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}

import scala.concurrent.ExecutionContext

class AgentClientAuthorisationConnectorSpec(implicit ec: ExecutionContext)
    extends UnitSpec
    with GuiceOneServerPerSuite
    with WireMockSupport
    with ACAStubs
    with MetricTestSupport
    with MockitoSugar {

  override implicit lazy val app: Application = appBuilder
    .build()

  val httpClient: HttpClient = app.injector.instanceOf[HttpClient]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.agent-client-authorisation.port" -> wireMockPort
      )

  private implicit val hc: HeaderCarrier = HeaderCarrier()
  val agentARN: Arn = Arn("ABCDE123456")
  val nino: Nino = Nino("AB213308A")

  val acaConnector = new AgentClientAuthorisationConnector(
    httpClient
  )(app.injector.instanceOf[Metrics], appConfig, ec)

  "getPartialAuthExistsFor" should {

    "return true when a record exists with status PartialAuth for main agent type" in {
      givenPartialAuthExistsFor(agentARN, nino, HMRCMTDIT)
      givenCleanMetricRegistry()
      val result = await(acaConnector.getPartialAuthExistsFor(nino, agentARN))
      result shouldBe true
      timerShouldExistsAndBeenUpdated("ConsumedAPI-ACA-getPartialAuthExistsFor-GET")
    }

    "return true when a record exists with status PartialAuth for supporting agent type" in {
      givenPartialAuthExistsFor(agentARN, nino, HMRCMTDITSUPP)
      givenCleanMetricRegistry()
      val result = await(acaConnector.getPartialAuthExistsFor(nino, agentARN))
      result shouldBe true
      timerShouldExistsAndBeenUpdated("ConsumedAPI-ACA-getPartialAuthExistsFor-GET")
    }

    "return false when no record exists with PartialAuth for client" in {
      givenPartialAuthNotExistsFor(agentARN, nino)
      givenCleanMetricRegistry()
      val result = await(acaConnector.getPartialAuthExistsFor(nino, agentARN))
      result shouldBe false
      timerShouldExistsAndBeenUpdated("ConsumedAPI-ACA-getPartialAuthExistsFor-GET")
    }

    "return false when there is a problem with the upstream service" in {
      givenAgentClientAuthorisationReturnsError(agentARN, nino, 503)
      givenCleanMetricRegistry()
      val result = await(acaConnector.getPartialAuthExistsFor(nino, agentARN))
      result shouldBe false
      timerShouldExistsAndBeenUpdated("ConsumedAPI-ACA-getPartialAuthExistsFor-GET")
    }
  }

  "updateAltItsaFor" should {
    "return true when response is 201" in {
      givenAltItsaUpdate(nino, responseStatus = 201)
      givenCleanMetricRegistry()
      val result = await(acaConnector.updateAltItsaFor(nino, HMRCMTDIT))
      result shouldBe true
      timerShouldExistsAndBeenUpdated("ConsumedAPI-ACA-updateAltItsaForHMRC-MTD-IT-PUT")

    }
  }
}
