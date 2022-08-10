package uk.gov.hmrc.agentclientrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import play.api.test.FakeRequest
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.agentclientrelationships.support.WireMockSupport
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.SessionKeys

trait AuthStub {
  me: WireMockSupport =>

  val oid: String = "556737e15500005500eaf68f"

  def requestIsNotAuthenticated(): AuthStub = {
    stubFor(post(urlEqualTo("/auth/authorise")).willReturn(aResponse().withStatus(401)))
    this
  }

  //VIA Client

  def givenLoginClientIndAll(mtdItId: MtdItId, vrn: Vrn, nino: Nino, cgtRef: CgtRef, pptRef: PptRef, withThisGgUserId: String = "12345-credId") = {
    stubFor(
      post(urlEqualTo("/auth/authorise"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(givenLoginClientIndAllJsonResponseBody(mtdItId, vrn, nino, cgtRef, pptRef, withThisGgUserId))))
    this
  }

  private def givenLoginClientIndAllJsonResponseBody(mtdItId: MtdItId, vrn: Vrn, nino: Nino, cgtRef: CgtRef, pptRef: PptRef, withThisGgUserId: String) =
    s"""
       |{
       |"affinityGroup": "Individual",
       |"authorisedEnrolments": [{
       |  "key": "HMRC-MTD-IT",
       |  "identifiers": [{
       |			"key": "MTDITID",
       |			"value": "${mtdItId.value}"
       |		}],
       |		"state": "Activated"
       |	}, {
       |		"key": "HMRC-MTD-VAT",
       |		"identifiers": [{
       |			"key": "VRN",
       |			"value": "${vrn.value}"
       |		}],
       |		"state": "Activated"
       |	}, {
       |		"key": "HMRC-CGT-PD",
       |		"identifiers": [{
       |			"key": "CGTPDRef",
       |			"value": "${cgtRef.value}"
       |		}],
       |		"state": "Activated"
       |	}, {
       |		"key": "HMRC-PPT-ORG",
       |		"identifiers": [{
       |			"key": "EtmpRegistrationNumber",
       |			"value": "${pptRef.value}"
       |		}],
       |		"state": "Activated"
       |	},
       |  {
       |		"key": "HMRC-NI",
       |		"identifiers": [{
       |			"key": "NINO",
       |			"value": "${nino.value}"
       |		}],
       |		"state": "Activated"
       |	}],
       |  "optionalCredentials": {
       |    "providerId": "$withThisGgUserId",
       |    "providerType": "GovernmentGateway"
       |  }
       |}
       """.stripMargin

  def givenLoginClientBusinessAll(vrn: Vrn, utr: Utr,  urn: Urn, cgtRef: CgtRef, pptRef: PptRef,  withThisGgUserId: String = "12345-credId") = {
    stubFor(
      post(urlEqualTo("/auth/authorise"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(givenLoginClientBusinessAllJsonResponseBody(vrn, utr, urn, cgtRef, pptRef, withThisGgUserId))))
    this
  }

  private def givenLoginClientBusinessAllJsonResponseBody(vrn: Vrn, utr: Utr,  urn: Urn, cgtRef: CgtRef, pptRef: PptRef,  withThisGgUserId: String) =
    s"""
       |{
       |"affinityGroup": "Organisation",
       |"authorisedEnrolments": [{
       |  "key": "HMRC-TERS-ORG",
       |  "identifiers": [{
       |			"key": "SAUTR",
       |			"value": "${utr.value}"
       |		}],
       |		"state": "Activated"
       |	}, {
       |		"key": "HMRC-MTD-VAT",
       |		"identifiers": [{
       |			"key": "VRN",
       |			"value": "${vrn.value}"
       |		}],
       |		"state": "Activated"
       |	}, {
       |		"key": "HMRC-TERSNT-ORG",
       |		"identifiers": [{
       |			"key": "URN",
       |			"value": "${urn.value}"
       |		}],
       |		"state": "Activated"
       |	}, {
       |		"key": "HMRC-PPT-ORG",
       |		"identifiers": [{
       |			"key": "EtmpRegistrationNumber",
       |			"value": "${pptRef.value}"
       |		}],
       |		"state": "Activated"
       |	},
       | {
       |		"key": "HMRC-CGT-PD",
       |		"identifiers": [{
       |			"key": "CGTPDRef",
       |			"value": "${cgtRef.value}"
       |		}],
       |		"state": "Activated"
       |	}],
       |  "optionalCredentials": {
       |    "providerId": "$withThisGgUserId",
       |    "providerType": "GovernmentGateway"
       |  }
       |}
       """.stripMargin

  //VIA Stride

  def givenUserIsAuthenticatedWithStride(strideRole: String, strideUserId: String) = {
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
                         |  "optionalCredentials": {
                         |    "providerId": "$strideUserId",
                         |    "providerType": "PrivilegedApplication"
                         |  }
                         |}
       """.stripMargin)))
    this
  }

  //VIA Agent
  //Applies to Endpoints that allow more than one different kind of user
  def givenUserIsSubscribedAgent(withThisArn: Arn, withThisGgUserId: String = "12345-credId", withThisGroupId: String = "foo", withThisAgentCode: String = "12345"): AuthStub = {
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
                         |  "optionalCredentials": {
                         |    "providerId": "$withThisGgUserId",
                         |    "providerType": "GovernmentGateway"
                         |  },
                         |  "agentCode": "$withThisAgentCode",
                         |  "groupIdentifier": "$withThisGroupId"
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
                         |  "optionalCredentials": {
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
                         |  "optionalCredentials": {
                         |    "providerId": "12345-credId",
                         |    "providerType": "GovernmentGateway"
                         |  }
                         |}
       """.stripMargin)))
    this
  }

  //noinspection ScalaStyle
  //TODO To be replaced with givenLoginClientIndAll OR givenLoginClientBusinessAll TBC
  def givenUserIsSubscribedClient(identifier: TaxIdentifier, withThisGgUserId: String = "12345-credId"): AuthStub = {
    val (service, key, value) = identifier match {
      case Nino(v)    => ("HMRC-NI", "NINO", v)
      case MtdItId(v) => ("HMRC-MTD-IT", "MTDITID", v)
      case Vrn(v)     => ("HMRC-MTD-VAT", "VRN", v)
      case Utr(v)     => ("HMRC-TERS-ORG", "SAUTR", v)
      case Urn(v)     => ("HMRC-TERSNT-ORG", "URN", v)
      case CgtRef(v)  => ("HMRC-CGT-PD", "CGTPDRef", v)
      case PptRef(v)  => ("HMRC-PPT-ORG", "EtmpRegistrationNumber", v)
    }

    stubFor(
      post(urlEqualTo("/auth/authorise"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""
                         |{
                         |"affinityGroup": "${if(key == "URN")"Organisation" else "Individual"}",
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
                         |  "optionalCredentials": {
                         |    "providerId": "$withThisGgUserId",
                         |    "providerType": "GovernmentGateway"
                         |  }
                         |}
       """.stripMargin)))
    this
  }

  def givenAuthorisedAsClient[A](request: FakeRequest[A], mtdItId: MtdItId, vrn: Vrn, utr: Utr, urn: Urn, pptRef: PptRef, cgtRef: CgtRef): FakeRequest[A] = {
    val enrolments =
      Seq(
        Enrolment("HMRC-MTD-IT", "MTDITID", mtdItId.value),
        Enrolment("HMRC-MTD-VAT", "VRN", vrn.value),
        Enrolment("HMRC-TERS-ORG", "SAUTR", utr.value),
        Enrolment("HMRC-TERSNT-ORG", "URN", urn.value),
        Enrolment("HMRC-PPT-ORG", "EtmpRegistrationNumber", pptRef.value),
        Enrolment("HMRC-CGT-PD", "CGTPDRef", cgtRef.value))

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

    request.withHeaders(SessionKeys.authToken -> "Bearer XYZ")
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
    request.withHeaders(SessionKeys.authToken -> "Bearer XYZ")
  }

  def givenUnauthorisedWith(mdtpDetail: String): StubMapping =
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
    request.withHeaders(SessionKeys.authToken -> "Bearer XYZ")
  }

  def givenAuthorisedAsStrideUser[A](request: FakeRequest[A], strideUserId: String): FakeRequest[A] = {
    givenAuthorisedFor(
      s"""
         |{
         |  "authorise": [
         |    { "authProviders": ["PrivilegedApplication"] }
         |  ],
         |  "retrieve":["optionalCredentials"]
         |}
           """.stripMargin,
      s"""
         |{
         |  "optionalCredentials":{
         |    "providerId": "$strideUserId",
         |    "providerType": "PrivilegedApplication"
         |  }
         |}
       """.stripMargin
    )
    request.withHeaders(SessionKeys.authToken -> "Bearer XYZ")
  }

  def givenAuthorisedFor(payload: String, responseBody: String): Seq[StubMapping] = {
    List(stubFor(
      post(urlEqualTo("/auth/authorise"))
        .atPriority(1)
        .withRequestBody(equalToJson(payload, true, true))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(responseBody))),
    stubFor(
      post(urlEqualTo("/auth/authorise"))
        .atPriority(2)
        .willReturn(aResponse()
          .withStatus(401)
          .withHeader("WWW-Authenticate", "MDTP detail=\"InsufficientEnrolments\"")))
    )
  }

  case class Enrolment(serviceName: String, identifierName: String, identifierValue: String)
}
