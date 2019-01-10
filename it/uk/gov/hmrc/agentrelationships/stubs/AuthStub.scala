package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import play.api.test.FakeRequest
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Eori, MtdItId, Vrn}
import uk.gov.hmrc.agentrelationships.support.WireMockSupport
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.SessionKeys

trait AuthStub {
  me: WireMockSupport =>

  val oid: String = "556737e15500005500eaf68f"

  def requestIsNotAuthenticated(): AuthStub = {
    stubFor(post(urlEqualTo("/auth/authorise")).willReturn(aResponse().withStatus(401)))
    this
  }

  def givenUserIsSubscribedAgent(withThisArn: Arn, withThisGgUserId: String = "12345-credId"): AuthStub = {
    stubFor(
      post(urlEqualTo("/auth/authorise"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{
                         |	"affinityGroup": "Agent",
                         |	"allEnrolments": [{
                         |		"key": "HMRC-AS-AGENT",
                         |		"identifiers": [{
                         |			"key": "AgentReferenceNumber",
                         |			"value": "${withThisArn.value}"
                         |		}],
                         |		"state": "Activated"
                         |	}],
                         |  "credentials": {
                         |    "providerId": "$withThisGgUserId",
                         |    "providerType": "GovernmentGateway"
                         |  }
                         |}
       """.stripMargin)))
    this
  }

  def givenUserHasNoAgentEnrolments(arn: Arn, withThisGgUserId: String = "12345-credId"): AuthStub = {
    stubFor(
      post(urlEqualTo("/auth/authorise"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{
                         |	"affinityGroup": "Agent",
                         |	"allEnrolments": [{
                         |		"key": " ",
                         |		"identifiers": [{
                         |			"key": "AgentReferenceNumber",
                         |			"value": "${arn.value}"
                         |		}],
                         |		"state": "Activated"
                         |	}],
                         |  "credentials": {
                         |    "providerId": "$withThisGgUserId",
                         |    "providerType": "GovernmentGateway"
                         |  }
                         |}
       """.stripMargin)))
    this
  }

  def givenUserHasNoClientEnrolments: AuthStub = {
    stubFor(
      post(urlEqualTo("/auth/authorise"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{
                         |	"affinityGroup": "Individual",
                         |	"allEnrolments": [{
                         |		"key": " ",
                         |		"identifiers": [{
                         |			"key": "MTDITID",
                         |			"value": "ID"
                         |		}],
                         |		"state": "Activated"
                         |	}],
                         |  "credentials": {
                         |    "providerId": "12345-credId",
                         |    "providerType": "GovernmentGateway"
                         |  }
                         |}
       """.stripMargin)))
    this
  }

  //noinspection ScalaStyle
  def givenUserIsSubscribedClient(identifier: TaxIdentifier, withThisGgUserId: String = "12345-credId"): AuthStub = {
    val (service, key, value) = identifier match {
      case Nino(v)    => ("HMRC-NI", "NINO", v)
      case MtdItId(v) => ("HMRC-MTD-IT", "MTDITID", v)
      case Vrn(v)     => ("HMRC-MTD-VAT", "VRN", v)
      case Eori(v)    => ("HMRC-NI-ORG", "NIEORI", v)
    }

    stubFor(
      post(urlEqualTo("/auth/authorise"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{
                         |"affinityGroup": "Individual",
                         |"allEnrolments": [{
                         |  "key": "$service",
                         |  "identifiers": [{
                         |			"key": "$key",
                         |			"value": "$value"
                         |		}],
                         |		"state": "Activated"
                         |	}, {
                         |		"key": "HMCE-VAT-AGNT",
                         |		"identifiers": [{
                         |			"key": "AgentRefNo",
                         |			"value": "V3264H"
                         |		}],
                         |		"state": "Activated"
                         |	}, {
                         |		"key": "HMRC-AGENT-AGENT",
                         |		"identifiers": [{
                         |			"key": "AgentRefNumber",
                         |			"value": "JARN1234567"
                         |		}],
                         |		"state": "Activated"
                         |	}, {
                         |		"key": "IR-SA-AGENT",
                         |		"identifiers": [{
                         |			"key": "IRAgentReference",
                         |			"value": "V3264H"
                         |		}],
                         |		"state": "Activated"
                         |	}],
                         |  "credentials": {
                         |    "providerId": "$withThisGgUserId",
                         |    "providerType": "GovernmentGateway"
                         |  }
                         |}
       """.stripMargin)))
    this
  }

  def givenUserIsAuthenticatedWithStride(strideRole: String, strideUserId: String): AuthStub = {
    stubFor(
      post(urlEqualTo("/auth/authorise"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{
                         |"affinityGroup": "Individual",
                         |"allEnrolments": [{
                         |  "key": "$strideRole"
                         |	}],
                         |  "credentials": {
                         |    "providerId": "$strideUserId",
                         |    "providerType": "PrivilegedApplication"
                         |  }
                         |}
       """.stripMargin)))
    this
  }

  def givenAuthorisedAsClientItSa[A](request: FakeRequest[A], mtdItId: String): FakeRequest[A] =
    authenticated(request, Seq(Enrolment("HMRC-MTD-IT", "MTDITID", mtdItId)), isAgent = false)

  def givenAuthorisedAsClientVat[A](request: FakeRequest[A], vrn: String): FakeRequest[A] =
    authenticated(request, Seq(Enrolment("HMRC-MTD-VAT", "VRN", vrn)), isAgent = false)

  def givenAuthorisedAsClient[A](request: FakeRequest[A], mtdItId: MtdItId, vrn: Vrn): FakeRequest[A] = {
    val enrolments =
      Seq(Enrolment("HMRC-MTD-IT", "MTDITID", mtdItId.value), Enrolment("HMRC-MTD-VAT", "VRN", vrn.value))

    givenAuthorisedFor(
      s"""
         |{
         |  "retrieve":["allEnrolments"]
         |}
           """.stripMargin,
      s"""
         |{
         |"allEnrolments": [
         |  ${enrolments
           .map(enrolment =>
             s"""{ "key":"${enrolment.serviceName}", "identifiers": [{"key":"${enrolment.identifierName}", "value": "${enrolment.identifierValue}"}]}""")
           .mkString(", ")}
         |]}
          """.stripMargin
    )

    request.withSession(request.session + SessionKeys.authToken -> "Bearer XYZ")
  }

  def givenAuthorisedAsValidAgent[A](request: FakeRequest[A], arn: String) =
    authenticatedAgent(request, Enrolment("HMRC-AS-AGENT", "AgentReferenceNumber", arn))

  def authenticatedAgent[A](request: FakeRequest[A], enrolment: Enrolment): FakeRequest[A] = {
    givenAuthorisedFor(
      s"""
         |{
         |  "authorise": [
         |    { "identifiers":[], "state":"Activated", "enrolment": "${enrolment.serviceName}" },
         |    { "authProviders": ["GovernmentGateway"] }
         |  ],
         |  "retrieve":["authorisedEnrolments"]
         |}
           """.stripMargin,
      s"""
         |{
         |"authorisedEnrolments": [
         |  { "key":"${enrolment.serviceName}", "identifiers": [
         |    {"key":"${enrolment.identifierName}", "value": "${enrolment.identifierValue}"}
         |  ]}
         |]}
          """.stripMargin
    )
    request.withSession(SessionKeys.authToken -> "Bearer XYZ")
  }

  def givenUnauthorisedWith(mdtpDetail: String): Unit =
    stubFor(
      post(urlEqualTo("/auth/authorise"))
        .willReturn(
          aResponse()
            .withStatus(401)
            .withHeader("WWW-Authenticate", s"""MDTP detail="$mdtpDetail"""")))

  def authenticated[A](request: FakeRequest[A], enrolments: Seq[Enrolment], isAgent: Boolean): FakeRequest[A] = {
    givenAuthorisedFor(
      s"""
         |{
         |  "authorise": [
         |    { "identifiers":[], "state":"Activated", "enrolment": "${enrolments.map(_.serviceName).mkString(",")}" },
         |    { "authProviders": ["GovernmentGateway"] }
         |  ],
         |  "retrieve":["authorisedEnrolments"]
         |}
           """.stripMargin,
      s"""
         |{
         |"authorisedEnrolments": [
         |  ${enrolments
           .map(enrolment =>
             s"""{ "key":"${enrolment.serviceName}", "identifiers": [{"key":"${enrolment.identifierName}", "value": "${enrolment.identifierValue}"}]}""")
           .mkString(", ")}
         |]}
          """.stripMargin
    )
    request.withSession(request.session + SessionKeys.authToken -> "Bearer XYZ")
  }

  def givenAuthorisedAsStrideUser[A](request: FakeRequest[A], strideUserId: String): FakeRequest[A] = {
    givenAuthorisedFor(
      s"""
         |{
         |  "authorise": [
         |    { "authProviders": ["PrivilegedApplication"] }
         |  ],
         |  "retrieve":["credentials"]
         |}
           """.stripMargin,
      s"""
         |{
         |  "credentials":{
         |    "providerId": "$strideUserId",
         |    "providerType": "PrivilegedApplication"
         |  }
         |}
       """.stripMargin
    )
    request.withSession(request.session + SessionKeys.authToken -> "Bearer XYZ")
  }

  def givenAuthorisedFor(payload: String, responseBody: String): Unit = {
    stubFor(
      post(urlEqualTo("/auth/authorise"))
        .atPriority(1)
        .withRequestBody(equalToJson(payload, true, true))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(responseBody)))

    stubFor(
      post(urlEqualTo("/auth/authorise"))
        .atPriority(2)
        .willReturn(aResponse()
          .withStatus(401)
          .withHeader("WWW-Authenticate", "MDTP detail=\"InsufficientEnrolments\"")))
  }

  case class Enrolment(serviceName: String, identifierName: String, identifierValue: String)
}
