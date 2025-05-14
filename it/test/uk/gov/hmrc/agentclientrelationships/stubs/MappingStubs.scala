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
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.domain.SaAgentReference

trait MappingStubs {

  def givenArnIsKnownFor(
    arn: Arn,
    saAgentReference: SaAgentReference
  ) = stubFor(
    get(urlEqualTo(s"/agent-mapping/mappings/${arn.value}")).willReturn(
      aResponse()
        .withStatus(200)
        .withBody(s"""{"mappings":[{"arn":"${arn.value}","saAgentReference":"${saAgentReference.value}"}]}""")
    )
  )

  def givenArnIsKnownFor(
    arn: Arn,
    refs: Seq[SaAgentReference]
  ) = stubFor(
    get(urlEqualTo(s"/agent-mapping/mappings/${arn.value}")).willReturn(
      aResponse()
        .withStatus(200)
        .withBody(s"""{"mappings":[${refs
            .map(ref => s"""{"arn":"${arn.value}","saAgentReference":"${ref.value}"}""")
            .mkString(",")}]}""")
    )
  )

  def givenArnIsKnownFor(
    arn: Arn,
    agentCode: AgentCode
  ) = stubFor(
    get(urlEqualTo(s"/agent-mapping/mappings/agentcode/${arn.value}")).willReturn(
      aResponse()
        .withStatus(200)
        .withBody(s"""{"mappings":[{"arn":"${arn.value}","agentCode":"${agentCode.value}"}]}""")
    )
  )

  def givenArnIsKnownForAgentCodes(
    arn: Arn,
    agentCodes: Seq[AgentCode]
  ) = stubFor(
    get(urlEqualTo(s"/agent-mapping/mappings/agentcode/${arn.value}")).willReturn(
      aResponse()
        .withStatus(200)
        .withBody(s"""{"mappings":[${agentCodes
            .map(agentCode => s"""{"arn":"${arn.value}","agentCode":"${agentCode.value}"}""")
            .mkString(",")}]}""")
    )
  )

  def givenArnIsUnknownFor(arn: Arn) = stubFor(
    get(urlEqualTo(s"/agent-mapping/mappings/${arn.value}")).willReturn(aResponse().withStatus(404))
  )

  def givenServiceReturnsServerError() = stubFor(
    get(urlMatching(s"/agent-mapping/.*")).willReturn(aResponse().withStatus(500))
  )

  def givenServiceReturnsServiceUnavailable() = stubFor(
    get(urlMatching(s"/agent-mapping/.*")).willReturn(aResponse().withStatus(503))
  )

}
