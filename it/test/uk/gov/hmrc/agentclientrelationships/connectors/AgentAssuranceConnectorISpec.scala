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
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.agentclientrelationships.stubs.AgentAssuranceStubs
import uk.gov.hmrc.agentclientrelationships.support.{TestData, UnitSpec, WireMockSupport}
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}

import scala.concurrent.ExecutionContext.Implicits.global

class AgentAssuranceConnectorISpec
    extends UnitSpec
    with GuiceOneServerPerSuite
    with WireMockSupport
    with TestData
    with AgentAssuranceStubs {

  override lazy val app: Application = appBuilder.build()

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.agent-assurance.port" -> wireMockPort,
        "auditing.consumer.baseUri.host"             -> wireMockHost,
        "auditing.consumer.baseUri.port"             -> wireMockPort
      )

  implicit val hc: HeaderCarrier = HeaderCarrier()

  private lazy val connector = app.injector.instanceOf[AgentAssuranceConnector]

  "getAgentRecord" should {
    "return the agent record for a given agent" in {

      givenAgentRecordFound(agentRecordResponse)

      await(connector.getAgentRecordWithChecks) shouldBe Right(agentRecord)
    }

    "throw exception when 502 response" in {
      givenAgentDetailsErrorResponse(502)
      intercept[UpstreamErrorResponse] {
        await(connector.getAgentRecordWithChecks)
      }
    }
  }

}
