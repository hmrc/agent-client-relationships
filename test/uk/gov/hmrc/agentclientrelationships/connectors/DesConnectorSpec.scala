/*
 * Copyright 2023 HM Revenue & Customs
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

import org.mockito.Mockito.when
import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.helpers.CorrelationIdGenerator
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.http.client.HttpClientV2

import scala.concurrent.ExecutionContext

class DesConnectorSpec extends UnitSpec with MockitoSugar {

  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  val appConfig: AppConfig = mock[AppConfig]
  val httpClient: HttpClientV2 = mock[HttpClientV2]
  val correlationIdGenerator: CorrelationIdGenerator = mock[CorrelationIdGenerator]
  val metrics: Metrics = mock[Metrics]

  val underTest =
    new DesConnector(
      httpClient,
      correlationIdGenerator,
      appConfig
    )(metrics, ec)

  "desHeaders" should {
    "contain correct headers" in {
      when(correlationIdGenerator.makeCorrelationId()).thenReturn("testCorrelationId")
      underTest.desHeaders(authToken = "testAuthToken", env = "testEnv").toMap shouldBe
        Map(
          "Authorization" -> "Bearer testAuthToken",
          "Environment"   -> "testEnv",
          "CorrelationId" -> "testCorrelationId"
        )
    }
  }

}
