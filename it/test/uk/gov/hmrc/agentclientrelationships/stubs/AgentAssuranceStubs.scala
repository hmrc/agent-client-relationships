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

package uk.gov.hmrc.agentclientrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import org.scalatest.concurrent.Eventually.eventually
import play.api.libs.json.Json
import play.api.test.Helpers.{AUTHORIZATION, USER_AGENT}
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentmtdidentifiers.model.Arn

trait AgentAssuranceStubs extends TestData {

  def givenAgentRecordFound(arn: Arn, agentRecord: TestAgentDetailsDesResponse): StubMapping =
    stubFor(
      get(urlEqualTo(s"/agent-assurance/agent-record-with-checks/arn/${arn.value}"))
        .withHeader(AUTHORIZATION, equalTo("internalAuthToken"))
        .withHeader(USER_AGENT, equalTo("agent-client-relationships"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(Json.toJson(agentRecord).toString)
        )
    )

  def verifyAgentRecordFoundSent(arn: Arn, count: Int = 1) =
    eventually {
      verify(
        count,
        getRequestedFor(urlPathEqualTo(s"/agent-assurance/agent-record-with-checks/arn/${arn.value}"))
      )
    }

  def givenAgentDetailsErrorResponse(arn: Arn, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(s"/agent-assurance/agent-record-with-checks/arn/${arn.value}"))
        .withHeader(AUTHORIZATION, equalTo("internalAuthToken"))
        .withHeader(USER_AGENT, equalTo("agent-client-relationships"))
        .willReturn(
          aResponse()
            .withStatus(status)
        )
    )

  private def similarToJson(value: String) = equalToJson(value.stripMargin, true, true)
}
