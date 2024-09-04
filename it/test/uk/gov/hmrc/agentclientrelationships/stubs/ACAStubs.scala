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
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentclientrelationships.support.WireMockSupport
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDIT
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}

trait ACAStubs {
  me: WireMockSupport =>

  def givenPartialAuthExistsFor(arn: Arn, nino: Nino, service: String) =
    stubFor(
      get(
        urlEqualTo(
          s"/agent-client-authorisation/agencies/${arn.value}/invitations/sent?status=PartialAuth&clientId=${nino.value}"
        )
      )
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{
                         |    "_links": {
                         |        "invitations": {
                         |            "href": "/agent-client-authorisation/agencies/${arn.value}/invitations/sent/AX6U1MAPY88GR"
                         |        },
                         |        "self": {
                         |            "href": "/agent-client-authorisation/agencies/${arn.value}/invitations/sent?clientId=${nino.value}&status=PartialAuth"
                         |        }
                         |    },
                         |    "_embedded": {
                         |        "invitations": [
                         |            {
                         |                "clientActionUrl": "http://localhost:9448/invitations/personal/7WG22HYK/brady-chartered-accountants--business-a",
                         |                "clientId": "CVOD54828866309",
                         |                "_links": {
                         |                    "self": {
                         |                        "href": "/agent-client-authorisation/agencies/YARN8313176/invitations/sent/AX6U1MAPY88GR"
                         |                    }
                         |                },
                         |                "created": "2021-04-28T18:08:51.786+01:00",
                         |                "origin": "agent-invitations-frontend",
                         |                "suppliedClientIdType": "ni",
                         |                "invitationId": "AX6U1MAPY88GR",
                         |                "isRelationshipEnded": false,
                         |                "detailsForEmail": {
                         |                    "agencyEmail": "rUhmYui@3o.com",
                         |                    "agencyName": "Brady Chartered Accountants & Business A",
                         |                    "clientName": "Stanley"
                         |                },
                         |                "expiryDate": "2021-05-19",
                         |                "lastUpdated": "2021-04-28T18:08:51.786+01:00",
                         |                "clientType": "personal",
                         |                "service": "$service",
                         |                "suppliedClientId": "AB213308A",
                         |                "relationshipEndedBy": null,
                         |                "clientIdType": "MTDITID",
                         |                "arn": "YARN8313176",
                         |                "status": "PartialAuth"
                         |            }
                         |        ]
                         |    }
                         |}""".stripMargin)
        )
    )

  def givenPartialAuthNotExistsFor(arn: Arn, nino: Nino) =
    stubFor(
      get(
        urlEqualTo(
          s"/agent-client-authorisation/agencies/${arn.value}/invitations/sent?status=PartialAuth&clientId=${nino.value}"
        )
      )
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{
                         |    "_links": {
                         |        "invitations": {
                         |            "href": "/agent-client-authorisation/agencies/YARN8313176/invitations/sent/AX6U1MAPY88GR"
                         |        },
                         |        "self": {
                         |            "href": "/agent-client-authorisation/agencies/YARN8313176/invitations/sent?clientId=AB213308A&status=PartialAuth"
                         |        }
                         |    },
                         |    "_embedded": {
                         |        "invitations": []
                         |    }
                         |}""".stripMargin)
        )
    )

  def givenAgentClientAuthorisationReturnsError(arn: Arn, nino: Nino, responseCode: Int) =
    stubFor(
      get(
        urlEqualTo(
          s"/agent-client-authorisation/agencies/${arn.value}/invitations/sent?status=PartialAuth&clientId=${nino.value}"
        )
      )
        .willReturn(
          aResponse()
            .withStatus(responseCode)
        )
    )

  def givenAltItsaUpdate(nino: Nino, service: String = HMRCMTDIT, responseStatus: Int) =
    stubFor(
      put(urlEqualTo(s"/agent-client-authorisation/alt-itsa/$service/update/${nino.value}"))
        .willReturn(aResponse().withStatus(responseStatus))
    )

  def givenSetRelationshipEnded(taxIdentifier: TaxIdentifier, arn: Arn): StubMapping =
    stubFor(
      put(urlEqualTo(s"/agent-client-authorisation/invitations/set-relationship-ended"))
        .withRequestBody(containing(taxIdentifier.value))
        .withRequestBody(containing(arn.value))
        .willReturn(
          aResponse()
            .withStatus(204)
        )
    )

}
