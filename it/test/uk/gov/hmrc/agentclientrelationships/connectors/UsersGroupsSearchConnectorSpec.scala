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
import uk.gov.hmrc.agentclientrelationships.stubs.DataStreamStub
import uk.gov.hmrc.agentclientrelationships.stubs.UsersGroupsSearchStubs
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentclientrelationships.support.WireMockSupport
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import scala.concurrent.ExecutionContext

class UsersGroupsSearchConnectorSpec
extends UnitSpec
with GuiceOneServerPerSuite
with WireMockSupport
with DataStreamStub
with UsersGroupsSearchStubs {

  override implicit lazy val app: Application = appBuilder.build()

  val httpClient: HttpClientV2 = app.injector.instanceOf[HttpClientV2]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  protected def appBuilder: GuiceApplicationBuilder = new GuiceApplicationBuilder().configure(
    "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
    "microservice.services.tax-enrolments.port" -> wireMockPort,
    "microservice.services.users-groups-search.port" -> wireMockPort,
    "microservice.services.des.port" -> wireMockPort,
    "microservice.services.auth.port" -> wireMockPort,
    "microservice.services.agent-mapping.port" -> wireMockPort,
    "auditing.consumer.baseUri.host" -> wireMockHost,
    "auditing.consumer.baseUri.port" -> wireMockPort,
    "features.copy-relationship.mtd-it" -> true,
    "features.recovery-enable" -> false,
    "agent.cache.expires" -> "1 millis",
    "agent.cache.enabled" -> true,
    "agent.trackPage.cache.expires" -> "1 millis",
    "agent.trackPage.cache.enabled" -> true
  )

  implicit val request: RequestHeader = FakeRequest()

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  val connector = new UsersGroupsSearchConnector(httpClient, app.injector.instanceOf[AppConfig])(app.injector.instanceOf[Metrics], ec)

  "UsersGroupsSearchConnector" should {

    "givenGroupInfo endpoint" should {

      "return some agentCode for a given agent's groupId" in {
        givenAuditConnector()
        givenAgentGroupExistsFor("foo")
        await(connector.getGroupInfo("foo")) shouldBe Some(
          GroupInfo(
            "foo",
            Some("Agent"),
            Some(AgentCode("NQJUEJCWT14"))
          )
        )
      }

      "return none agentCode for a given non-agent groupId" in {
        givenAuditConnector()
        givenNonAgentGroupExistsFor("foo")
        await(connector.getGroupInfo("foo")) shouldBe Some(
          GroupInfo(
            "foo",
            Some("Organisation"),
            None
          )
        )
      }

      "return none if group not exists" in {
        givenAuditConnector()
        givenGroupNotExistsFor("foo")
        await(connector.getGroupInfo("foo")) shouldBe None
      }
    }

  }

}
