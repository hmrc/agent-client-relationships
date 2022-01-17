package uk.gov.hmrc.agentclientrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import uk.gov.hmrc.agentmtdidentifiers.model.MtdItId
import uk.gov.hmrc.domain.Nino

trait DesStubs {

  def givenNinoIsKnownFor(mtdbsa: MtdItId, nino: Nino) =
    stubFor(
      get(urlEqualTo(s"/registration/business-details/mtdbsa/${mtdbsa.value}"))
        .willReturn(aResponse().withStatus(200).withBody(s"""{ "nino": "${nino.value}" }""")))

  def givenNinoIsUnknownFor(mtdbsa: MtdItId) =
    stubFor(
      get(urlEqualTo(s"/registration/business-details/mtdbsa/${mtdbsa.value}"))
        .willReturn(aResponse().withStatus(404)))

  def givenMtdbsaIsInvalid(mtdbsa: MtdItId) =
    stubFor(
      get(urlMatching(s"/registration/.*?/mtdbsa/${mtdbsa.value}"))
        .willReturn(aResponse().withStatus(400)))

  def givenMtdItIdIsKnownFor(nino: Nino, mtdbsa: MtdItId) =
    stubFor(
      get(urlEqualTo(s"/registration/business-details/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(200).withBody(s"""{ "mtdbsa": "${mtdbsa.value}" }""")))

  def givenMtdItIdIsUnKnownFor(nino: Nino) =
    stubFor(
      get(urlEqualTo(s"/registration/business-details/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(404)))

  def givenNinoIsInvalid(nino: Nino) =
    stubFor(
      get(urlMatching(s"/registration/.*?/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(400)))

  def givenDesReturnsServiceUnavailable() =
    stubFor(
      any(urlMatching(s"/registration/.*"))
        .willReturn(aResponse().withStatus(503)))

  def givenDesReturnsServerError() =
    stubFor(
      any(urlMatching(s"/registration/.*"))
        .willReturn(aResponse().withStatus(500)))


  val someAlienAgent = """{"hasAgent":false,"agentId":"alien"}"""
  val someCeasedAgent = """{"hasAgent":true,"agentId":"ex-agent","agentCeasedDate":"someDate"}"""

  def givenClientHasRelationshipWithAgentInCESA(nino: Nino, agentId: String) =
    stubFor(
      get(urlEqualTo(s"/registration/relationship/nino/${nino.value}"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"agents":[$someCeasedAgent,{"hasAgent":true,"agentId":"$agentId"}, $someAlienAgent]}""")))

  def givenClientHasRelationshipWithMultipleAgentsInCESA(nino: Nino, agentIds: Seq[String]) =
    stubFor(
      get(urlEqualTo(s"/registration/relationship/nino/${nino.value}"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"agents":[${agentIds
              .map(id => s"""{"hasAgent":true,"agentId":"$id"}""")
              .mkString(",")}, $someAlienAgent, $someCeasedAgent ]}""")))

  def givenClientRelationshipWithAgentCeasedInCESA(nino: Nino, agentId: String) =
    stubFor(
      get(urlEqualTo(s"/registration/relationship/nino/${nino.value}"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"agents":[{"hasAgent":true,"agentId":"$agentId","agentCeasedDate":"2010-01-01"}]}""")))

  def givenAllClientRelationshipsWithAgentsCeasedInCESA(nino: Nino, agentIds: Seq[String]) =
    stubFor(
      get(urlEqualTo(s"/registration/relationship/nino/${nino.value}"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"agents":[${agentIds
              .map(id => s"""{"hasAgent":true,"agentId":"$id","agentCeasedDate":"2020-12-31"}""")
              .mkString(",")}]}""")))

  def givenClientHasNoActiveRelationshipWithAgentInCESA(nino: Nino) =
    stubFor(
      get(urlEqualTo(s"/registration/relationship/nino/${nino.value}"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"agents":[$someCeasedAgent, $someAlienAgent]}""")))

  def givenClientHasNoRelationshipWithAnyAgentInCESA(nino: Nino) =
    stubFor(
      get(urlEqualTo(s"/registration/relationship/nino/${nino.value}"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{}""")))

  def givenClientIsUnknownInCESAFor(nino: Nino) =
    stubFor(
      get(urlEqualTo(s"/registration/relationship/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(404)))
}
