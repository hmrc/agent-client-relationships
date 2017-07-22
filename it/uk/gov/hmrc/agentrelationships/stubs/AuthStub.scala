package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.agentrelationships.support.WireMockSupport

trait AuthStub {
  me: WireMockSupport =>

  val oid: String = "556737e15500005500eaf68f"

  def requestIsNotAuthenticated(): AuthStub = {
    stubFor(get(urlEqualTo("/auth/authority")).willReturn(aResponse().withStatus(401)))
    this
  }

  def givenRequestIsAuthenticated(): AuthStub = {
    stubFor(get(urlEqualTo("/auth/authority"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             |{
             |  "new-session":"/auth/oid/$oid/session",
             |  "enrolments":"/auth/oid/$oid/enrolments",
             |  "uri":"/auth/oid/$oid",
             |  "loggedInAt":"2016-06-20T10:44:29.634Z",
             |  "credentials":{
             |    "gatewayId":"0000001234567890"
             |  },
             |  "accounts":{
             |  },
             |  "lastUpdated":"2016-06-20T10:44:29.634Z",
             |  "credentialStrength":"strong",
             |  "confidenceLevel":50,
             |  "userDetailsLink":"$wireMockBaseUrl/user-details/id/$oid",
             |  "levelOfAssurance":"1",
             |  "previouslyLoggedInAt":"2016-06-20T09:48:37.112Z"
             |}
       """.stripMargin)))
    this
  }

  def andHasNoEnrolments(): AuthStub = {
    stubFor(get(urlPathEqualTo(s"/auth/oid/$oid/enrolments"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             |[]
        """.stripMargin)))
    this
  }

  def andAgentClientAreSubscribed(arn: Arn, mtdItId: MtdItId): AuthStub = {
    stubFor(get(urlPathEqualTo(s"/auth/oid/$oid/enrolments"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             |[{"key": "HMRC-AS-AGENT",
             |  "identifiers": [{"key": "AgentReferenceNumber", "value": "${arn.value}"},
             |                  {"key": "AnotherIdentifier", "value": "Not Arn"}],
             |  "state": "Activated"
             |},
             |{"key": "HMRC-MTD-IT",
             |  "identifiers": [{"key": "AgentReferenceNumber", "value": "${mtdItId.value}"},
             |                  {"key": "AnotherIdentifier", "value": "Not MtdItId"}],
             |  "state": "Activated"
             |}]
        """.stripMargin)))
    this
  }

  def andAgentIsSubscribedClientIsNotSubscribed(arn: Arn): AuthStub = {
    stubFor(get(urlPathEqualTo(s"/auth/oid/$oid/enrolments"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             |[{"key": "HMRC-AS-AGENT",
             |  "identifiers": [{"key": "AgentReferenceNumber", "value": "${arn.value}"},
             |                  {"key": "AnotherIdentifier", "value": "Not Arn"}],
             |  "state": "Activated"
             |},
             |{"key": "HMRC-MTD-IT",
             |  "identifiers": [{"key": "AnotherIdentifier", "value": "Not MtdItId"},
             |                  {"key": "AnotherIdentifier", "value": "Not MtdItId"}],
             |  "state": "Activated"
             |}]
        """.stripMargin)))
    this
  }

  def andAgentNotSubscribedClientIsSubscribed(mtdItId: MtdItId): AuthStub = {
    stubFor(get(urlPathEqualTo(s"/auth/oid/$oid/enrolments"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             |[{"key": "HMRC-AS-AGENT",
             |  "identifiers": [{"key": "AnotherIdentifier", "value": "Not Arn"},
             |                  {"key": "AnotherIdentifier", "value": "Not Arn"}],
             |  "state": "Activated"
             |},
             |{"key": "HMRC-MTD-IT",
             |  "identifiers": [{"key": "AgentReferenceNumber", "value": "${mtdItId.value}"},
             |                  {"key": "AnotherIdentifier", "value": "Not MtdItId"}],
             |  "state": "Activated"
             |}]
        """.stripMargin)))
    this
  }

  def andAgentClientNotSubscribed(): AuthStub = {
    stubFor(get(urlPathEqualTo(s"/auth/oid/$oid/enrolments"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             |[{"key": "HMRC-AS-AGENT",
             |  "identifiers": [{"key": "AnotherIdentifier", "value": "Not Arn"},
             |                  {"key": "AnotherIdentifier", "value": "Not Arn"}],
             |  "state": "Activated"
             |},
             |{"key": "HMRC-MTD-IT",
             |  "identifiers": [{"key": "AnotherIdentifier", "value": "Not MtdItId"},
             |                  {"key": "AnotherIdentifier", "value": "Not MtdItId"}],
             |  "state": "Activated"
             |}]
        """.stripMargin)))
    this
  }
}
