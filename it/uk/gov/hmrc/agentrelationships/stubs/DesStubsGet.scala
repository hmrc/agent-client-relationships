package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import org.joda.time.LocalDate
import uk.gov.hmrc.agentmtdidentifiers.model._
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
    case Urn(urn) =>
      s"/registration/relationship?idtype=URN&referenceNumber=$urn&agent=false&active-only=true&regime=TRS"
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
         |  "individual" : {
         |    "firstName": "someName"
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
    case Urn(clientId) => arn =>
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

  private val inactiveUrlClient: TaxIdentifier => String = {
    case MtdItId(mtdItId) =>
      s"/registration/relationship?ref-no=$mtdItId&agent=false&active-only=false&regime=ITSA&from=2015-01-01&to=${LocalDate.now().toString}"
    case Vrn(vrn) =>
      s"/registration/relationship?idtype=VRN&ref-no=$vrn&agent=false&active-only=false&regime=VATC&from=2015-01-01&to=${LocalDate.now().toString}&relationship=ZA01&auth-profile=ALL00001"
    case Utr(utr) =>
      s"/registration/relationship?idtype=UTR&ref-no=$utr&agent=false&active-only=false&regime=TRS&from=2015-01-01&to=${LocalDate.now().toString}"
    case Urn(urn) =>
      s"/registration/relationship?idtype=URN&referenceNumber=$urn&agent=false&active-only=false&regime=TRS&from=2015-01-01&to=${LocalDate.now().toString}"
    case CgtRef(ref) =>
      s"/registration/relationship?idtype=ZCGT&ref-no=$ref&agent=false&active-only=false&regime=CGT&from=2015-01-01&to=${LocalDate.now().toString}&relationship=ZA01&auth-profile=ALL00001"
  }

  private val agentRecordUrl: TaxIdentifier => String = {
    case Arn(arn) => s"/registration/personal-details/arn/$arn"
    case Utr(utr) => s"/registration/personal-details/utr/$utr"
  }

  def getAgentRecordForClient(taxIdentifier: TaxIdentifier): StubMapping = {
    stubFor(get(urlEqualTo(agentRecordUrl(taxIdentifier)))
      .willReturn(aResponse()
      .withStatus(200)
        .withBody(
          s"""
             | {
             |  "suspensionDetails" : {
             |    "suspensionStatus": false,
             |    "regimes": []
             |  }
             | }
             |""".stripMargin)
      )
    )
  }

  def getSuspendedAgentRecordForClient(taxIdentifier: TaxIdentifier): StubMapping = {
    stubFor(get(urlEqualTo(agentRecordUrl(taxIdentifier)))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             | {
             |  "suspensionDetails" : {
             |    "suspensionStatus": true,
             |    "regimes": ["ITSA"]
             |  }
             | }
             |""".stripMargin)
      )
    )
  }

  def getInactiveRelationshipsForClient(taxIdentifier: TaxIdentifier): StubMapping = {

    val individualOrOrganisationJson = if(taxIdentifier.isInstanceOf[MtdItId])
      s"""
         | "individual" : {
         |  "firstName" : "someName"
         |  },
         |""".stripMargin
    else
      s"""
         | "organisation" : {
         | "organisationName" : "someOrgName"
         | },
         |""".stripMargin

    stubFor(get(urlEqualTo(inactiveUrlClient(taxIdentifier)))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(s"""
                     |{
                     |"relationship" :[
                     |{
                     |  "referenceNumber" : "${taxIdentifier.value}",
                     |  "agentReferenceNumber" : "ABCDE123456", """.stripMargin + individualOrOrganisationJson + s"""
                     |  "dateFrom" : "2015-09-10",
                     |  "dateTo" : "2018-09-09",
                     |  "contractAccountCategory" : "01",
                     |  "activity" : "09"
                     |},
                     |{
                     | "referenceNumber" : "${taxIdentifier.value}",
                     |  "agentReferenceNumber" : "ABCDE777777", """.stripMargin + individualOrOrganisationJson + s"""
                     |  "dateFrom" : "2019-09-09",
                     |  "dateTo" : "2050-09-09",
                     |  "contractAccountCategory" : "01",
                     |  "activity" : "09"
                     |  }
                     |]
                     |}""".stripMargin)))
  }

  def getNoInactiveRelationshipsForClient(taxIdentifier: TaxIdentifier): StubMapping = {

    stubFor(get(urlEqualTo(inactiveUrlClient(taxIdentifier)))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(s"""
                     |{
                     |"relationship" :[
                     |{
                     |  "referenceNumber" : "${taxIdentifier.value}",
                     |  "agentReferenceNumber" : "ABCDE123456",
                     |  "individual" : {
                     |    "firstName": "someName"
                     |  },
                     |  "dateFrom" : "2015-09-10",
                     |  "dateTo" : "2050-09-09",
                     |  "contractAccountCategory" : "01",
                     |  "activity" : "09"
                     |}
                     |]
                     |}""".stripMargin)))
  }

  def getFailInactiveRelationshipsForClient(taxIdentifier: TaxIdentifier, status: Int) =
    stubFor(get(urlEqualTo(inactiveUrlClient(taxIdentifier)))
      .willReturn(aResponse().withStatus(status))
    )

  def getInactiveRelationshipsViaAgent(arn: Arn, otherTaxIdentifier: TaxIdentifier, taxIdentifier: TaxIdentifier): StubMapping = {

    val individualOrOrganisation: TaxIdentifier => String = {
    case MtdItId(_) =>
      s""" "individual" : {
         | "firstName" : "someName"
         |  },""".stripMargin
    case _ =>
      s""" "organisation" : {
         |  "organisationName" : "someOrganisationName"
         |    },""".stripMargin
    }

    stubFor(get(urlEqualTo(inactiveUrl(arn)))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(s"""
                     |{
                     |"relationship" :[
                     |${otherInactiveRelationship(otherTaxIdentifier)(arn)},
                     |{
                     |  "referenceNumber" : "${taxIdentifier.value}",
                     |  "agentReferenceNumber" : "${arn.value}",""".stripMargin +
          individualOrOrganisation(taxIdentifier) +
                     s"""  "dateFrom" : "2015-09-10",
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

  def getVrnIsKnownInETMPFor(vrn: Vrn): StubMapping =
    stubFor(
      get(urlEqualTo(s"/vat/customer/vrn/${vrn.value}/information"))
      .willReturn(aResponse().withBody(s"""{
                                          |    "vrn": "${vrn.value}",
                                          |    "approvedInformation": {
                                          |        "customerDetails": {
                                          |            "individual": {
                                          |                "firstName": "Daniel",
                                          |                "lastName": "Begum"
                                          |            },
                                          |            "dateOfBirth": "1939-08-11",
                                          |            "tradingName": "Dominum",
                                          |            "mandationStatus": "3",
                                          |            "registrationReason": "0001",
                                          |            "effectiveRegistrationDate": "2009-11-10",
                                          |            "businessStartDate": "2005-07-15"
                                          |        },
                                          |        "PPOB": {
                                          |            "address": {
                                          |                "line1": "73 Tut Hole",
                                          |                "line2": "Motherwell",
                                          |                "postCode": "ML93 5ML",
                                          |                "countryCode": "GB"
                                          |            },
                                          |            "RLS": "0006",
                                          |            "contactDetails": {
                                          |                "primaryPhoneNumber": "04538 597689",
                                          |                "mobileNumber": "08217 419358",
                                          |                "faxNumber": "08217 419358",
                                          |                "emailAddress": "l@D.eu"
                                          |            },
                                          |            "websiteAddress": "v"
                                          |        },
                                          |        "correspondenceContactDetails": {
                                          |            "address": {
                                          |                "line1": "58 Abercorn Place",
                                          |                "line2": "The Hassan House",
                                          |                "postCode": "CA32 8AC",
                                          |                "countryCode": "GB"
                                          |            },
                                          |            "RLS": "0006",
                                          |            "contactDetails": {
                                          |                "primaryPhoneNumber": "04538 597689",
                                          |                "mobileNumber": "08217 419358",
                                          |                "faxNumber": "08217 419358",
                                          |                "emailAddress": "l@D.eu"
                                          |            }
                                          |        },
                                          |        "bankDetails": {
                                          |            "IBAN": "v",
                                          |            "BIC": "v",
                                          |            "accountHolderName": "v",
                                          |            "bankAccountNumber": "82174193",
                                          |            "sortCode": "821741",
                                          |            "buildingSocietyNumber": "v",
                                          |            "bankBuildSocietyName": "v"
                                          |        },
                                          |        "businessActivities": {
                                          |            "primaryMainCode": "82174",
                                          |            "mainCode2": "82174",
                                          |            "mainCode3": "82174",
                                          |            "mainCode4": "82174"
                                          |        },
                                          |        "flatRateScheme": {
                                          |            "FRSCategory": "015",
                                          |            "FRSPercentage": 0,
                                          |            "startDate": "1985-04-09",
                                          |            "limitedCostTrader": true
                                          |        },
                                          |        "returnPeriod": {
                                          |            "stdReturnPeriod": "MC",
                                          |            "nonStdTaxPeriods": {
                                          |                "period01": "2009-11-10",
                                          |                "period02": "2009-11-10",
                                          |                "period03": "2009-11-10",
                                          |                "period04": "2009-11-10",
                                          |                "period05": "2009-11-10",
                                          |                "period06": "2009-11-10",
                                          |                "period07": "2009-11-10",
                                          |                "period08": "2009-11-10",
                                          |                "period09": "2009-11-10",
                                          |                "period10": "2009-11-10",
                                          |                "period11": "2009-11-10",
                                          |                "period12": "2009-11-10",
                                          |                "period13": "2009-11-10",
                                          |                "period14": "2009-11-10",
                                          |                "period15": "2009-11-10",
                                          |                "period16": "2009-11-10",
                                          |                "period17": "2009-11-10",
                                          |                "period18": "2009-11-10",
                                          |                "period19": "2009-11-10",
                                          |                "period20": "2009-11-10",
                                          |                "period21": "2009-11-10",
                                          |                "period22": "2009-11-10"
                                          |            }
                                          |        },
                                          |        "groupOrPartnerMbrs": [
                                          |            {
                                          |                "typeOfRelationship": "01",
                                          |                "organisationName": "Dominum",
                                          |                "individual": {
                                          |                    "title": "0003",
                                          |                    "firstName": "Daniel",
                                          |                    "middleName": "Lincoln",
                                          |                    "lastName": "Bonneviot"
                                          |                },
                                          |                "SAP_Number": "174193586055099507220032829225513697468686"
                                          |            }
                                          |        ]
                                          |    },
                                          |    "id": "6111032d3700004c3db66092"
                                          |}""".stripMargin).withStatus(200)))

  def getVrnIsNotKnownInETMPFor(vrn: Vrn): StubMapping =
    stubFor(
      get(urlEqualTo(s"/vat/customer/vrn/${vrn.value}/information"))
        .willReturn(aResponse().withBody(s"""{}""").withStatus(200)))

  def givenDESRespondsWithStatusForVrn(vrn: Vrn, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(s"/vat/customer/vrn/${vrn.value}/information"))
        .willReturn(aResponse().withStatus(status)))

}
