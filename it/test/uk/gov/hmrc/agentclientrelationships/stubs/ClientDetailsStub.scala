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

trait ClientDetailsStub {

  def givenItsaDesignatoryDetailsExists(nino: String): StubMapping =
    stubFor(
      get(urlEqualTo(s"/citizen-details/$nino/designatory-details"))
        .willReturn(
          aResponse()
            .withBody(s"""
                         |{
                         |  "address": {
                         |    "postcode": "AA1 1AA"
                         |  }
                         |}
          """.stripMargin)))

  def givenItsaDesignatoryDetailsError(nino: String, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(s"/citizen-details/$nino/designatory-details"))
        .willReturn(aResponse().withStatus(status)))

  def givenItsaCitizenDetailsExists(nino: String): StubMapping =
    stubFor(
      get(urlEqualTo(s"/citizen-details/nino/$nino"))
        .willReturn(
          aResponse()
            .withBody(s"""
                         |{
                         |  "name": {
                         |    "current": {
                         |      "firstName": "Matthew",
                         |      "lastName": "Kovacic"
                         |    }
                         |  },
                         |  "dateOfBirth": "01012000",
                         |  "ids": {
                         |    "sautr": "11223344"
                         |  }
                         |}
          """.stripMargin)))

  def givenItsaCitizenDetailsError(nino: String, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(s"/citizen-details/nino/$nino"))
        .willReturn(aResponse().withStatus(status)))

  def givenVatCustomerInfoExists(vrn: String): StubMapping =
    stubFor(
      get(urlEqualTo(s"/vat/customer/vrn/$vrn/information"))
        .willReturn(
          aResponse()
            .withBody(s"""
                         |{
                         |  "approvedInformation": {
                         |    "customerDetails": {
                         |      "organisationName": "CFG",
                         |      "tradingName": "CFG Solutions",
                         |      "individual": {
                         |        "title": "0001",
                         |        "firstName": "Ilkay",
                         |        "middleName": "Silky",
                         |        "lastName": "Gundo"
                         |      },
                         |      "effectiveRegistrationDate": "2020-01-01",
                         |      "isInsolvent": false,
                         |      "overseasIndicator": true
                         |    }
                         |  }
                         |}
          """.stripMargin)))

  def givenVatCustomerInfoError(vrn: String, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(s"/vat/customer/vrn/$vrn/information"))
        .willReturn(aResponse().withStatus(status)))

  def givenTrustDetailsExist(identifier: String, identifierType: String): StubMapping =
    stubFor(
      get(urlEqualTo(s"/trusts/agent-known-fact-check/$identifierType/$identifier"))
        .willReturn(
          aResponse()
            .withBody(s"""
                         |{
                         |  "trustDetails": {
                         |    "trustName": "The Safety Trust"
                         |  }
                         |}
            """.stripMargin)))

  def givenTrustDetailsError(identifier: String, identifierType: String, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(s"/trusts/agent-known-fact-check/$identifierType/$identifier"))
        .willReturn(aResponse().withStatus(status)))

  def givenCgtDetailsExist(cgtRef: String): StubMapping =
    stubFor(
      get(urlEqualTo(s"/subscriptions/CGT/ZCGT/$cgtRef"))
        .willReturn(
          aResponse()
            .withBody(s"""
                         |{
                         |  "subscriptionDetails": {
                         |    "typeOfPersonDetails": {
                         |      "typeOfPerson": "Trustee",
                         |      "organisationName": "CFG Solutions"
                         |    },
                         |    "addressDetails": {
                         |      "postalCode": "AA1 1AA",
                         |      "countryCode": "GB"
                         |    }
                         |  }
                         |}
            """.stripMargin)))

  def givenCgtDetailsError(cgtRef: String, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(s"/subscriptions/CGT/ZCGT/$cgtRef"))
        .willReturn(aResponse().withStatus(status)))

  def givenPptDetailsExist(pptRef: String): StubMapping =
    stubFor(
      get(urlEqualTo(s"/plastic-packaging-tax/subscriptions/PPT/$pptRef/display"))
        .willReturn(
          aResponse()
            .withBody(s"""
                         |{
                         |  "legalEntityDetails": {
                         |    "dateOfApplication": "2020-01-01",
                         |    "customerDetails": {
                         |      "customerType": "Organisation",
                         |      "organisationDetails": {
                         |        "organisationName": "CFG Solutions"
                         |      }
                         |    }
                         |  },
                         |  "changeOfCircumstanceDetails": {
                         |    "deregistrationDetails": {
                         |      "deregistrationDate": "2030-01-01"
                         |    }
                         |  }
                         |}
            """.stripMargin)))

  def givenPptDetailsError(pptRef: String, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(s"/plastic-packaging-tax/subscriptions/PPT/$pptRef/display"))
        .willReturn(aResponse().withStatus(status)))

  def givenCbcDetailsExist(isGBUser: Boolean = true): StubMapping =
    stubFor(
      post(urlEqualTo("/dac6/dct50d/v1"))
        .willReturn(
          aResponse()
            .withBody(s"""{
                         |  "displaySubscriptionForCBCResponse": {
                         |    "responseDetail": {
                         |      "isGBUser": $isGBUser,
                         |      "tradingName": "CFG Solutions",
                         |      "primaryContact": [
                         |        {
                         |          "email": "test@email.com",
                         |          "individual": {
                         |            "firstName": "Erling",
                         |            "lastName": "Haal"
                         |          },
                         |          "organisation": {
                         |            "organisationName": "CFG"
                         |          }
                         |        }
                         |      ],
                         |      "secondaryContact": [
                         |        {
                         |          "email": "test2@email.com",
                         |          "individual": {
                         |            "firstName": "Kevin",
                         |            "lastName": "De Burner"
                         |          },
                         |          "organisation": {
                         |            "organisationName": "CFG"
                         |          }
                         |        }
                         |      ]
                         |    }
                         |  }
                         |}
            """.stripMargin)))

  def givenCbcDetailsError(status: Int): StubMapping =
    stubFor(
      post(urlEqualTo("/dac6/dct50d/v1"))
        .willReturn(aResponse().withStatus(status)))

  def givenPillar2DetailsExist(plrId: String): StubMapping =
    stubFor(
      get(urlEqualTo(s"/pillar2/subscription/$plrId"))
        .willReturn(
          aResponse()
            .withBody(s"""{
                         |  "success": {
                         |    "upeDetails": {
                         |      "organisationName": "CFG Solutions",
                         |      "registrationDate": "2020-01-01"
                         |    },
                         |    "upeCorrespAddressDetails": {
                         |      "countryCode": "GB"
                         |    },
                         |    "accountStatus": {
                         |      "inactive": true
                         |    }
                         |  }
                         |}
            """.stripMargin)))

  def givenPillar2DetailsError(plrId: String, status: Int): StubMapping =
    stubFor(
      get(urlEqualTo(s"/pillar2/subscription/$plrId"))
        .willReturn(aResponse().withStatus(status)))
}
