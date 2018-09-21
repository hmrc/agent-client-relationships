package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import org.joda.time.LocalDate

trait DesStubsGet {

  def getClientActiveAgentRelationshipsItSa(encodedClientId: String, agentArn: String, service: String = "ITSA"): Unit =
    stubFor(
      get(
        urlEqualTo(s"/registration/relationship?ref-no=$encodedClientId&agent=false&active-only=true&regime=$service"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
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

  def getClientActiveAgentRelationshipsVat(encodedClientId: String, agentArn: String, service: String = "VATC"): Unit =
    stubFor(
      get(urlEqualTo(
        s"/registration/relationship?idtype=VRN&ref-no=$encodedClientId&agent=false&active-only=true&regime=$service"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
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

  def getAgentInactiveRelationships(encodedArn: String, agentArn: String, service: String = "ITSA"): Unit =
    stubFor(get(urlEqualTo(
      s"/registration/relationship?arn=$encodedArn&agent=true&active-only=false&regime=$service&from=1970-01-01&to=${LocalDate.now().toString}"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(s"""
                     |{
                     |"relationship" :[
                     |{
                     |  "referenceNumber" : "ABCDE1234567890",
                     |  "agentReferenceNumber" : "$agentArn",
                     |  "organisation" : {
                     |    "organisationName": "someOrganisationName"
                     |  },
                     |  "dateFrom" : "2015-09-10",
                     |  "dateTo" : "2015-09-21",
                     |  "contractAccountCategory" : "01",
                     |  "activity" : "09"
                     |}
                     |]
                     |}""".stripMargin)))

  def getAgentInactiveRelationshipsButActive(encodedArn: String, agentArn: String, service: String = "ITSA"): Unit =
    stubFor(get(urlEqualTo(
      s"/registration/relationship?arn=$encodedArn&agent=true&active-only=false&regime=$service&from=1970-01-01&to=${LocalDate.now().toString}"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(s"""
                     |{
                     |"relationship" :[
                     |{
                     |  "referenceNumber" : "ABCDE1234567890",
                     |  "agentReferenceNumber" : "$agentArn",
                     |  "organisation" : {
                     |    "organisationName": "someOrganisationName"
                     |  },
                     |  "dateFrom" : "2015-09-10",
                     |  "dateTo" : "9999-09-21",
                     |  "contractAccountCategory" : "01",
                     |  "activity" : "09"
                     |}
                     |]
                     |}""".stripMargin)))

  def getClientActiveButEndedAgentRelationshipsItSa(
    encodedClientId: String,
    agentArn: String,
    service: String = "ITSA"): Unit =
    stubFor(
      get(
        urlEqualTo(s"/registration/relationship?ref-no=$encodedClientId&agent=false&active-only=true&regime=$service"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
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

  def getClientActiveButEndedAgentRelationshipsVat(
    encodedClientId: String,
    agentArn: String,
    service: String = "VATC"): Unit =
    stubFor(
      get(urlEqualTo(
        s"/registration/relationship?idtype=VRN&ref-no=$encodedClientId&agent=false&active-only=true&regime=$service"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
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

  def getClientActiveButSomeEndedAgentRelationshipsItSa(
    encodedClientId: String,
    agentArn1: String,
    agentArn2: String,
    agentArn3: String,
    service: String = "ITSA"): Unit =
    stubFor(
      get(
        urlEqualTo(s"/registration/relationship?ref-no=$encodedClientId&agent=false&active-only=true&regime=$service"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
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

  def getClientActiveButSomeEndedAgentRelationshipsVat(
    encodedClientId: String,
    agentArn1: String,
    agentArn2: String,
    agentArn3: String,
    service: String = "VATC"): Unit =
    stubFor(
      get(urlEqualTo(
        s"/registration/relationship?idtype=VRN&ref-no=$encodedClientId&agent=false&active-only=true&regime=$service"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
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

  def getFailFoundClientActiveAgentRelationshipsItSa(
    encodedClientId: String,
    service: String = "ITSA",
    status: Int): Unit =
    stubFor(
      get(
        urlEqualTo(s"/registration/relationship?ref-no=$encodedClientId&agent=false&active-only=true&regime=$service"))
        .willReturn(aResponse()
          .withStatus(status)))

  def getFailClientActiveAgentRelationshipsVat(encodedClientId: String, service: String = "VATC", status: Int): Unit =
    stubFor(
      get(urlEqualTo(
        s"/registration/relationship?idtype=VRN&ref-no=$encodedClientId&agent=false&active-only=true&regime=$service"))
        .willReturn(aResponse()
          .withStatus(status)))

  def getFailAgentInactiveRelationships(encodedArn: String, service: String, status: Int) =
    stubFor(get(urlEqualTo(
      s"/registration/relationship?arn=$encodedArn&agent=true&active-only=false&regime=$service&from=1970-01-01&to=${LocalDate.now().toString}"))
      .willReturn(aResponse()
        .withStatus(status)))
}
