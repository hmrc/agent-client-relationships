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
import org.scalatest.concurrent.Eventually.eventually
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}

import java.time.LocalDate

trait HipStub {

  def givenReturnsServerError(): StubMapping =
    stubFor(
      any(urlMatching(s"/etmp/RESTAdapter/rosm/agent-relationship?.*"))
        .willReturn(aResponse().withStatus(500))
    )

  def givenReturnsServiceUnavailable(): StubMapping =
    stubFor(
      any(urlMatching(s"/etmp/RESTAdapter/rosm/agent-relationship?.*"))
        .willReturn(aResponse().withStatus(503))
    )

  def givenAgentCanBeAllocated(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
    stubFor(
      post(urlEqualTo(s"/etmp/RESTAdapter/rosm/agent-relationship"))
        .withRequestBody(containing(taxIdentifier.value))
        .withRequestBody(containing(arn.value))
        .withRequestBody(containing("\"0001\""))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"processingDate": "2001-12-17T09:30:47Z"}""")
        )
    )

  def givenAgentCanNotBeAllocated(status: Int): StubMapping =
    stubFor(
      post(urlEqualTo(s"/etmp/RESTAdapter/rosm/agent-relationship"))
        .withRequestBody(containing("\"0001\""))
        .willReturn(
          aResponse()
            .withStatus(status)
            .withBody(s"""{"reason": "Service unavailable"}""")
        )
    )

  def givenAgentCanBeDeallocated(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
    stubFor(
      post(urlEqualTo(s"/etmp/RESTAdapter/rosm/agent-relationship"))
        .withRequestBody(containing(taxIdentifier.value))
        .withRequestBody(containing(arn.value))
        .withRequestBody(containing("\"0002\""))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"processingDate": "2001-03-14T19:16:07Z"}""")
        )
    )

  def givenAgentHasNoActiveRelationship(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
    stubFor(
      post(urlEqualTo(s"/etmp/RESTAdapter/rosm/agent-relationship"))
        .withRequestBody(containing(taxIdentifier.value))
        .withRequestBody(containing(arn.value))
        .withRequestBody(containing("\"0002\""))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"processingDate": "2001-03-14T19:16:07Z"}""")
        )
    )

  def givenAgentCanNotBeDeallocated(status: Int): StubMapping =
    stubFor(
      post(urlEqualTo(s"/etmp/RESTAdapter/rosm/agent-relationship"))
        .withRequestBody(containing("\"0002\""))
        .willReturn(
          aResponse()
            .withStatus(status)
            .withBody(s"""{"reason": "Service unavailable"}""")
        )
    )

  def getActiveRelationshipFailsWith(
    taxIdentifier: TaxIdentifier,
    status: Int,
    authProfile: String = "ALL00001"
  ): StubMapping =
    stubFor(
      get(
        urlEqualTo(
          relationshipHipUrl(taxIdentifier = taxIdentifier, authProfileOption = Some(authProfile), activeOnly = true)
        )
      )
        .willReturn(
          aResponse()
            .withStatus(status)
        )
    )

  def getActiveRelationshipFailsWithSuspended(
    taxIdentifier: TaxIdentifier,
    authProfile: String = "ALL00001"
  ): StubMapping =
    stubFor(
      get(
        urlEqualTo(
          relationshipHipUrl(taxIdentifier = taxIdentifier, authProfileOption = Some(authProfile), activeOnly = true)
        )
      )
        .willReturn(
          aResponse()
            .withStatus(403)
            .withBody("suspended")
        )
    )

  def getActiveRelationshipsViaClient(
    taxIdentifier: TaxIdentifier,
    arn: Arn,
    authProfile: String = "ALL00001"
  ): StubMapping =
    stubFor(
      get(
        urlEqualTo(
          relationshipHipUrl(taxIdentifier = taxIdentifier, authProfileOption = Some(authProfile), activeOnly = true)
        )
      )
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{
                         |"relationshipDisplayResponse":[
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
                         |]
                         |}""".stripMargin)
        )
    )

  def getAllActiveRelationshipsViaClient(
    taxIdentifier: TaxIdentifier,
    arn: Arn,
    activeOnly: Boolean = true
  ): StubMapping =
    stubFor(
      get(urlEqualTo(relationshipHipUrl(taxIdentifier = taxIdentifier, None, activeOnly = activeOnly)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{
                         |"relationshipDisplayResponse":[
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
                         |]
                         |}""".stripMargin)
        )
    )

  def getAllInactiveRelationshipsViaClient(
    taxIdentifier: TaxIdentifier,
    arn: Arn,
    activeOnly: Boolean = true
  ): StubMapping =
    stubFor(
      get(urlEqualTo(relationshipHipUrl(taxIdentifier = taxIdentifier, None, activeOnly = activeOnly)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{
                         |"relationshipDisplayResponse":[
                         |{
                         |  "refNumber" : "${taxIdentifier.value}",
                         |  "arn" : "${arn.value}",
                         |  "organisation" : {
                         |    "organisationName": "someOrganisationName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "dateTo" : "2016-09-10",
                         |  "contractAccountCategory" : "01",
                         |  "activity" : "09"
                         |}
                         |]
                         |}""".stripMargin)
        )
    )

  def getItsaMainAndSupportingActiveRelationshipsViaClient(
    taxIdentifier: TaxIdentifier,
    arnMain: Arn,
    arnSup: Arn,
    activeOnly: Boolean = true
  ): StubMapping =
    stubFor(
      get(urlEqualTo(relationshipHipUrl(taxIdentifier = taxIdentifier, None, activeOnly = activeOnly)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{
                         |"relationshipDisplayResponse":[
                         |{
                         |  "refNumber" : "${taxIdentifier.value}",
                         |  "arn" : "${arnMain.value}",
                         |  "organisation" : {
                         |    "organisationName": "someMainOrganisationName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "dateTo" : "9999-12-31",
                         |  "contractAccountCategory" : "01",
                         |  "authProfile" : "ALL00001",
                         |  "activity" : "09"
                         |},
                         |{
                         |  "refNumber" : "${taxIdentifier.value}",
                         |  "arn" : "${arnSup.value}",
                         |  "organisation" : {
                         |    "organisationName": "someSuppOrganisationName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "dateTo" : "9999-12-31",
                         |  "contractAccountCategory" : "01",
                         |  "authProfile" : "ITSAS001",
                         |  "activity" : "09"
                         |}
                         |
                         |]
                         |}""".stripMargin)
        )
    )

  def getItsaMainActiveAndSupportingInactiveRelationshipsViaClient(
    taxIdentifier: TaxIdentifier,
    arnMain: Arn,
    arnSup: Arn,
    activeOnly: Boolean = true
  ): StubMapping =
    stubFor(
      get(urlEqualTo(relationshipHipUrl(taxIdentifier = taxIdentifier, None, activeOnly = activeOnly)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{
                         |"relationshipDisplayResponse":[
                         |{
                         |  "refNumber" : "${taxIdentifier.value}",
                         |  "arn" : "${arnMain.value}",
                         |  "organisation" : {
                         |    "organisationName": "someMainOrganisationName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "dateTo" : "9999-12-31",
                         |  "contractAccountCategory" : "01",
                         |  "authProfile" : "ALL00001",
                         |  "activity" : "09"
                         |},
                         |{
                         |  "refNumber" : "${taxIdentifier.value}",
                         |  "arn" : "${arnSup.value}",
                         |  "organisation" : {
                         |    "organisationName": "someSuppOrganisationName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "dateTo" : "1900-12-31",
                         |  "contractAccountCategory" : "01",
                         |  "authProfile" : "ITSAS001",
                         |  "activity" : "09"
                         |}
                         |
                         |]
                         |}""".stripMargin)
        )
    )

  def getAllActiveRelationshipFailsWith(
    taxIdentifier: TaxIdentifier,
    status: Int,
    activeOnly: Boolean = true
  ): StubMapping =
    stubFor(
      get(urlEqualTo(relationshipHipUrl(taxIdentifier = taxIdentifier, None, activeOnly = activeOnly)))
        .willReturn(
          aResponse()
            .withStatus(status)
        )
    )

  def getAllActiveRelationshipFailsWithNotFound(
    taxIdentifier: TaxIdentifier,
    status: Int,
    activeOnly: Boolean = true
  ): StubMapping =
    stubFor(
      get(urlEqualTo(relationshipHipUrl(taxIdentifier = taxIdentifier, None, activeOnly = activeOnly)))
        .willReturn(
          aResponse()
            .withStatus(status)
            .withBody(s"""
                         {
                         |"errors": {
                         |"processingDate": "2024-07-15T09:45:17Z",
                         |"code": "009",
                         |"text": "No relationship found"
                         |}
                         |}""".stripMargin)
        )
    )

  def getAllActiveRelationshipFailsWithSuspended(
    taxIdentifier: TaxIdentifier,
    activeOnly: Boolean = true
  ): StubMapping =
    stubFor(
      get(urlEqualTo(relationshipHipUrl(taxIdentifier = taxIdentifier, None, activeOnly = activeOnly)))
        .willReturn(
          aResponse()
            .withStatus(422)
            .withBody("suspended")
        )
    )

  def verifyAllActiveRelationshipsViaClientCalled(
    taxIdentifier: TaxIdentifier,
    arn: Arn,
    activeOnly: Boolean = true,
    count: Int = 1
  ): Unit =
    eventually {
      verify(
        count,
        getRequestedFor(urlEqualTo(relationshipHipUrl(taxIdentifier = taxIdentifier, None, activeOnly = activeOnly)))
      )
    }

  def getInactiveRelationshipViaClient(
    taxIdentifier: TaxIdentifier,
    agentArn: String,
    authProfile: String = "ALL00001"
  ): StubMapping =
    stubFor(
      get(urlEqualTo(relationshipHipUrl(taxIdentifier = taxIdentifier, Some(authProfile), activeOnly = true)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{"relationshipDisplayResponse": [
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
                         |]}""".stripMargin)
        )
    )

  def getSomeActiveRelationshipsViaClient(
    taxIdentifier: TaxIdentifier,
    agentArn1: String,
    agentArn2: String,
    agentArn3: String,
    authProfile: String = "ALL00001"
  ): StubMapping =
    stubFor(
      get(urlEqualTo(relationshipHipUrl(taxIdentifier = taxIdentifier, Some(authProfile), activeOnly = true)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{"relationshipDisplayResponse":[
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
                         |]}""".stripMargin)
        )
    )

  def getInactiveRelationshipsForClient(
    taxIdentifier: TaxIdentifier,
    authProfile: String = "ALL00001"
  ): StubMapping = {

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
      get(urlEqualTo(relationshipHipUrl(taxIdentifier = taxIdentifier, Some(authProfile), activeOnly = false)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(
              s"""
                 |{"relationshipDisplayResponse":[
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
                                                                                                                                                                             |]}""".stripMargin
            )
        )
    )
  }

  def getNoInactiveRelationshipsForClient(
    taxIdentifier: TaxIdentifier,
    authProfile: String = "ALL00001"
  ): StubMapping =
    stubFor(
      get(urlEqualTo(relationshipHipUrl(taxIdentifier = taxIdentifier, Some(authProfile), activeOnly = false)))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{"relationshipDisplayResponse":[
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
                         |]}""".stripMargin)
        )
    )

  def getFailInactiveRelationshipsForClient(
    taxIdentifier: TaxIdentifier,
    status: Int,
    body: Option[String] = None,
    authProfile: String = "ALL00001"
  ): StubMapping =
    stubFor(
      get(urlEqualTo(relationshipHipUrl(taxIdentifier = taxIdentifier, Some(authProfile), activeOnly = false)))
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
                 |{
                 |"relationshipDisplayResponse" : [
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
                   |]
                   |}""".stripMargin
            )
        )
    )
  }

  def getAgentInactiveRelationshipsButActive(
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
                         |{"relationshipDisplayResponse":[
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
                         |]}""".stripMargin)
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
                         |{"relationshipDisplayResponse":[
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
                         |]}""".stripMargin)
        )
    )

  private def relationshipHipUrl(
    taxIdentifier: TaxIdentifier,
    authProfileOption: Option[String],
    activeOnly: Boolean = true,
    fromDateString: String = "2015-01-01"
  ) = {

    val dateRangeParams =
      if (activeOnly) ""
      else {
        val from = LocalDate.parse(fromDateString).toString
        val now = LocalDate.now().toString
        s"&dateFrom=$from&dateTo=$now"
      }

    val authProfileParam = authProfileOption.map(x => s"&authProfile=$x").getOrElse("")

    taxIdentifier match {
      case MtdItId(mtdItId) =>
        s"/etmp/RESTAdapter/rosm/agent-relationship?refNumber=$mtdItId&isAnAgent=false&activeOnly=$activeOnly&regime=ITSA$dateRangeParams&relationshipType=ZA01$authProfileParam"
      case Vrn(vrn) =>
        s"/etmp/RESTAdapter/rosm/agent-relationship?idType=VRN&refNumber=$vrn&isAnAgent=false&activeOnly=$activeOnly&regime=VATC$dateRangeParams&relationshipType=ZA01$authProfileParam"
      case Utr(utr) =>
        s"/etmp/RESTAdapter/rosm/agent-relationship?idType=UTR&refNumber=$utr&isAnAgent=false&activeOnly=$activeOnly&regime=TRS$dateRangeParams"
      case Urn(urn) =>
        s"/etmp/RESTAdapter/rosm/agent-relationship?idType=URN&refNumber=$urn&isAnAgent=false&activeOnly=$activeOnly&regime=TRS$dateRangeParams"
      case CgtRef(ref) =>
        s"/etmp/RESTAdapter/rosm/agent-relationship?idType=ZCGT&refNumber=$ref&isAnAgent=false&activeOnly=$activeOnly&regime=CGT$dateRangeParams&relationshipType=ZA01$authProfileParam"
      case PptRef(ref) =>
        s"/etmp/RESTAdapter/rosm/agent-relationship?idType=ZPPT&refNumber=$ref&isAnAgent=false&activeOnly=$activeOnly&regime=PPT$dateRangeParams&relationshipType=ZA01$authProfileParam"
      case CbcId(ref) =>
        s"/etmp/RESTAdapter/rosm/agent-relationship?idType=CBC&refNumber=$ref&isAnAgent=false&activeOnly=$activeOnly&regime=CBC$dateRangeParams"
      case PlrId(ref) =>
        s"/etmp/RESTAdapter/rosm/agent-relationship?idType=ZPLR&refNumber=$ref&isAnAgent=false&activeOnly=$activeOnly&regime=PLR$dateRangeParams"
      case _ => throw new IllegalStateException(s"Unsupported Identifier $taxIdentifier")
    }
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

  private def inactiveUrl(arn: Arn) = s"/etmp/RESTAdapter/rosm/agent-relationship?arn=${arn.value}" +
    s"&isAnAgent=true&activeOnly=false&regime=AGSV&dateFrom=${LocalDate.now().minusDays(30).toString}&dateTo=${LocalDate.now().toString}"

  def givenNinoIsKnownFor(mtdId: MtdItId, nino: Nino): StubMapping =
    stubFor(
      get(urlEqualTo(s"/etmp/RESTAdapter/itsa/taxpayer/business-details?mtdReference=${mtdId.value}"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"success":{"taxPayerDisplayResponse":{"nino": "${nino.value}" }}}""")
        )
    )

  def givenNinoIsUnknownFor(mtdId: MtdItId): StubMapping =
    stubFor(
      get(urlEqualTo(s"/etmp/RESTAdapter/itsa/taxpayer/business-details?mtdReference=${mtdId.value}"))
        .willReturn(
          aResponse()
            .withStatus(422)
            .withBody(s"""
                         {
                         |"errors": {
                         |"processingDate": "2024-07-15T09:45:17Z",
                         |"code": "008",
                         |"text": "ID not found"
                         |}
                         |}""".stripMargin)
        )
    )

  def givenmtdIdIsInvalid(mtdId: MtdItId): StubMapping =
    stubFor(
      get(urlMatching(s"/etmp/RESTAdapter/itsa/taxpayer/business-details?mtdReference=${mtdId.value}"))
        .willReturn(aResponse().withStatus(400))
    )

  def givenMtdItIdIsKnownFor(nino: Nino, mtdId: MtdItId): StubMapping =
    stubFor(
      get(urlEqualTo(s"/etmp/RESTAdapter/itsa/taxpayer/business-details?nino=${nino.value}"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"success":{"taxPayerDisplayResponse":{"mtdId": "${mtdId.value}" }}}""")
        )
    )

  def givenMtdItIdIsUnKnownFor(nino: Nino): StubMapping =
    stubFor(
      get(urlEqualTo(s"/etmp/RESTAdapter/itsa/taxpayer/business-details?nino=${nino.value}"))
        .willReturn(
          aResponse()
            .withStatus(422)
            .withBody(s"""
                         {
                         |"errors": {
                         |"processingDate": "2024-07-15T09:45:17Z",
                         |"code": "008",
                         |"text": "ID not found"
                         |}
                         |}""".stripMargin)
        )
    )

  def givenNinoIsInvalid(nino: Nino): StubMapping =
    stubFor(
      get(urlMatching(s"/etmp/RESTAdapter/itsa/taxpayer/business-details?nino=${nino.value}"))
        .willReturn(aResponse().withStatus(400))
    )

  // idType: String, id: String, foundId: String = "XAIT0000111122"
  def givenNinoItsaBusinessDetailsExists(
    mtdId: MtdItId,
    nino: Nino,
    postCode: String = "AA1 1AA",
    countryCode: String = "GB"
  ): StubMapping =
    stubFor(
      get(urlEqualTo(s"/etmp/RESTAdapter/itsa/taxpayer/business-details?mtdReference=${mtdId.value}"))
        .willReturn(
          aResponse()
            .withBody(s"""
                         |{
                         |    "success": {
                         |        "taxPayerDisplayResponse": {
                         |            "businessData": [
                         |                {
                         |                    "tradingName": "Erling Haal",
                         |                    "businessAddressDetails": {
                         |                        "postalCode": "$postCode",
                         |                        "countryCode": "$countryCode"
                         |                    }
                         |                }
                         |            ],
                         |            "nino": "${nino.value}"
                         |        }
                         |    }
                         |}
          """.stripMargin)
        )
    )

  def givenMtdItsaBusinessDetailsExists(
    nino: Nino,
    mtdId: MtdItId,
    postCode: String = "AA1 1AA",
    countryCode: String = "GB"
  ): StubMapping =
    stubFor(
      get(urlEqualTo(s"/etmp/RESTAdapter/itsa/taxpayer/business-details?nino=${nino.value}"))
        .willReturn(
          aResponse()
            .withBody(s"""
                         |{
                         |    "success": {
                         |        "taxPayerDisplayResponse": {
                         |            "businessData": [
                         |                {
                         |                    "tradingName": "Erling Haal",
                         |                    "businessAddressDetails": {
                         |                        "postalCode": "$postCode",
                         |                        "countryCode": "$countryCode"
                         |                    }
                         |                }
                         |            ],
                         |            "mtdId": "${mtdId.value}"
                         |        }
                         |    }
                         |}
          """.stripMargin)
        )
    )

  def givenMultipleItsaBusinessDetailsExists(nino: String): StubMapping =
    stubFor(
      get(urlEqualTo(s"/etmp/RESTAdapter/itsa/taxpayer/business-details?nino=$nino"))
        .willReturn(
          aResponse()
            .withBody(s"""
                         |{
                         |    "success": {
                         |        "taxPayerDisplayResponse": {
                         |            "businessData": [
                         |                {
                         |                    "tradingName": "Erling Haal",
                         |                    "businessAddressDetails": {
                         |                        "postalCode": "AA1 1AA",
                         |                        "countryCode": "GB"
                         |                    }
                         |                },
                         |                {
                         |                    "tradingName": "Bernard Silver",
                         |                    "businessAddressDetails": {
                         |                        "postalCode": "BB1 1BB",
                         |                        "countryCode": "PT"
                         |                    }
                         |                }
                         |            ]
                         |        }
                         |    }
                         |}
          """.stripMargin)
        )
    )

  def givenEmptyItsaBusinessDetailsExists(nino: String): StubMapping =
    stubFor(
      get(urlEqualTo(s"/etmp/RESTAdapter/itsa/taxpayer/business-details?nino=$nino"))
        .willReturn(
          aResponse()
            .withBody(s"""
                         |{
                         |    "success": {
                         |        "taxPayerDisplayResponse": {
                         |            "businessData": []
                         |        }
                         |    }
                         |}
          """.stripMargin)
        )
    )

  def givenItsaBusinessDetailsError(nino: String, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(s"/etmp/RESTAdapter/itsa/taxpayer/business-details?nino=$nino"))
        .willReturn(aResponse().withStatus(status))
    )

}
