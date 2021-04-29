package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentrelationships.support.WireMockSupport
import uk.gov.hmrc.domain.Nino


trait ACAStubs {
  me: WireMockSupport =>

  def givenPartialAuthExistsFor(arn: Arn, nino: Nino) =
    stubFor(
      get(urlEqualTo(s"/agent-client-authorisation/agencies/${arn.value}/invitations/sent?status=PartialAuth&clientId=${nino.value}&service=HMRC-MTD-IT"))
        .willReturn(aResponse()
          .withStatus(200)
          .withBody(s"""{
                       |    "_links": {
                       |        "invitations": {
                       |            "href": "/agent-client-authorisation/agencies/${arn.value}/invitations/sent/AX6U1MAPY88GR"
                       |        },
                       |        "self": {
                       |            "href": "/agent-client-authorisation/agencies/${arn.value}/invitations/sent?service=HMRC-MTD-IT&clientId=${nino.value}&status=PartialAuth"
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
                       |                "service": "HMRC-MTD-IT",
                       |                "suppliedClientId": "AB213308A",
                       |                "relationshipEndedBy": null,
                       |                "clientIdType": "MTDITID",
                       |                "arn": "YARN8313176",
                       |                "status": "PartialAuth"
                       |            }
                       |        ]
                       |    }
                       |}""".stripMargin))
    )

  def givenPartialAuthNotExistsFor(arn: Arn, nino: Nino) =
    stubFor(
      get(urlEqualTo(s"/agent-client-authorisation/agencies/${arn.value}/invitations/sent?status=PartialAuth&clientId=${nino.value}&service=HMRC-MTD-IT"))
        .willReturn(aResponse()
          .withStatus(200)
          .withBody(s"""{
                       |    "_links": {
                       |        "invitations": {
                       |            "href": "/agent-client-authorisation/agencies/YARN8313176/invitations/sent/AX6U1MAPY88GR"
                       |        },
                       |        "self": {
                       |            "href": "/agent-client-authorisation/agencies/YARN8313176/invitations/sent?service=HMRC-MTD-IT&clientId=AB213308A&status=PartialAuth"
                       |        }
                       |    },
                       |    "_embedded": {
                       |        "invitations": []
                       |    }
                       |}""".stripMargin))
    )

  def givenAgentClientAuthorisationReturnsError(arn: Arn, nino: Nino, responseCode: Int) =
    stubFor(
      get(urlEqualTo(s"/agent-client-authorisation/agencies/${arn.value}/invitations/sent?status=PartialAuth&clientId=${nino.value}&service=HMRC-MTD-IT"))
        .willReturn(aResponse()
          .withStatus(responseCode))
    )

}
