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
import uk.gov.hmrc.domain.TaxIdentifier

import java.time.LocalDate

trait AgentClientRelationshipStub {

  def givenReturnsServerError(): StubMapping

  def givenReturnsServiceUnavailable(): StubMapping

  def givenAgentCanBeAllocated(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping

  def givenAgentCanNotBeAllocated(status: Int): StubMapping

  def givenAgentCanBeDeallocated(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping

  def givenAgentHasNoActiveRelationship(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping

  def givenAgentCanNotBeDeallocated(status: Int): StubMapping

  def getActiveRelationshipFailsWith(taxIdentifier: TaxIdentifier, status: Int): StubMapping

  def getActiveRelationshipFailsWithSuspended(taxIdentifier: TaxIdentifier): StubMapping

  def getActiveRelationshipsViaClient(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping

  def getInactiveRelationshipViaClient(taxIdentifier: TaxIdentifier, agentArn: String): StubMapping

  def getSomeActiveRelationshipsViaClient(
    taxIdentifier: TaxIdentifier,
    agentArn1: String,
    agentArn2: String,
    agentArn3: String
  ): StubMapping

  def getInactiveRelationshipsForClient(taxIdentifier: TaxIdentifier): StubMapping

  def getNoInactiveRelationshipsForClient(taxIdentifier: TaxIdentifier): StubMapping

  def getFailInactiveRelationshipsForClient(
    taxIdentifier: TaxIdentifier,
    status: Int,
    body: Option[String] = None
  ): StubMapping

  def getInactiveRelationshipsViaAgent(
    arn: Arn,
    otherTaxIdentifier: TaxIdentifier,
    taxIdentifier: TaxIdentifier
  ): StubMapping

  def getAgentInactiveRelationshipsButActive(encodedArn: String, agentArn: String, clientId: String): StubMapping

  def getFailAgentInactiveRelationships(encodedArn: String, status: Int): StubMapping

  def getFailWithSuspendedAgentInactiveRelationships(encodedArn: String): StubMapping

  def getFailWithInvalidAgentInactiveRelationships(encodedArn: String): StubMapping

  def getAgentInactiveRelationshipsNoDateTo(arn: Arn, clientId: String): StubMapping
}

trait HIPAgentClientRelationshipStub extends AgentClientRelationshipStub {

  override def givenReturnsServerError(): StubMapping =
    stubFor(
      any(urlMatching(s"/RESTAdapter/rosm/agent-relationship?.*"))
        .willReturn(aResponse().withStatus(500))
    )

  override def givenReturnsServiceUnavailable(): StubMapping =
    stubFor(
      any(urlMatching(s"/RESTAdapter/rosm/agent-relationship?.*"))
        .willReturn(aResponse().withStatus(503))
    )

  override def givenAgentCanBeAllocated(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
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

  override def givenAgentCanNotBeAllocated(status: Int): StubMapping =
    stubFor(
      post(urlEqualTo(s"/RESTAdapter/rosm/agent-relationship"))
        .withRequestBody(containing("\"0001\""))
        .willReturn(
          aResponse()
            .withStatus(status)
            .withBody(s"""{"reason": "Service unavailable"}""")
        )
    )

  override def givenAgentCanBeDeallocated(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
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

  override def givenAgentHasNoActiveRelationship(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
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

  override def givenAgentCanNotBeDeallocated(status: Int): StubMapping =
    stubFor(
      post(urlEqualTo(s"/RESTAdapter/rosm/agent-relationship"))
        .withRequestBody(containing("\"0002\""))
        .willReturn(
          aResponse()
            .withStatus(status)
            .withBody(s"""{"reason": "Service unavailable"}""")
        )
    )

  override def getActiveRelationshipFailsWith(taxIdentifier: TaxIdentifier, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(url(taxIdentifier)))
        .willReturn(
          aResponse()
            .withStatus(status)
        )
    )

  override def getActiveRelationshipFailsWithSuspended(taxIdentifier: TaxIdentifier): StubMapping =
    stubFor(
      get(urlEqualTo(url(taxIdentifier)))
        .willReturn(
          aResponse()
            .withStatus(403)
            .withBody("suspended")
        )
    )

  override def getActiveRelationshipsViaClient(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
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

  override def getInactiveRelationshipViaClient(taxIdentifier: TaxIdentifier, agentArn: String): StubMapping =
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

  override def getSomeActiveRelationshipsViaClient(
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

  override def getInactiveRelationshipsForClient(taxIdentifier: TaxIdentifier): StubMapping = {

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

  override def getNoInactiveRelationshipsForClient(taxIdentifier: TaxIdentifier): StubMapping =
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

  override def getFailInactiveRelationshipsForClient(
    taxIdentifier: TaxIdentifier,
    status: Int,
    body: Option[String] = None
  ): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrlClient(taxIdentifier)))
        .willReturn(aResponse().withStatus(status).withBody(body.getOrElse("")))
    )

  override def getInactiveRelationshipsViaAgent(
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

  override def getAgentInactiveRelationshipsButActive(
    encodedArn: String,
    agentArn: String,
    clientId: String
  ): StubMapping =
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

  override def getFailAgentInactiveRelationships(encodedArn: String, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrl(Arn(encodedArn))))
        .willReturn(
          aResponse()
            .withStatus(status)
        )
    )

  override def getFailWithSuspendedAgentInactiveRelationships(encodedArn: String): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrl(Arn(encodedArn))))
        .willReturn(
          aResponse()
            .withStatus(403)
            .withBody("suspended")
        )
    )

  override def getFailWithInvalidAgentInactiveRelationships(encodedArn: String): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrl(Arn(encodedArn))))
        .willReturn(
          aResponse()
            .withStatus(503)
        )
    )

  override def getAgentInactiveRelationshipsNoDateTo(arn: Arn, clientId: String): StubMapping =
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

  private val url: TaxIdentifier => String = {
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

  private val otherInactiveRelationship: TaxIdentifier => Arn => String = {
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

}

trait IFAgentClientRelationshipStub extends AgentClientRelationshipStub {

  override def givenReturnsServerError(): StubMapping =
    stubFor(
      any(urlMatching(s"/registration/.*"))
        .willReturn(aResponse().withStatus(500))
    )

  override def givenReturnsServiceUnavailable(): StubMapping =
    stubFor(
      any(urlMatching(s"/registration/.*"))
        .willReturn(aResponse().withStatus(503))
    )

  override def givenAgentCanBeAllocated(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing(taxIdentifier.value))
        .withRequestBody(containing(arn.value))
        .withRequestBody(containing("\"Authorise\""))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"processingDate": "2001-12-17T09:30:47Z"}""")
        )
    )

  override def givenAgentCanNotBeAllocated(status: Int): StubMapping =
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing("\"Authorise\""))
        .willReturn(
          aResponse()
            .withStatus(status)
            .withBody(s"""{"reason": "Service unavailable"}""")
        )
    )

  override def givenAgentCanBeDeallocated(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing(taxIdentifier.value))
        .withRequestBody(containing(arn.value))
        .withRequestBody(containing("\"De-Authorise\""))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"processingDate": "2001-03-14T19:16:07Z"}""")
        )
    )

  override def givenAgentHasNoActiveRelationship(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing(taxIdentifier.value))
        .withRequestBody(containing(arn.value))
        .withRequestBody(containing("\"De-Authorise\""))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"processingDate": "2001-03-14T19:16:07Z"}""")
        )
    )

  override def givenAgentCanNotBeDeallocated(status: Int): StubMapping =
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing("\"De-Authorise\""))
        .willReturn(
          aResponse()
            .withStatus(status)
            .withBody(s"""{"reason": "Service unavailable"}""")
        )
    )

  override def getActiveRelationshipFailsWith(taxIdentifier: TaxIdentifier, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(url(taxIdentifier)))
        .willReturn(
          aResponse()
            .withStatus(status)
        )
    )

  override def getActiveRelationshipFailsWithSuspended(taxIdentifier: TaxIdentifier): StubMapping =
    stubFor(
      get(urlEqualTo(url(taxIdentifier)))
        .willReturn(
          aResponse()
            .withStatus(403)
            .withBody("AGENT_SUSPENDED")
        )
    )

  override def getActiveRelationshipsViaClient(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
    stubFor(
      get(urlEqualTo(url(taxIdentifier)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
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
                         |}""".stripMargin)
        )
    )

  override def getInactiveRelationshipViaClient(taxIdentifier: TaxIdentifier, agentArn: String): StubMapping =
    stubFor(
      get(urlEqualTo(url(taxIdentifier)))
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
                         |}""".stripMargin)
        )
    )

  override def getSomeActiveRelationshipsViaClient(
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
                         |}""".stripMargin)
        )
    )

  // VIA Agent
  override def getInactiveRelationshipsForClient(taxIdentifier: TaxIdentifier): StubMapping = {

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
                                                                                                                                                                                                               |}""".stripMargin
            )
        )
    )
  }

  override def getNoInactiveRelationshipsForClient(taxIdentifier: TaxIdentifier): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrlClient(taxIdentifier)))
        .willReturn(
          aResponse()
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
                         |}""".stripMargin)
        )
    )

  override def getFailInactiveRelationshipsForClient(
    taxIdentifier: TaxIdentifier,
    status: Int,
    body: Option[String] = None
  ): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrlClient(taxIdentifier)))
        .willReturn(aResponse().withStatus(status).withBody(body.getOrElse("")))
    )

  override def getInactiveRelationshipsViaAgent(
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
                 |{
                 |"relationship" : [
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
                   |}""".stripMargin
            )
        )
    )
  }

  override def getAgentInactiveRelationshipsButActive(
    encodedArn: String,
    agentArn: String,
    clientId: String
  ): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrl(Arn(agentArn))))
        .willReturn(
          aResponse()
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
                         |}""".stripMargin)
        )
    )

  override def getFailAgentInactiveRelationships(encodedArn: String, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrl(Arn(encodedArn))))
        .willReturn(
          aResponse()
            .withStatus(status)
        )
    )

  override def getFailWithSuspendedAgentInactiveRelationships(encodedArn: String): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrl(Arn(encodedArn))))
        .willReturn(
          aResponse()
            .withStatus(403)
            .withBody("AGENT_SUSPENDED")
        )
    )

  override def getFailWithInvalidAgentInactiveRelationships(encodedArn: String): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrl(Arn(encodedArn))))
        .willReturn(
          aResponse()
            .withStatus(503)
        )
    )

  override def getAgentInactiveRelationshipsNoDateTo(arn: Arn, clientId: String): StubMapping =
    stubFor(
      get(urlEqualTo(inactiveUrl(arn)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{
                         |"relationship" :[
                         |{
                         |  "referenceNumber" : "$clientId",
                         |  "agentReferenceNumber" : "${arn.value}",
                         |  "organisation" : {
                         |    "organisationName": "someOrganisationName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "contractAccountCategory" : "01",
                         |  "activity" : "09"
                         |}
                         |]
                         |}""".stripMargin)
        )
    )

  private val url: TaxIdentifier => String = {
    case MtdItId(mtdItId) =>
      s"/registration/relationship?referenceNumber=$mtdItId&agent=false&active-only=true&regime=ITSA&relationship=ZA01&auth-profile=ALL00001"
    case Vrn(vrn) =>
      s"/registration/relationship?idType=VRN&referenceNumber=$vrn&agent=false&active-only=true&regime=VATC&relationship=ZA01&auth-profile=ALL00001"
    case Utr(utr) =>
      s"/registration/relationship?idType=UTR&referenceNumber=$utr&agent=false&active-only=true&regime=TRS"
    case Urn(urn) =>
      s"/registration/relationship?idType=URN&referenceNumber=$urn&agent=false&active-only=true&regime=TRS"
    case CgtRef(ref) =>
      s"/registration/relationship?idType=ZCGT&referenceNumber=$ref&agent=false&active-only=true&regime=CGT&relationship=ZA01&auth-profile=ALL00001"
    case PptRef(ref) =>
      s"/registration/relationship?idType=ZPPT&referenceNumber=$ref&agent=false&active-only=true&regime=PPT&relationship=ZA01&auth-profile=ALL00001"
    case CbcId(ref) =>
      s"/registration/relationship?idType=CBC&referenceNumber=$ref&agent=false&active-only=true&regime=CBC"
    case PlrId(ref) =>
      s"/registration/relationship?idType=ZPLR&referenceNumber=$ref&agent=false&active-only=true&regime=PLR"
    case x => throw new IllegalArgumentException(s"Tax identifier not supported $x")
  }

  private val otherInactiveRelationship: TaxIdentifier => Arn => String = {
    case MtdItId(clientId) => arn => s"""
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
    case Vrn(clientId) => arn => s"""
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
    case Utr(clientId) => arn => s"""
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
    case Urn(clientId) => arn => s"""
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
    case PptRef(clientId) => arn => s"""
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
    case CgtRef(clientId) => arn => s"""
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
    case PlrId(clientId) => arn => s"""
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
    case x => throw new IllegalArgumentException(s"Tax identifier not supported $x")
  }

  private def inactiveUrl(arn: Arn) = s"/registration/relationship?arn=${arn.value}" +
    s"&agent=true&active-only=false&regime=AGSV&from=${LocalDate.now().minusDays(30).toString}&to=${LocalDate.now().toString}"

  private val inactiveUrlClient: TaxIdentifier => String = {
    case MtdItId(mtdItId) =>
      s"/registration/relationship?referenceNumber=$mtdItId&agent=false&active-only=false&regime=ITSA&from=2015-01-01&to=${LocalDate.now().toString}&relationship=ZA01&auth-profile=ALL00001"
    case Vrn(vrn) =>
      s"/registration/relationship?idType=VRN&referenceNumber=$vrn&agent=false&active-only=false&regime=VATC&from=2015-01-01&to=${LocalDate.now().toString}&relationship=ZA01&auth-profile=ALL00001"
    case Utr(utr) =>
      s"/registration/relationship?idType=UTR&referenceNumber=$utr&agent=false&active-only=false&regime=TRS&from=2015-01-01&to=${LocalDate.now().toString}"
    case Urn(urn) =>
      s"/registration/relationship?idType=URN&referenceNumber=$urn&agent=false&active-only=false&regime=TRS&from=2015-01-01&to=${LocalDate.now().toString}"
    case CgtRef(ref) =>
      s"/registration/relationship?idType=ZCGT&referenceNumber=$ref&agent=false&active-only=false&regime=CGT&from=2015-01-01&to=${LocalDate.now().toString}&relationship=ZA01&auth-profile=ALL00001"
    case PptRef(ref) =>
      s"/registration/relationship?idType=ZPPT&referenceNumber=$ref&agent=false&active-only=false&regime=PPT&from=2015-01-01&to=${LocalDate.now().toString}&relationship=ZA01&auth-profile=ALL00001"
    case PlrId(ref) =>
      s"/registration/relationship?idType=ZPLR&referenceNumber=$ref&agent=false&active-only=false&regime=PLR&from=2015-01-01&to=${LocalDate.now().toString}"
    case x => throw new IllegalArgumentException(s"Tax identifier not supported $x")
  }

}
