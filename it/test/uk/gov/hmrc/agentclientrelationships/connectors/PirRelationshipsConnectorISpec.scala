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
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.stubs.AfiRelationshipStub
import uk.gov.hmrc.agentclientrelationships.support.{MetricTestSupport, UnitSpec, WireMockSupport}
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

class PirRelationshipsConnectorISpec
    extends UnitSpec
    with GuiceOneServerPerSuite
    with WireMockSupport
    with AfiRelationshipStub
    with MetricTestSupport {

  override implicit lazy val app: Application = appBuilder
    .build()

  val httpClient: HttpClient = app.injector.instanceOf[HttpClient]
  val metrics: Metrics = app.injector.instanceOf[Metrics]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
        "microservice.services.tax-enrolments.port"        -> wireMockPort,
        "microservice.services.users-groups-search.port"   -> wireMockPort,
        "microservice.services.des.port"                   -> wireMockPort,
        "microservice.services.auth.port"                  -> wireMockPort,
        "microservice.services.agent-fi-relationship.port" -> wireMockPort,
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

  val pirRelationshipConnector =
    new PirRelationshipConnector(httpClient)(appConfig, metrics, ec)

  val arn: Arn = Arn("ABCDE123456")
  val service: Service = Service.PersonalIncomeRecord
  val clientId = "AA000001B"

  "GetInactiveIrvRelationships" should {
    "return a Some true if PersonalIncomeRecord has been deleted" in {
      givenTerminateAfiRelationshipSucceeds(arn, service.id, clientId)
      await(pirRelationshipConnector.deleteRelationship(arn, service, clientId)) shouldBe Some(true)
    }
  }
}
