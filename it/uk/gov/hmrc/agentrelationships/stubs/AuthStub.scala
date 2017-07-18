package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import uk.gov.hmrc.agentrelationships.support.WireMockSupport

trait AuthStub {
  me: WireMockSupport =>

  val oid: String = "556737e15500005500eaf68f"

  def requestIsNotAuthenticated(): AuthStub = {
    stubFor(get(urlEqualTo("/auth/authority")).willReturn(aResponse().withStatus(401)))
    this
  }

  def requestIsAuthenticated(): AuthStub = {
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

  def andIsAnAgent(): AuthStub = {
    stubFor(get(urlPathEqualTo(s"/user-details/id/$oid"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             |{
             |  "authProviderId": "12345-credId",
             |  "authProviderType": "GovernmentGateway",
             |  "affinityGroup": "Agent"
             |}
         """.stripMargin)))
    this
  }

  // authProviderId and authProviderType are Options in the UserDetails class in the user-details service, so presumably we shouldn't assume they will always be present
  def andIsAnAgentWithoutAuthProvider(): AuthStub = {
    stubFor(get(urlPathEqualTo(s"/user-details/id/$oid"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             |{
             |  "affinityGroup": "Agent"
             |}
         """.stripMargin)))
    this
  }

  def andIsAnNotAgent(): AuthStub = {
    stubFor(get(urlPathEqualTo(s"/user-details/id/$oid"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             |{
             |  "authProviderId": "12345-credId",
             |  "authProviderType": "GovernmentGateway",
             |  "affinityGroup": "Organisation"
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

  def andHasEnrolments(): AuthStub = {
    stubFor(get(urlPathEqualTo(s"/auth/oid/$oid/enrolments"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             |[{"key": "HMRC-AS-AGENT",
             |  "identifiers": [{"key": "someKey", "value": "someValue"},
             |                  {"key": "someKey", "value": "someValue"}],
             |  "state": "someState"
             |}]
        """.stripMargin)))
    this
  }
}
