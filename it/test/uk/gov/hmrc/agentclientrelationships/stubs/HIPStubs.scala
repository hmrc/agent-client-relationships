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
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}

import java.time.LocalDate

trait HIPStubs {

  val url: TaxIdentifier => String = {
    case MtdItId(mtdItId) =>
      s"/RESTAdapter/rosm/agent-relationship?refNumber=$mtdItId&isAnAgent=false&activeOnly=true&regime=ITSA&relationshipType=ZA01&authProfile=ALL00001"
    case Vrn(vrn) =>
      s"/RESTAdapter/rosm/agent-relationship?idType=VRN&refNumber=$vrn&isAnAgent=false&activeOnly=true&regime=VATC&relationshipType=ZA01&authProfile=ALL00001"
    case Utr(utr) =>
      s"/RESTAdapter/rosm/agent-relationship?idType=UTR&refNumber=$utr&isAnAgent=false&activeOnly=true&regime=TRS"
    case Urn(urn) =>
      s"/RESTAdapter/rosm/agent-relationship?idType=URN&refNumber=$urn&isAnAgent=false&activeOnly=true&regime=TRS"
    case CgtRef(ref) =>
      s"/RESTAdapter/rosm/agent-relationship?idType=ZCGT&refNumber=$ref&isAnAgent=false&activeOnly=true&regime=CGT&relationshipType=ZA01&authProfile=ALL00001"
    case PptRef(ref) =>
      s"/RESTAdapter/rosm/agent-relationship?idType=ZPPT&refNumber=$ref&isAnAgent=false&activeOnly=true&regime=PPT&relationshipType=ZA01&authProfile=ALL00001"
    case CbcId(ref) =>
      s"/RESTAdapter/rosm/agent-relationship?idType=CBC&refNumber=$ref&isAnAgent=false&activeOnly=true&regime=CBC"
    case PlrId(ref) =>
      s"/RESTAdapter/rosm/agent-relationship?idType=ZPLR&refNumber=$ref&isAnAgent=false&activeOnly=true&regime=PLR"
    case x => throw new IllegalArgumentException(s"Tax identifier not supported $x")
  }

  def givenHIPReturnsServerError(): StubMapping =
    stubFor(
      any(urlMatching(s"/RESTAdapter/rosm/agent-relationship?.*"))
        .willReturn(aResponse().withStatus(500))
    )

  def givenHIPReturnsServiceUnavailable(): StubMapping =
    stubFor(
      any(urlMatching(s"/RESTAdapter/rosm/agent-relationship?.*"))
        .willReturn(aResponse().withStatus(503))
    )

  def givenAgentCanBeAllocatedInHIP(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
    stubFor(
      post(urlEqualTo(s"/RESTAdapter/rosm/agent-relationship"))
        .withRequestBody(containing(taxIdentifier.value))
        .withRequestBody(containing(arn.value))
        .withRequestBody(containing("\"0001\""))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"processingDate": "2001-12-17T09:30:47Z"}""")
        )
    )

  def givenAgentCanNotBeAllocatedInHIP(status: Int): StubMapping =
    stubFor(
      post(urlEqualTo(s"/RESTAdapter/rosm/agent-relationship"))
        .withRequestBody(containing("\"0001\""))
        .willReturn(
          aResponse()
            .withStatus(status)
            .withBody(s"""{"reason": "Service unavailable"}""")
        )
    )

  def givenAgentCanBeDeallocatedInHIP(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
    stubFor(
      post(urlEqualTo(s"/RESTAdapter/rosm/agent-relationship"))
        .withRequestBody(containing(taxIdentifier.value))
        .withRequestBody(containing(arn.value))
        .withRequestBody(containing("\"0002\""))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"processingDate": "2001-03-14T19:16:07Z"}""")
        )
    )

  def givenAgentHasNoActiveRelationshipInHIP(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
    stubFor(
      post(urlEqualTo(s"/RESTAdapter/rosm/agent-relationship"))
        .withRequestBody(containing(taxIdentifier.value))
        .withRequestBody(containing(arn.value))
        .withRequestBody(containing("\"0002\""))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"processingDate": "2001-03-14T19:16:07Z"}""")
        )
    )

  def givenAgentCanNotBeDeallocatedInHIP(status: Int): StubMapping =
    stubFor(
      post(urlEqualTo(s"/RESTAdapter/rosm/agent-relationship"))
        .withRequestBody(containing("\"0002\""))
        .willReturn(
          aResponse()
            .withStatus(status)
            .withBody(s"""{"reason": "Service unavailable"}""")
        )
    )

  def getActiveRelationshipFailsWith(taxIdentifier: TaxIdentifier, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(url(taxIdentifier)))
        .willReturn(
          aResponse()
            .withStatus(status)
        )
    )

  def getActiveRelationshipFailsWithSuspended(taxIdentifier: TaxIdentifier): StubMapping =
    stubFor(
      get(urlEqualTo(url(taxIdentifier)))
        .willReturn(
          aResponse()
            .withStatus(403)
            .withBody("suspended")
        )
    )

  def getActiveRelationshipsViaClient(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
    stubFor(
      get(urlEqualTo(url(taxIdentifier)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |[
                         |{
                         |  "refNumber" : "${taxIdentifier.value}",
                         |  "arn" : "${arn.value}",
                         |  "organisation" : {
                         |    "organisationName": "someOrganisationName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "dateTo" : "9999-12-31",
                         |  "contractAccountCategory" : "01",
                         |  "activity" : "09"
                         |}
                         |]""".stripMargin)
        )
    )

  def getInactiveRelationshipViaClient(taxIdentifier: TaxIdentifier, agentArn: String): StubMapping =
    stubFor(
      get(urlEqualTo(url(taxIdentifier)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |[
                         |{
                         |  "refNumber" : "${taxIdentifier.value}",
                         |  "arn" : "$agentArn",
                         |  "organisation" : {
                         |    "organisationName": "someOrganisationName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "dateTo" : "2016-12-31",
                         |  "contractAccountCategory" : "01",
                         |  "activity" : "09"
                         |}
                         |]""".stripMargin)
        )
    )

  def getSomeActiveRelationshipsViaClient(
    taxIdentifier: TaxIdentifier,
    agentArn1: String,
    agentArn2: String,
    agentArn3: String
  ): StubMapping =
    stubFor(
      get(urlEqualTo(url(taxIdentifier)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |[
                         |{
                         |  "refNumber" : "$taxIdentifier",
                         |  "arn" : "$agentArn1",
                         |  "organisation" : {
                         |    "organisationName": "someOrganisationName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "dateTo" : "2015-12-31",
                         |  "contractAccountCategory" : "01",
                         |  "activity" : "10"
                         |},
                         |{
                         |  "refNumber" : "$taxIdentifier",
                         |  "arn" : "$agentArn2",
                         |  "organisation" : {
                         |    "organisationName": "sayOrganisationName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "dateTo" : "2016-12-31",
                         |  "contractAccountCategory" : "02",
                         |  "activity" : "09"
                         |},
                         |{
                         |  "refNumber" : "$taxIdentifier",
                         |  "arn" : "$agentArn3",
                         |  "organisation" : {
                         |    "organisationName": "noneOrganisationName"
                         |  },
                         |  "dateFrom" : "2014-09-10",
                         |  "dateTo" : "9999-12-31",
                         |  "contractAccountCategory" : "03",
                         |  "activity" : "11"
                         |}
                         |]""".stripMargin)
        )
    )

  // VIA Agent

  val otherInactiveRelationship: TaxIdentifier => Arn => String = {
    case MtdItId(clientId) => arn => s"""
                                        |{
                                        |  "refNumber" : "$clientId",
                                        |  "arn" : "${arn.value}",
                                        |  "individual" : {
                                        |    "firstName": "someName"
                                        |  },
                                        |  "dateFrom" : "2015-09-10",
                                        |  "dateTo" : "2015-09-21",
                                        |  "contractAccountCategory" : "01",
                                        |  "activity" : "09"
                                        |}
       """.stripMargin
    case Vrn(clientId) => arn => s"""
                                    |{
                                    |  "refNumber" : "$clientId",
                                    |  "arn" : "${arn.value}",
                                    |  "organisation" : {
                                    |    "organisationName": "someOrganisationName"
                                    |  },
                                    |  "dateFrom" : "2015-09-10",
                                    |  "dateTo" : "2015-09-21",
                                    |  "contractAccountCategory" : "01",
                                    |  "activity" : "09"
                                    |}
       """.stripMargin
    case Utr(clientId) => arn => s"""
                                    |{
                                    |  "refNumber" : "$clientId",
                                    |  "arn" : "${arn.value}",
                                    |  "organisation" : {
                                    |    "organisationName": "someOrganisationName"
                                    |  },
                                    |  "dateFrom" : "2015-09-10",
                                    |  "dateTo" : "2015-09-21",
                                    |  "contractAccountCategory" : "01",
                                    |  "activity" : "09"
                                    |}
       """.stripMargin
    case Urn(clientId) => arn => s"""
                                    |{
                                    |  "refNumber" : "$clientId",
                                    |  "arn" : "${arn.value}",
                                    |  "organisation" : {
                                    |    "organisationName": "someOrganisationName"
                                    |  },
                                    |  "dateFrom" : "2015-09-10",
                                    |  "dateTo" : "2015-09-21",
                                    |  "contractAccountCategory" : "01",
                                    |  "activity" : "09"
                                    |}
       """.stripMargin
    case PptRef(clientId) => arn => s"""
                                       |{
                                       |  "refNumber" : "$clientId",
                                       |  "arn" : "${arn.value}",
                                       |  "organisation" : {
                                       |    "organisationName": "someOrganisationName"
                                       |  },
                                       |  "dateFrom" : "2015-09-10",
                                       |  "dateTo" : "2015-09-21",
                                       |  "contractAccountCategory" : "01",
                                       |  "activity" : "09"
                                       |}
       """.stripMargin
    case CgtRef(clientId) => arn => s"""
                                       |{
                                       |  "refNumber" : "$clientId",
                                       |  "arn" : "${arn.value}",
                                       |  "organisation" : {
                                       |    "organisationName": "someOrganisationName"
                                       |  },
                                       |  "dateFrom" : "2015-09-10",
                                       |  "dateTo" : "2015-09-21",
                                       |  "contractAccountCategory" : "01",
                                       |  "activity" : "09"
                                       |}
       """.stripMargin
    case PlrId(clientId) => arn => s"""
                                      |{
                                      |  "refNumber" : "$clientId",
                                      |  "arn" : "${arn.value}",
                                      |  "organisation" : {
                                      |    "organisationName": "someOrganisationName"
                                      |  },
                                      |  "dateFrom" : "2015-09-10",
                                      |  "dateTo" : "2015-09-21",
                                      |  "contractAccountCategory" : "01",
                                      |  "activity" : "09"
                                      |}
       """.stripMargin
    case x => throw new IllegalArgumentException(s"Tax identifier not supported $x")
  }

  private def inactiveUrl(arn: Arn) = s"/RESTAdapter/rosm/agent-relationship?arn=${arn.value}" +
    s"&isAnAgent=true&activeOnly=false&regime=AGSV&dateFrom=${LocalDate.now().minusDays(30).toString}&dateTo=${LocalDate.now().toString}"

  private val inactiveUrlClient: TaxIdentifier => String = {
    case MtdItId(mtdItId) =>
      s"/RESTAdapter/rosm/agent-relationship?refNumber=$mtdItId&isAnAgent=false&activeOnly=false&regime=ITSA&dateFrom=2015-01-01&dateTo=${LocalDate.now().toString}&relationshipType=ZA01&authProfile=ALL00001"
    case Vrn(vrn) =>
      s"/RESTAdapter/rosm/agent-relationship?idType=VRN&refNumber=$vrn&isAnAgent=false&activeOnly=false&regime=VATC&dateFrom=2015-01-01&dateTo=${LocalDate.now().toString}&relationshipType=ZA01&authProfile=ALL00001"
    case Utr(utr) =>
      s"/RESTAdapter/rosm/agent-relationship?idType=UTR&refNumber=$utr&isAnAgent=false&activeOnly=false&regime=TRS&dateFrom=2015-01-01&dateTo=${LocalDate.now().toString}"
    case Urn(urn) =>
      s"/RESTAdapter/rosm/agent-relationship?idType=URN&refNumber=$urn&isAnAgent=false&activeOnly=false&regime=TRS&dateFrom=2015-01-01&dateTo=${LocalDate.now().toString}"
    case CgtRef(ref) =>
      s"/RESTAdapter/rosm/agent-relationship?idType=ZCGT&refNumber=$ref&isAnAgent=false&activeOnly=false&regime=CGT&dateFrom=2015-01-01&dateTo=${LocalDate.now().toString}&relationshipType=ZA01&authProfile=ALL00001"
    case PptRef(ref) =>
      s"/RESTAdapter/rosm/agent-relationship?idType=ZPPT&refNumber=$ref&isAnAgent=false&activeOnly=false&regime=PPT&dateFrom=2015-01-01&dateTo=${LocalDate.now().toString}&relationshipType=ZA01&authProfile=ALL00001"
    case PlrId(ref) =>
      s"/RESTAdapter/rosm/agent-relationship?idType=ZPLR&refNumber=$ref&isAnAgent=false&activeOnly=false&regime=PLR&dateFrom=2015-01-01&dateTo=${LocalDate.now().toString}"
    case x => throw new IllegalArgumentException(s"Tax identifier not supported $x")
  }

  def getInactiveRelationshipsForClient(taxIdentifier: TaxIdentifier): StubMapping = {

    val individualOrOrganisationJson =
      if (taxIdentifier.isInstanceOf[MtdItId])
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

    stubFor(
      get(urlEqualTo(inactiveUrlClient(taxIdentifier)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(
              s"""
                 |[
                 |{
                 |  "refNumber" : "${taxIdentifier.value}",
                 |  "arn" : "ABCDE123456", """.stripMargin + individualOrOrganisationJson + s"""
                                                                                               |  "dateFrom" : "2015-09-10",
                                                                                               |  "dateTo" : "2018-09-09",
                                                                                               |  "contractAccountCategory" : "01",
                                                                                               |  "activity" : "09"
                                                                                               |},
                                                                                               |{
                                                                                               | "refNumber" : "${taxIdentifier.value}",
                                                                                               |  "arn" : "ABCDE777777", """.stripMargin + individualOrOrganisationJson + s"""
                                                                                                                                                                             |  "dateFrom" : "2019-09-09",
                                                                                                                                                                             |  "dateTo" : "2050-09-09",
                                                                                                                                                                             |  "contractAccountCategory" : "01",
                                                                                                                                                                             |  "activity" : "09"
                                                                                                                                                                             |  }
                                                                                                                                                                             |]""".stripMargin
            )
        )
    )
  }

  def getNoInactiveRelationshipsForClient(taxIdentifier: TaxIdentifier): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrlClient(taxIdentifier)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |[
                         |{
                         |  "refNumber" : "${taxIdentifier.value}",
                         |  "arn" : "ABCDE123456",
                         |  "individual" : {
                         |    "firstName": "someName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "dateTo" : "2050-09-09",
                         |  "contractAccountCategory" : "01",
                         |  "activity" : "09"
                         |}
                         |]""".stripMargin)
        )
    )

  def getFailInactiveRelationshipsForClient(
    taxIdentifier: TaxIdentifier,
    status: Int,
    body: Option[String] = None
  ): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrlClient(taxIdentifier)))
        .willReturn(aResponse().withStatus(status).withBody(body.getOrElse("")))
    )

  def getInactiveRelationshipsViaAgent(
    arn: Arn,
    otherTaxIdentifier: TaxIdentifier,
    taxIdentifier: TaxIdentifier
  ): StubMapping = {

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

    stubFor(
      get(urlEqualTo(inactiveUrl(arn)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(
              s"""
                 |[
                 |${otherInactiveRelationship(otherTaxIdentifier)(arn)},
                 |{
                 |  "refNumber" : "${taxIdentifier.value}",
                 |  "arn" : "${arn.value}",""".stripMargin +
                individualOrOrganisation(taxIdentifier) +
                s"""  "dateFrom" : "2015-09-10",
                   |  "dateTo" : "${LocalDate.now().toString}",
                   |  "contractAccountCategory" : "01",
                   |  "activity" : "09"
                   |}
                   |]""".stripMargin
            )
        )
    )
  }

  def getAgentInactiveRelationshipsButActive(encodedArn: String, agentArn: String, clientId: String): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrl(Arn(agentArn))))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |[
                         |{
                         |  "refNumber" : "$clientId",
                         |  "arn" : "$agentArn",
                         |  "organisation" : {
                         |    "organisationName": "someOrganisationName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "dateTo" : "9999-09-21",
                         |  "contractAccountCategory" : "01",
                         |  "activity" : "09"
                         |}
                         |]""".stripMargin)
        )
    )

  def getFailAgentInactiveRelationships(encodedArn: String, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrl(Arn(encodedArn))))
        .willReturn(
          aResponse()
            .withStatus(status)
        )
    )

  def getFailWithSuspendedAgentInactiveRelationships(encodedArn: String): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrl(Arn(encodedArn))))
        .willReturn(
          aResponse()
            .withStatus(403)
            .withBody("suspended")
        )
    )

  def getFailWithInvalidAgentInactiveRelationships(encodedArn: String): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrl(Arn(encodedArn))))
        .willReturn(
          aResponse()
            .withStatus(503)
        )
    )

  def getAgentInactiveRelationshipsNoDateTo(arn: Arn, clientId: String): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrl(arn)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |[
                         |{
                         |  "refNumber" : "$clientId",
                         |  "arn" : "${arn.value}",
                         |  "organisation" : {
                         |    "organisationName": "someOrganisationName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "contractAccountCategory" : "01",
                         |  "activity" : "09"
                         |}
                         |]""".stripMargin)
        )
    )

}
