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
      s"/registration/relationship?idtype=VRN&ref-no=$vrn&agent=false&active-only=true&regime=VATC&relationship=ZA01&auth-profile=ALL00001"
    case Utr(utr) =>
      s"/registration/relationship?idtype=UTR&ref-no=$utr&agent=false&active-only=true&regime=TRS"
    case CgtRef(ref) =>
      s"/registration/relationship?idtype=ZCGT&ref-no=$ref&agent=false&active-only=true&regime=CGT&relationship=ZA01&auth-profile=ALL00001"
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

  def getActiveRelationshipFailsWithSuspended(taxIdentifier: TaxIdentifier): StubMapping =
    stubFor(
      get(
        urlEqualTo(url(taxIdentifier)))
        .willReturn(aResponse()
          .withStatus(403).withBody("AGENT_SUSPENDED")))


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

  private def inactiveUrl(arn: Arn) = s"/registration/relationship?arn=${arn.value}" +
      s"&agent=true&active-only=false&regime=AGSV&from=${LocalDate.now().minusDays(30).toString}&to=${LocalDate.now().toString}"

  def getInactiveRelationshipsViaAgent(arn: Arn, otherTaxIdentifier: TaxIdentifier, taxIdentifier: TaxIdentifier): StubMapping = {

    stubFor(get(urlEqualTo(inactiveUrl(arn)))
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

  def getAgentInactiveRelationshipsButActive(encodedArn: String, agentArn: String, clientId: String): StubMapping =
    stubFor(get(urlEqualTo(inactiveUrl(Arn(agentArn))))
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

  def getFailAgentInactiveRelationships(encodedArn: String, status: Int) =
    stubFor(get(urlEqualTo(inactiveUrl(Arn(encodedArn))))
      .willReturn(aResponse()
        .withStatus(status)))

  def getFailWithSuspendedAgentInactiveRelationships(encodedArn: String) =
    stubFor(get(urlEqualTo(inactiveUrl(Arn(encodedArn))))
      .willReturn(aResponse()
        .withStatus(403).withBody("AGENT_SUSPENDED")))

  def getFailWithInvalidAgentInactiveRelationships(encodedArn: String) =
    stubFor(get(urlEqualTo(inactiveUrl(Arn(encodedArn))))
      .willReturn(aResponse()
        .withStatus(503)))

  def getAgentInactiveRelationshipsNoDateTo(arn: Arn, clientId: String): StubMapping =
    stubFor(get(urlEqualTo(inactiveUrl(arn)))
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
