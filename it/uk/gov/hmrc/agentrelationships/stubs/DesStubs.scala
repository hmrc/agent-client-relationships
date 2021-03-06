package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}

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

  def givenDesReturnsServerError() =
    stubFor(
      any(urlMatching(s"/registration/.*"))
        .willReturn(aResponse().withStatus(500)))

  def givenDesReturnsServiceUnavailable() =
    stubFor(
      any(urlMatching(s"/registration/.*"))
        .willReturn(aResponse().withStatus(503)))

  def givenAgentCanBeAllocatedInDes(taxIdentifier: TaxIdentifier, arn: Arn) =
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing(taxIdentifier.value))
        .withRequestBody(containing(arn.value))
        .withRequestBody(containing("\"Authorise\""))
        .willReturn(aResponse()
          .withStatus(200)
          .withBody(s"""{"processingDate": "2001-12-17T09:30:47Z"}""")))

  def givenAgentCanNotBeAllocatedInDes(status: Int) =
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing("\"Authorise\""))
        .willReturn(aResponse()
          .withStatus(status)
          .withBody(s"""{"reason": "Service unavailable"}""")))

  def givenAgentCanBeDeallocatedInDes(taxIdentifier: TaxIdentifier, arn: Arn) =
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing(taxIdentifier.value))
        .withRequestBody(containing(arn.value))
        .withRequestBody(containing("\"De-Authorise\""))
        .willReturn(aResponse()
          .withStatus(200)
          .withBody(s"""{"processingDate": "2001-03-14T19:16:07Z"}""")))

  def givenAgentHasNoActiveRelationshipInDes(taxIdentifier: TaxIdentifier, arn: Arn) =
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing(taxIdentifier.value))
        .withRequestBody(containing(arn.value))
        .withRequestBody(containing("\"De-Authorise\""))
        .willReturn(aResponse()
          .withStatus(200)
          .withBody(s"""{"processingDate": "2001-03-14T19:16:07Z"}""")))

  def givenAgentCanNotBeDeallocatedInDes(status: Int) =
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing("\"De-Authorise\""))
        .willReturn(aResponse()
          .withStatus(status)
          .withBody(s"""{"reason": "${failureMessage(status)}"}""")))

  private def failureMessage(status: Int): String = status match  {
    case 404 => "The remote endpoint has indicated that no activeRelationship can be found"
    case 503 => "Dependent systems are currently not responding"
    case o    => s"reason for status code $o"

  }
}
