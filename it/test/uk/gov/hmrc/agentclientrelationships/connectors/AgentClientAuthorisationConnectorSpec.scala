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

import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.stubs.ACAStubs
import uk.gov.hmrc.agentclientrelationships.support.{UnitSpec, WireMockSupport}
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDIT
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import scala.concurrent.ExecutionContext

class AgentClientAuthorisationConnectorSpec
    extends UnitSpec
    with GuiceOneServerPerSuite
    with WireMockSupport
    with ACAStubs
    with MockitoSugar {

  override implicit lazy val app: Application = appBuilder.build()

  val httpClient: HttpClient = app.injector.instanceOf[HttpClient]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.agent-client-authorisation.port" -> wireMockPort
      )

  private implicit val hc: HeaderCarrier = HeaderCarrier()
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val agentARN: Arn = Arn("ABCDE123456")
  val nino: Nino = Nino("AB213308A")

  val acaConnector = new AgentClientAuthorisationConnector(
    httpClient
  )(app.injector.instanceOf[Metrics], appConfig, ec)

  "getPartialAuthExistsFor" should {

    "return a list of services where a record exists with status PartialAuth for client" in {
      givenPartialAuthExistsFor(agentARN, nino, HMRCMTDIT)
      val result = acaConnector.getPartialAuth(nino, agentARN))
      result shouldBe List(HMRCMTDIT)
    }

    "return an empty list when no record exists with PartialAuth for client" in {
      givenPartialAuthNotExistsFor(agentARN, nino)
      val result = await(acaConnector.getPartialAuth(nino, agentARN))
      result shouldBe List()
    }

    "return an empty list when there is a problem with the upstream service" in {
      givenAgentClientAuthorisationReturnsError(agentARN, nino, 503)
      val result = await(acaConnector.getPartialAuth(nino, agentARN))
      result shouldBe List()
    }
  }

  "updateAltItsaFor" should {
    "return true when response is 201" in {
      givenAltItsaUpdate(nino, responseStatus = 201)
      val result = await(acaConnector.updateAltItsaFor(nino, HMRCMTDIT))
      result shouldBe true
    }
  }

  "updateStatusToAccepted" should {
    "return true" in {
      givenUpdateStatusToAccepted(nino, responseStatus = 204)
      val result = await(acaConnector.updateStatusToAccepted(nino, HMRCMTDIT))
      result shouldBe true
    }
  }
}
