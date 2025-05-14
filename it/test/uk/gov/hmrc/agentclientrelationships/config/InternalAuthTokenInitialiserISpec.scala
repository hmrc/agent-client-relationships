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

package uk.gov.hmrc.agentclientrelationships.config

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.http.Status._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test.Helpers.AUTHORIZATION
import uk.gov.hmrc.agentclientrelationships.stubs.DataStreamStub
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentclientrelationships.support.WireMockSupport

class InternalAuthTokenInitialiserISpec
extends UnitSpec
with GuiceOneServerPerSuite
with WireMockSupport
with DataStreamStub {

  "when configured to run" should {
    "should initialise the internal-auth token if it is not already initialised" in {
      val authToken = "authToken"
      val appName = "appName"

      val expectedRequest = Json.obj(
        "token" -> authToken,
        "principal" -> appName,
        "permissions" -> Seq(
          Json.obj(
            "resourceType" -> "agent-assurance",
            "resourceLocation" -> "agent-record-with-checks/arn",
            "actions" -> List("WRITE")
          )
        )
      )

      stubFor(get(urlMatching("/test-only/token")).willReturn(aResponse().withStatus(NOT_FOUND)))

      stubFor(post(urlMatching("/test-only/token")).willReturn(aResponse().withStatus(CREATED)))

      new GuiceApplicationBuilder()
        .configure(
          "microservice.services.internal-auth.port" -> wireMockPort,
          "appName" -> appName,
          "internal-auth-token-enabled-on-start" -> true,
          "internal-auth.token" -> authToken
        )
        .build()

      verify(1, getRequestedFor(urlMatching("/test-only/token")).withHeader(AUTHORIZATION, equalTo(authToken)))
      verify(
        1,
        postRequestedFor(urlMatching("/test-only/token"))
          .withRequestBody(equalToJson(Json.stringify(Json.toJson(expectedRequest))))
      )
    }
    "should fail with exception the internal-auth token if it is not already initialised" in {
      val authToken = "authToken"
      val appName = "appName"

      val expectedRequest = Json.obj(
        "token" -> authToken,
        "principal" -> appName,
        "permissions" -> Seq(
          Json.obj(
            "resourceType" -> "agent-assurance",
            "resourceLocation" -> "agent-record-with-checks/arn",
            "actions" -> List("WRITE")
          )
        )
      )

      stubFor(get(urlMatching("/test-only/token")).willReturn(aResponse().withStatus(NOT_FOUND)))

      stubFor(post(urlMatching("/test-only/token")).willReturn(aResponse().withStatus(OK)))

      val exception = intercept[RuntimeException] {
        new GuiceApplicationBuilder()
          .configure(
            "microservice.services.internal-auth.port" -> wireMockPort,
            "appName" -> appName,
            "internal-auth-token-enabled-on-start" -> true,
            "internal-auth.token" -> authToken
          )
          .build()
      }

      exception.getMessage should include("Unable to initialise internal-auth token")

      verify(1, getRequestedFor(urlMatching("/test-only/token")).withHeader(AUTHORIZATION, equalTo(authToken)))
      verify(
        1,
        postRequestedFor(urlMatching("/test-only/token"))
          .withRequestBody(equalToJson(Json.stringify(Json.toJson(expectedRequest))))
      )
    }
    "should not initialise the internal-auth token if it is already initialised" in {
      val authToken = "authToken"
      val appName = "appName"

      stubFor(get(urlMatching("/test-only/token")).willReturn(aResponse().withStatus(OK)))

      stubFor(post(urlMatching("/test-only/token")).willReturn(aResponse().withStatus(CREATED)))

      val app = new GuiceApplicationBuilder()
        .configure(
          "microservice.services.internal-auth.port" -> wireMockPort,
          "appName" -> appName,
          "internal-auth-token-enabled-on-start" -> true,
          "internal-auth.token" -> authToken
        )
        .build()

      app.injector.instanceOf[InternalAuthTokenInitialiser].initialised.futureValue

      verify(1, getRequestedFor(urlMatching("/test-only/token")).withHeader(AUTHORIZATION, equalTo(authToken)))
      verify(0, postRequestedFor(urlMatching("/test-only/token")))
    }
  }

  "when not configured to run" should {
    "should not make the relevant calls to internal-auth" in {
      val authToken = "authToken"
      val appName = "appName"

      stubFor(get(urlMatching("/test-only/token")).willReturn(aResponse().withStatus(OK)))

      stubFor(post(urlMatching("/test-only/token")).willReturn(aResponse().withStatus(CREATED)))

      val app = new GuiceApplicationBuilder()
        .configure(
          "microservice.services.internal-auth.port" -> wireMockPort,
          "appName" -> appName,
          "internal-auth-token-enabled-on-start" -> false,
          "internal-auth.token" -> authToken
        )
        .build()

      app.injector.instanceOf[InternalAuthTokenInitialiser].initialised.futureValue

      verify(0, getRequestedFor(urlMatching("/test-only/token")))
      verify(0, postRequestedFor(urlMatching("/test-only/token")))
    }
  }

}
