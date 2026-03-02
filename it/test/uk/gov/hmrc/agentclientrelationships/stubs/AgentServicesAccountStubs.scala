/*
 * Copyright 2026 HM Revenue & Customs
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

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.verify
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import org.scalatest.concurrent.Eventually.eventually
import play.api.libs.json.Json
import play.api.test.Helpers.AUTHORIZATION
import play.api.test.Helpers.USER_AGENT
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.support.TestData

trait AgentServicesAccountStubs
extends TestData {

  def givenAgentRecord(
    arn: Arn,
    agentRecord: TestAgentDetailsDesResponse
  ): StubMapping = stubFor(
    get(urlEqualTo(s"/agent-services-account/agent-record-with-checks/arn/${arn.value}"))
      .withHeader(AUTHORIZATION, equalTo("internalAuthToken"))
      .withHeader(USER_AGENT, equalTo("agent-client-relationships"))
      .willReturn(aResponse().withStatus(200).withBody(Json.toJson(agentRecord).toString))
  )

  def verifyAgentRecordSent(
    arn: Arn,
    count: Int = 1
  ): Unit = eventually {
    verify(count, getRequestedFor(urlPathEqualTo(s"/agent-services-account/agent-record-with-checks/arn/${arn.value}")))
  }

  def givenAgentRecordErrorResponse(
    arn: Arn,
    status: Int
  ): StubMapping = stubFor(
    get(urlEqualTo(s"/agent-services-account/agent-record-with-checks/arn/${arn.value}"))
      .withHeader(AUTHORIZATION, equalTo("internalAuthToken"))
      .withHeader(USER_AGENT, equalTo("agent-client-relationships"))
      .willReturn(aResponse().withStatus(status).withBody("Unexpected error retrieving agent record"))
  )

}
