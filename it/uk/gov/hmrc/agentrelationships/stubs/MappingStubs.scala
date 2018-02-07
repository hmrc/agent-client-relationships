package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Vrn}
import uk.gov.hmrc.domain.SaAgentReference

trait MappingStubs {

  def givenArnIsKnownFor(arn: Arn, saAgentReference: SaAgentReference) = {
    stubFor(
      get(urlEqualTo(s"/agent-mapping/mappings/${arn.value}"))
        .willReturn(aResponse()
          .withStatus(200)
          .withBody(s"""{"mappings":[{"arn":"${arn.value}","saAgentReference":"${saAgentReference.value}"}]}"""))
    )
  }

  def givenArnIsKnownFor(arn: Arn, refs: Seq[SaAgentReference]) = {
    stubFor(
      get(urlEqualTo(s"/agent-mapping/mappings/${arn.value}"))
        .willReturn(aResponse()
          .withStatus(200)
          .withBody(s"""{"mappings":[${refs.map(ref => s"""{"arn":"${arn.value}","saAgentReference":"${ref.value}"}""").mkString(",")}]}"""))
    )
  }

  def givenArnIsKnownFor(arn: Arn, vrn: Vrn) = {
    stubFor(
      get(urlEqualTo(s"/agent-mapping/mappings/vat/${arn.value}"))
        .willReturn(aResponse()
          .withStatus(200)
          .withBody(s"""{"mappings":[{"arn":"${arn.value}","vrn":"${vrn.value}"}]}"""))
    )
  }

  def givenArnIsKnownForVrns(arn: Arn, vrns: Seq[Vrn]) = {
    stubFor(
      get(urlEqualTo(s"/agent-mapping/mappings/vat/${arn.value}"))
        .willReturn(aResponse()
          .withStatus(200)
          .withBody(s"""{"mappings":[${vrns.map(vrn => s"""{"arn":"${arn.value}","vrn":"${vrn.value}"}""").mkString(",")}]}"""))
    )
  }

  def givenArnIsUnknownFor(arn: Arn) = {
    stubFor(
      get(urlEqualTo(s"/agent-mapping/mappings/${arn.value}"))
        .willReturn(aResponse()
          .withStatus(404))
    )
  }

  def givenServiceReturnsServerError() = {
    stubFor(
      get(urlMatching(s"/agent-mapping/.*"))
        .willReturn(aResponse().withStatus(500))
    )
  }

  def givenServiceReturnsServiceUnavailable() = {
    stubFor(
      get(urlMatching(s"/agent-mapping/.*"))
        .willReturn(aResponse().withStatus(503))
    )
  }

}
