package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import org.joda.time.LocalDate
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, CgtRef, MtdItId, Utr, Vrn}
import uk.gov.hmrc.agentrelationships.support.WireMockSupport
import uk.gov.hmrc.domain.TaxIdentifier

trait DesStubsGet {

  me: WireMockSupport =>

  // Via Client

  val url: TaxIdentifier => String = {
    case MtdItId(mtdItId) =>
      s"/registration/relationship?ref-no=$mtdItId&agent=false&active-only=true&regime=ITSA"
    case Vrn(vrn) =>
      s"/registration/relationship?idtype=VRN&ref-no=$vrn&agent=false&active-only=true&regime=VATC"
    case Utr(utr) =>
      s"/registration/relationship?idtype=UTR&ref-no=$utr&agent=false&active-only=true&regime=TRS"
    case CgtRef(ref) =>
      s"/registration/relationship?idtype=ZCGT&ref-no=$ref&agent=false&active-only=true&regime=CGT"
  }

  def getActiveRelationshipsViaClient(taxIdentifier: TaxIdentifier, arn: Arn) = {
    stubFor(get(urlEqualTo(url(taxIdentifier)))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             |{
             |"relationship" :[
             |{
             |  "referenceNumber" : "${taxIdentifier.value}",
             |  "agentReferenceNumber" : "${arn.value}",
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

  def getInactiveRelationshipViaClient(taxIdentifier: TaxIdentifier,
                                       agentArn: String): StubMapping =
    stubFor(
      get(
        urlEqualTo(url(taxIdentifier)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{
                         |"relationship" :[
                         |{
                         |  "referenceNumber" : "${taxIdentifier.value}",
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

  def getSomeActiveRelationshipsViaClient(taxIdentifier: TaxIdentifier,
                                          agentArn1: String,
                                          agentArn2: String,
                                          agentArn3: String): StubMapping =
    stubFor(
      get(
        urlEqualTo(url(taxIdentifier)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{
                         |"relationship" :[
                         |{
                         |  "referenceNumber" : "$taxIdentifier",
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
                         |  "referenceNumber" : "$taxIdentifier",
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
                         |  "referenceNumber" : "$taxIdentifier",
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

  def getActiveRelationshipFailsWith(taxIdentifier: TaxIdentifier,
                                     status: Int): StubMapping =
    stubFor(
      get(
        urlEqualTo(url(taxIdentifier)))
        .willReturn(aResponse()
          .withStatus(status)))


  //VIA Agent

  val otherInactiveRelationship: TaxIdentifier => Arn => String = {
    case MtdItId(clientId) => arn =>
      s"""
         |{
         |  "referenceNumber" : "$clientId",
         |  "agentReferenceNumber" : "${arn.value}",
         |  "organisation" : {
         |    "organisationName": "someOrganisationName"
         |  },
         |  "dateFrom" : "2015-09-10",
         |  "dateTo" : "2015-09-21",
         |  "contractAccountCategory" : "01",
         |  "activity" : "09"
         |}
       """.stripMargin
    case Vrn(clientId) => arn =>
      s"""
         |{
         |  "referenceNumber" : "$clientId",
         |  "agentReferenceNumber" : "${arn.value}",
         |  "organisation" : {
         |    "organisationName": "someOrganisationName"
         |  },
         |  "dateFrom" : "2015-09-10",
         |  "dateTo" : "2015-09-21",
         |  "contractAccountCategory" : "01",
         |  "activity" : "09"
         |}
       """.stripMargin
    case Utr(clientId) => arn =>
      s"""
         |{
         |  "referenceNumber" : "$clientId",
         |  "agentReferenceNumber" : "${arn.value}",
         |  "organisation" : {
         |    "organisationName": "someOrganisationName"
         |  },
         |  "dateFrom" : "2015-09-10",
         |  "dateTo" : "2015-09-21",
         |  "contractAccountCategory" : "01",
         |  "activity" : "09"
         |}
       """.stripMargin
    case CgtRef(clientId) => arn =>
      s"""
         |{
         |  "referenceNumber" : "$clientId",
         |  "agentReferenceNumber" : "${arn.value}",
         |  "organisation" : {
         |    "organisationName": "someOrganisationName"
         |  },
         |  "dateFrom" : "2015-09-10",
         |  "dateTo" : "2015-09-21",
         |  "contractAccountCategory" : "01",
         |  "activity" : "09"
         |}
       """.stripMargin
  }

  def getInactiveRelationshipsViaAgent(arn: Arn, otherTaxIdentifier: TaxIdentifier, taxIdentifier: TaxIdentifier,  regime: String): StubMapping = {

    stubFor(get(urlEqualTo(
      s"/registration/relationship?arn=${arn.value}" +
      s"&agent=true&active-only=false&regime=$regime&from=${LocalDate.now().minusDays(30).toString}" +
      s"&to=${LocalDate.now().toString}"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(s"""
                     |{
                     |"relationship" :[
                     |${otherInactiveRelationship(otherTaxIdentifier)(arn)},
                     |{
                     |  "referenceNumber" : "${taxIdentifier.value}",
                     |  "agentReferenceNumber" : "${arn.value}",
                     |  "organisation" : {
                     |    "organisationName": "someOrganisationName"
                     |  },
                     |  "dateFrom" : "2015-09-10",
                     |  "dateTo" : "${LocalDate.now().toString}",
                     |  "contractAccountCategory" : "01",
                     |  "activity" : "09"
                     |}
                     |]
                     |}""".stripMargin)))
  }

  def getAgentInactiveRelationshipsButActive(encodedArn: String, agentArn: String, clientId: String, service: String): StubMapping =
    stubFor(get(urlEqualTo(
      s"/registration/relationship?arn=$encodedArn" +
      s"&agent=true&active-only=false&regime=$service&from=${LocalDate.now().minusDays(30).toString}" +
      s"&to=${LocalDate.now().toString}"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(s"""
                     |{
                     |"relationship" :[
                     |{
                     |  "referenceNumber" : "$clientId",
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

  def getFailAgentInactiveRelationships(encodedArn: String, service: String, status: Int) =
    stubFor(get(urlEqualTo(
      s"/registration/relationship?arn=$encodedArn" +
      s"&agent=true&active-only=false&regime=$service&from=${LocalDate.now().minusDays(30)}" +
      s"&to=${LocalDate.now().toString}"))
      .willReturn(aResponse()
        .withStatus(status)))

  def getAgentInactiveRelationshipsNoDateTo(arn: Arn, clientId: String, regime: String): StubMapping =
    stubFor(get(urlEqualTo(
      s"/registration/relationship?arn=${arn.value}" +
      s"&agent=true&active-only=false&regime=$regime&from=${LocalDate.now().minusDays(30)}" +
      s"&to=${LocalDate.now().toString}"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(s"""
                     |{
                     |"relationship" :[
                     |{
                     |  "referenceNumber" : "${clientId}",
                     |  "agentReferenceNumber" : "${arn.value}",
                     |  "organisation" : {
                     |    "organisationName": "someOrganisationName"
                     |  },
                     |  "dateFrom" : "2015-09-10",
                     |  "contractAccountCategory" : "01",
                     |  "activity" : "09"
                     |}
                     |]
                     |}""".stripMargin)))
}
