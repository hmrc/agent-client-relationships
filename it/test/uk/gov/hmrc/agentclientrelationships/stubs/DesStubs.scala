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
import uk.gov.hmrc.domain.Nino

trait DesStubs {

  def givenDesReturnsServiceUnavailable() = stubFor(
    any(urlMatching(s"/registration/.*")).willReturn(aResponse().withStatus(503))
  )

  def givenDesReturnsServerError() = stubFor(
    any(urlMatching(s"/registration/.*")).willReturn(aResponse().withStatus(500))
  )

  val someAlienAgent = """{"hasAgent":false,"agentId":"alien"}"""
  val someCeasedAgent = """{"hasAgent":true,"agentId":"ex-agent","agentCeasedDate":"someDate"}"""

  def givenClientHasRelationshipWithAgentInCESA(nino: Nino, agentId: String) = stubFor(
    get(urlEqualTo(s"/registration/relationship/nino/${nino.value}")).willReturn(
      aResponse()
        .withStatus(200)
        .withBody(s"""{"agents":[$someCeasedAgent,{"hasAgent":true,"agentId":"$agentId"}, $someAlienAgent]}""")
    )
  )

  def givenClientHasRelationshipWithMultipleAgentsInCESA(nino: Nino, agentIds: Seq[String]) = stubFor(
    get(urlEqualTo(s"/registration/relationship/nino/${nino.value}")).willReturn(
      aResponse()
        .withStatus(200)
        .withBody(s"""{"agents":[${agentIds
          .map(id => s"""{"hasAgent":true,"agentId":"$id"}""")
          .mkString(",")}, $someAlienAgent, $someCeasedAgent ]}""")
    )
  )

  def givenClientRelationshipWithAgentCeasedInCESA(nino: Nino, agentId: String) = stubFor(
    get(urlEqualTo(s"/registration/relationship/nino/${nino.value}")).willReturn(
      aResponse()
        .withStatus(200)
        .withBody(s"""{"agents":[{"hasAgent":true,"agentId":"$agentId","agentCeasedDate":"2010-01-01"}]}""")
    )
  )

  def givenAllClientRelationshipsWithAgentsCeasedInCESA(nino: Nino, agentIds: Seq[String]) = stubFor(
    get(urlEqualTo(s"/registration/relationship/nino/${nino.value}")).willReturn(
      aResponse()
        .withStatus(200)
        .withBody(s"""{"agents":[${agentIds
          .map(id => s"""{"hasAgent":true,"agentId":"$id","agentCeasedDate":"2020-12-31"}""")
          .mkString(",")}]}""")
    )
  )

  def givenClientHasNoActiveRelationshipWithAgentInCESA(nino: Nino) = stubFor(
    get(urlEqualTo(s"/registration/relationship/nino/${nino.value}"))
      .willReturn(aResponse().withStatus(200).withBody(s"""{"agents":[$someCeasedAgent, $someAlienAgent]}"""))
  )

  def givenClientHasNoRelationshipWithAnyAgentInCESA(nino: Nino) = stubFor(
    get(urlEqualTo(s"/registration/relationship/nino/${nino.value}"))
      .willReturn(aResponse().withStatus(200).withBody(s"""{}"""))
  )

  def givenClientIsUnknownInCESAFor(nino: Nino) = stubFor(
    get(urlEqualTo(s"/registration/relationship/nino/${nino.value}")).willReturn(aResponse().withStatus(404))
  )
}
