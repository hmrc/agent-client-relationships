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

import com.kenshoo.play.metrics.Metrics
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.services.AgentCacheProvider
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.http.{Authorization, HeaderCarrier, HttpClient, RequestId, SessionId}

class DesConnectorSpec extends UnitSpec with MockitoSugar {

  val appConfig: AppConfig = mock[AppConfig]
  val httpClient: HttpClient = mock[HttpClient]
  val metrics: Metrics = mock[Metrics]
  val agentCacheProvider: AgentCacheProvider = mock[AgentCacheProvider]

  val underTest = new DesConnector(httpClient, metrics, agentCacheProvider)(appConfig)

  "desHeaders" should {
    "contain correct headers" when {
      "internally hosted service" in {

        val hc = HeaderCarrier(
          authorization = Some(Authorization("auth-123")),
          sessionId = Some(SessionId("session-123")),
          requestId = Some(RequestId("request-123"))
        )

        val authToken: String = "testAuthToken"
        val env: String = "testEnv"

        val headersMap = underTest.desHeaders(authToken, env, isInternalHost = true)(hc).toMap

        headersMap should contain("Environment" -> "testEnv")
        headersMap.contains("CorrelationId") shouldBe true
      }

      "externally hosted service" in {

        val hc = HeaderCarrier(
          authorization = Some(Authorization("auth-123")),
          sessionId = Some(SessionId("session-123")),
          requestId = Some(RequestId("request-123"))
        )

        val authToken: String = "testAuthToken"
        val env: String = "testEnv"

        val headersMap = underTest.desHeaders(authToken, env, isInternalHost = false)(hc).toMap

        headersMap should contain("Authorization" -> "Bearer testAuthToken")
        headersMap should contain("Environment" -> "testEnv")
        headersMap should contain("X-Session-ID" -> "session-123")
        headersMap.contains("CorrelationId") shouldBe true
        headersMap.contains("X-Request-ID") shouldBe true
      }
    }
  }
}
