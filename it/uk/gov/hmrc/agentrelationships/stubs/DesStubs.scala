package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import uk.gov.hmrc.agentmtdidentifiers.model.{ Arn, MtdItId }
import uk.gov.hmrc.domain.{ Nino, TaxIdentifier }

trait DesStubs {

  def givenNinoIsKnownFor(mtdbsa: MtdItId, nino: Nino) = {
    stubFor(
      get(urlEqualTo(s"/registration/business-details/mtdbsa/${mtdbsa.value}"))
        .willReturn(aResponse().withStatus(200).withBody(s"""{ "nino": "${nino.value}" }""")))
  }

  def givenNinoIsUnknownFor(mtdbsa: MtdItId) = {
    stubFor(
      get(urlEqualTo(s"/registration/business-details/mtdbsa/${mtdbsa.value}"))
        .willReturn(aResponse().withStatus(404)))
  }

  def givenMtdbsaIsInvalid(mtdbsa: MtdItId) = {
    stubFor(
      get(urlMatching(s"/registration/.*?/mtdbsa/${mtdbsa.value}"))
        .willReturn(aResponse().withStatus(400)))
  }

  def givenMtdItIdIsKnownFor(nino: Nino, mtdbsa: MtdItId) = {
    stubFor(
      get(urlEqualTo(s"/registration/business-details/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(200).withBody(s"""{ "mtdbsa": "${mtdbsa.value}" }""")))
  }

  def givenMtdItIdIsUnKnownFor(nino: Nino) = {
    stubFor(
      get(urlEqualTo(s"/registration/business-details/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(404)))
  }

  def givenNinoIsInvalid(nino: Nino) = {
    stubFor(
      get(urlMatching(s"/registration/.*?/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(400)))
  }

  val someAlienAgent = """{"hasAgent":false,"agentId":"alien"}"""
  val someCeasedAgent = """{"hasAgent":true,"agentId":"ex-agent","agentCeasedDate":"someDate"}"""

  def givenClientHasRelationshipWithAgentInCESA(nino: Nino, agentId: String) = {
    stubFor(
      get(urlEqualTo(s"/registration/relationship/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(200)
          .withBody(s"""{"agents":[$someCeasedAgent,{"hasAgent":true,"agentId":"$agentId"}, $someAlienAgent]}""")))
  }

  def givenClientHasRelationshipWithMultipleAgentsInCESA(nino: Nino, agentIds: Seq[String]) = {
    stubFor(
      get(urlEqualTo(s"/registration/relationship/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(200)
          .withBody(s"""{"agents":[${agentIds.map(id => s"""{"hasAgent":true,"agentId":"$id"}""").mkString(",")}, $someAlienAgent, $someCeasedAgent ]}""")))
  }

  def givenClientRelationshipWithAgentCeasedInCESA(nino: Nino, agentId: String) = {
    stubFor(
      get(urlEqualTo(s"/registration/relationship/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(200)
          .withBody(s"""{"agents":[{"hasAgent":true,"agentId":"$agentId","agentCeasedDate":"2010-01-01"}]}""")))
  }

  def givenAllClientRelationshipsWithAgentsCeasedInCESA(nino: Nino, agentIds: Seq[String]) = {
    stubFor(
      get(urlEqualTo(s"/registration/relationship/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(200)
          .withBody(s"""{"agents":[${agentIds.map(id => s"""{"hasAgent":true,"agentId":"$id","agentCeasedDate":"2020-12-31"}""").mkString(",")}]}""")))
  }

  def givenClientHasNoActiveRelationshipWithAgentInCESA(nino: Nino) = {
    stubFor(
      get(urlEqualTo(s"/registration/relationship/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(200)
          .withBody(s"""{"agents":[$someCeasedAgent, $someAlienAgent]}""")))
  }

  def givenClientHasNoRelationshipWithAnyAgentInCESA(nino: Nino) = {
    stubFor(
      get(urlEqualTo(s"/registration/relationship/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(200)
          .withBody(s"""{}""")))
  }

  def givenClientIsUnknownInCESAFor(nino: Nino) = {
    stubFor(
      get(urlEqualTo(s"/registration/relationship/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(404)))
  }

  def givenDesReturnsServerError() = {
    stubFor(
      get(urlMatching(s"/registration/.*"))
        .willReturn(aResponse().withStatus(500)))
  }

  def givenDesReturnsServiceUnavailable() = {
    stubFor(
      get(urlMatching(s"/registration/.*"))
        .willReturn(aResponse().withStatus(503)))
  }

  def givenAgentCanBeAllocatedInDes(taxIdentifier: TaxIdentifier, arn: Arn) = {
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing(taxIdentifier.value))
        .withRequestBody(containing(arn.value))
        .withRequestBody(containing("\"Authorise\""))
        .willReturn(aResponse().withStatus(200)
          .withBody(s"""{"processingDate": "2001-12-17T09:30:47Z"}""")))
  }

  def givenAgentCanNotBeAllocatedInDes = {
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing("\"Authorise\""))
        .willReturn(aResponse().withStatus(404)
          .withBody(s"""{"reason": "Service unavailable"}""")))
  }

  def givenAgentCanBeDeallocatedInDes(taxIdentifier: TaxIdentifier, arn: Arn) = {
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing(taxIdentifier.value))
        .withRequestBody(containing(arn.value))
        .withRequestBody(containing("\"De-Authorise\""))
        .willReturn(aResponse().withStatus(200)
          .withBody(s"""{"processingDate": "2001-03-14T19:16:07Z"}""")))
  }

  def givenAgentHasNoActiveRelationshipInDes(taxIdentifier: TaxIdentifier, arn: Arn) = {
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing(taxIdentifier.value))
        .withRequestBody(containing(arn.value))
        .withRequestBody(containing("\"De-Authorise\""))
        .willReturn(aResponse().withStatus(200)
          .withBody(s"""{"processingDate": "2001-03-14T19:16:07Z"}""")))
  }

  def givenAgentCanNotBeDeallocatedInDes = {
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing("\"De-Authorise\""))
        .willReturn(aResponse().withStatus(404)
          .withBody(s"""{"reason": "Service unavailable"}""")))
  }

  def getClientActiveAgentRelationships(encodedClientId: String, service: String, agentArn: String): Unit = {
    stubFor(get(urlEqualTo(s"/registration/relationship?ref-no=$encodedClientId&agent=false&active-only=true&regime=$service"))
      .willReturn(
        aResponse()
          .withStatus(200)
          .withBody(
            s"""
               |{
               |"relationship" :[
               |{
               |  "referenceNumber" : "ABCDE1234567890",
               |  "agentReferenceNumber" : "$agentArn",
               |  "organisation" : {
               |    "organisationName": "someOrganisationName"
               |  },
               |  "dateFrom" : "2015-09-10",
               |  "dateTo" : "9999-12-31",
               |  "contractAccountCategory" : "01",
               |  "activity" : "09"
               |}
               |]
               |}""".stripMargin)))
  }

  def getClientActiveButEndedAgentRelationships(encodedClientId: String, service: String, agentArn: String): Unit = {
    stubFor(get(urlEqualTo(s"/registration/relationship?ref-no=$encodedClientId&agent=false&active-only=true&regime=$service"))
      .willReturn(
        aResponse()
          .withStatus(200)
          .withBody(
            s"""
               |{
               |"relationship" :[
               |{
               |  "referenceNumber" : "ABCDE1234567890",
               |  "agentReferenceNumber" : "$agentArn",
               |  "organisation" : {
               |    "organisationName": "someOrganisationName"
               |  },
               |  "dateFrom" : "2015-09-10",
               |  "dateTo" : "2016-12-31",
               |  "contractAccountCategory" : "01",
               |  "activity" : "09"
               |}
               |]
               |}""".stripMargin)))
  }

  def getClientActiveButSomeEndedAgentRelationships(encodedClientId: String, service: String, agentArn1: String, agentArn2: String, agentArn3: String): Unit = {
    stubFor(get(urlEqualTo(s"/registration/relationship?ref-no=$encodedClientId&agent=false&active-only=true&regime=$service"))
      .willReturn(
        aResponse()
          .withStatus(200)
          .withBody(
            s"""
               |{
               |"relationship" :[
               |{
               |  "referenceNumber" : "ABCDE1234567890",
               |  "agentReferenceNumber" : "$agentArn1",
               |  "organisation" : {
               |    "organisationName": "someOrganisationName"
               |  },
               |  "dateFrom" : "2015-09-10",
               |  "dateTo" : "2015-12-31",
               |  "contractAccountCategory" : "01",
               |  "activity" : "10"
               |},
               |{
               |  "referenceNumber" : "ABCDE1234567890",
               |  "agentReferenceNumber" : "$agentArn2",
               |  "organisation" : {
               |    "organisationName": "sayOrganisationName"
               |  },
               |  "dateFrom" : "2015-09-10",
               |  "dateTo" : "2016-12-31",
               |  "contractAccountCategory" : "02",
               |  "activity" : "09"
               |},
               |{
               |  "referenceNumber" : "ABCDE1234567890",
               |  "agentReferenceNumber" : "$agentArn3",
               |  "organisation" : {
               |    "organisationName": "noneOrganisationName"
               |  },
               |  "dateFrom" : "2014-09-10",
               |  "dateTo" : "9999-12-31",
               |  "contractAccountCategory" : "03",
               |  "activity" : "11"
               |}
               |]
               |}""".stripMargin)))
  }

  def getNotFoundClientActiveAgentRelationships(encodedClientId: String, service: String): Unit = {
    stubFor(get(urlEqualTo(s"/registration/relationship?ref-no=$encodedClientId&agent=false&active-only=true&regime=$service"))
      .willReturn(
        aResponse()
          .withStatus(404)))
  }
}
