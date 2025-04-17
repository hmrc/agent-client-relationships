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

trait IfStub {
  def givenReturnsServerError(): StubMapping =
    stubFor(
      any(urlMatching(s"/registration/.*"))
        .willReturn(aResponse().withStatus(500))
    )

  def givenReturnsServiceUnavailable(): StubMapping =
    stubFor(
      any(urlMatching(s"/registration/.*"))
        .willReturn(aResponse().withStatus(503))
    )

  def givenAgentCanBeAllocated(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
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

  def givenAgentCanNotBeAllocated(status: Int): StubMapping =
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing("\"Authorise\""))
        .willReturn(
          aResponse()
            .withStatus(status)
            .withBody(s"""{"reason": "Service unavailable"}""")
        )
    )

  def givenAgentCanBeDeallocated(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
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

  def givenAgentHasNoActiveRelationship(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
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

  def givenAgentCanNotBeDeallocated(status: Int): StubMapping =
    stubFor(
      post(urlEqualTo(s"/registration/relationship"))
        .withRequestBody(containing("\"De-Authorise\""))
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
          relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = Some(authProfile), activeOnly = true)
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
          relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = Some(authProfile), activeOnly = true)
        )
      )
        .willReturn(
          aResponse()
            .withStatus(403)
            .withBody("AGENT_SUSPENDED")
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
          relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = Some(authProfile), activeOnly = true)
        )
      )
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

  def getAllActiveRelationshipsViaClient(
    taxIdentifier: TaxIdentifier,
    arn: Arn,
    activeOnly: Boolean = true
  ): StubMapping =
    stubFor(
      get(
        urlEqualTo(relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = None, activeOnly = activeOnly))
      )
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

  def getAllInactiveRelationshipsViaClient(
    taxIdentifier: TaxIdentifier,
    arn: Arn,
    activeOnly: Boolean = true
  ): StubMapping =
    stubFor(
      get(
        urlEqualTo(relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = None, activeOnly = activeOnly))
      )
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
      get(
        urlEqualTo(relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = None, activeOnly = activeOnly))
      )
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{
                         |"relationship" :[
                         |{
                         |  "referenceNumber" : "${taxIdentifier.value}",
                         |  "agentReferenceNumber" : "${arnMain.value}",
                         |  "organisation" : {
                         |    "organisationName": "someOrganisationName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "dateTo" : "9999-12-31",
                         |  "contractAccountCategory" : "01",
                         |  "authProfile" : "ALL00001",
                         |  "activity" : "09"
                         |},
                         |{
                         |  "referenceNumber" : "${taxIdentifier.value}",
                         |  "agentReferenceNumber" : "${arnSup.value}",
                         |  "organisation" : {
                         |    "organisationName": "someOrganisationName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "dateTo" : "9999-12-31",
                         |  "contractAccountCategory" : "01",
                         |  "authProfile" : "ITSAS001",
                         |  "activity" : "09"
                         |}
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
      get(
        urlEqualTo(relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = None, activeOnly = activeOnly))
      )
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{
                         |"relationship" :[
                         |{
                         |  "referenceNumber" : "${taxIdentifier.value}",
                         |  "agentReferenceNumber" : "${arnMain.value}",
                         |  "organisation" : {
                         |    "organisationName": "someOrganisationName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "dateTo" : "9999-12-31",
                         |  "contractAccountCategory" : "01",
                         |  "authProfile" : "ALL00001",
                         |  "activity" : "09"
                         |},
                         |{
                         |  "referenceNumber" : "${taxIdentifier.value}",
                         |  "agentReferenceNumber" : "${arnSup.value}",
                         |  "organisation" : {
                         |    "organisationName": "someOrganisationName"
                         |  },
                         |  "dateFrom" : "2015-09-10",
                         |  "dateTo" : "1900-12-31",
                         |  "contractAccountCategory" : "01",
                         |  "authProfile" : "ITSAS001",
                         |  "activity" : "09"
                         |}
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
      get(
        urlEqualTo(relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = None, activeOnly = activeOnly))
      )
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
      get(
        urlEqualTo(relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = None, activeOnly = activeOnly))
      )
        .willReturn(
          aResponse()
            .withStatus(status)
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
        getRequestedFor(
          urlEqualTo(
            relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = None, activeOnly = activeOnly)
          )
        )
      )
    }

  def getAllActiveRelationshipFailsWithSuspended(
    taxIdentifier: TaxIdentifier,
    activeOnly: Boolean = true
  ): StubMapping =
    stubFor(
      get(
        urlEqualTo(relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = None, activeOnly = activeOnly))
      )
        .willReturn(
          aResponse()
            .withStatus(403)
            .withBody("AGENT_SUSPENDED")
        )
    )

  def getInactiveRelationshipViaClient(
    taxIdentifier: TaxIdentifier,
    agentArn: String,
    authProfile: String = "ALL00001"
  ): StubMapping =
    stubFor(
      get(
        urlEqualTo(
          relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = Some(authProfile), activeOnly = false)
        )
      )
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

  def getSomeActiveRelationshipsViaClient(
    taxIdentifier: TaxIdentifier,
    agentArn1: String,
    agentArn2: String,
    agentArn3: String,
    authProfile: String = "ALL00001"
  ): StubMapping =
    stubFor(
      get(
        urlEqualTo(
          relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = Some(authProfile), activeOnly = true)
        )
      )
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
      get(
        urlEqualTo(
          relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = Some(authProfile), activeOnly = false)
        )
      )
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

  def getNoInactiveRelationshipsForClient(
    taxIdentifier: TaxIdentifier,
    authProfile: String = "ALL00001"
  ): StubMapping =
    stubFor(
      get(
        urlEqualTo(
          relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = Some(authProfile), activeOnly = false)
        )
      )
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

  def getFailInactiveRelationshipsForClient(
    taxIdentifier: TaxIdentifier,
    status: Int,
    body: Option[String] = None,
    authProfile: String = "ALL00001"
  ): StubMapping =
    stubFor(
      get(
        urlEqualTo(
          relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = Some(authProfile), activeOnly = false)
        )
      )
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
            .withBody("AGENT_SUSPENDED")
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

  private def relationshipIFUrl(
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
        s"&from=$from&to=$now"
      }

    val authProfileParam = authProfileOption.map(x => s"auth-profile=$x").getOrElse("")

    taxIdentifier match {
      case MtdItId(mtdItId) =>
        s"/registration/relationship?referenceNumber=$mtdItId&agent=false&active-only=$activeOnly&regime=ITSA$dateRangeParams&relationship=ZA01&$authProfileParam"
      case Vrn(vrn) =>
        s"/registration/relationship?idType=VRN&referenceNumber=$vrn&agent=false&active-only=$activeOnly&regime=VATC$dateRangeParams&relationship=ZA01&$authProfileParam"
      case Utr(utr) =>
        s"/registration/relationship?idType=UTR&referenceNumber=$utr&agent=false&active-only=$activeOnly&regime=TRS$dateRangeParams"
      case Urn(urn) =>
        s"/registration/relationship?idType=URN&referenceNumber=$urn&agent=false&active-only=$activeOnly&regime=TRS$dateRangeParams"
      case CgtRef(ref) =>
        s"/registration/relationship?idType=ZCGT&referenceNumber=$ref&agent=false&active-only=$activeOnly&regime=CGT$dateRangeParams&relationship=ZA01&$authProfileParam"
      case PptRef(ref) =>
        s"/registration/relationship?idType=ZPPT&referenceNumber=$ref&agent=false&active-only=$activeOnly&regime=PPT$dateRangeParams&relationship=ZA01&$authProfileParam"
      case CbcId(ref) =>
        s"/registration/relationship?idType=CBC&referenceNumber=$ref&agent=false&active-only=$activeOnly&regime=CBC$dateRangeParams"
      case PlrId(ref) =>
        s"/registration/relationship?idType=ZPLR&referenceNumber=$ref&agent=false&active-only=$activeOnly&regime=PLR$dateRangeParams"
      case x => throw new IllegalArgumentException(s"Tax identifier not supported $x")
    }

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

  def givenNinoIsKnownFor(mtdId: MtdItId, nino: Nino): StubMapping =
    stubFor(
      get(urlEqualTo(s"/registration/business-details/mtdId/${mtdId.value}"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"taxPayerDisplayResponse":{"nino": "${nino.value}" }}""")
        )
    )

  def givenNinoIsUnknownFor(mtdId: MtdItId): StubMapping =
    stubFor(
      get(urlEqualTo(s"/registration/business-details/mtdId/${mtdId.value}"))
        .willReturn(aResponse().withStatus(404))
    )

  def givenmtdIdIsInvalid(mtdId: MtdItId): StubMapping =
    stubFor(
      get(urlMatching(s"/registration/.*?/mtdId/${mtdId.value}"))
        .willReturn(aResponse().withStatus(400))
    )

  def givenMtdItIdIsKnownFor(nino: Nino, mtdId: MtdItId): StubMapping =
    stubFor(
      get(urlEqualTo(s"/registration/business-details/nino/${nino.value}"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"taxPayerDisplayResponse":{"mtdId": "${mtdId.value}" }}""")
        )
    )

  def givenMtdItIdIsUnKnownFor(nino: Nino): StubMapping =
    stubFor(
      get(urlEqualTo(s"/registration/business-details/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(404))
    )

  def givenNinoIsInvalid(nino: Nino): StubMapping =
    stubFor(
      get(urlMatching(s"/registration/.*?/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(400))
    )
}
