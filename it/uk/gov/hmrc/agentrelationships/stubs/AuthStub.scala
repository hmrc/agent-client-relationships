package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.agentrelationships.support.WireMockSupport

trait AuthStub {
  me: WireMockSupport =>

  val oid: String = "556737e15500005500eaf68f"

  def requestIsNotAuthenticated(): AuthStub = {
    stubFor(post(urlEqualTo("/auth/authorise")).willReturn(aResponse().withStatus(401)))
    this
  }

  def writeAuditSucceeds(): Unit = {
    stubFor(post(urlEqualTo("/write/audit"))
      .willReturn(aResponse()
        .withStatus(200)
      ))
  }


  def givenUserIsSubscribedAgent(arn: Arn): AuthStub = {
    stubFor(post(urlEqualTo("/auth/authorise"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             |{
             |	"affinityGroup": "Agent",
             |	"allEnrolments": [{
             |		"key": "HMRC-AS-AGENT",
             |		"identifiers": [{
             |			"key": "AgentReferenceNumber",
             |			"value": "${arn.value}"
             |		}],
             |		"state": "Activated"
             |	}]
             |}
       """.stripMargin)))
    this
  }

  def givenUserHasNoAgentEnrolments(arn: Arn): AuthStub = {
    stubFor(post(urlEqualTo("/auth/authorise"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             |{
             |	"affinityGroup": "Agent",
             |	"allEnrolments": [{
             |		"key": " ",
             |		"identifiers": [{
             |			"key": "AgentReferenceNumber",
             |			"value": "${arn.value}"
             |		}],
             |		"state": "Activated"
             |	}]
             |}
       """.stripMargin)))
    this
  }

  def givenUserHasNoClientEnrolments: AuthStub = {
    stubFor(post(urlEqualTo("/auth/authorise"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             |{
             |	"affinityGroup": "Individual",
             |	"allEnrolments": [{
             |		"key": " ",
             |		"identifiers": [{
             |			"key": "MTDITID",
             |			"value": "ID"
             |		}],
             |		"state": "Activated"
             |	}]
             |}
       """.stripMargin)))
    this
  }

  def givenUserIsSubscribedClient(mtdItId: MtdItId): AuthStub = {
    stubFor(post(urlEqualTo("/auth/authorise"))
      .willReturn(aResponse()
        .withStatus(200)
        .withBody(
          s"""
             |{
             |"affinityGroup": "Individual",
             |"allEnrolments": [{
             |  "key": "HMRC-MTD-IT",
             |  "identifiers": [{
             |			"key": "MTDITID",
             |			"value": "${mtdItId.value}"
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
             |	}]
             |}
       """.stripMargin)))
    this
  }
}