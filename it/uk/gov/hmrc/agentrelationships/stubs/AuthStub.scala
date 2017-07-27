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

  def requestIsNotAAgentOrClient(): AuthStub = {
    stubFor(post(urlEqualTo("/auth/authorise")).willReturn(aResponse().withStatus(200)
      .withBody(
        s"""
        {
           |	"affinityGroup": "Agent",
           |	"allEnrolments": [{
           |		"key": "HMRC-sdfsdf-asdasd",
           |		"identifiers": [{
           |			"key": "AgentReferenceNumber",
           |			"value": "123123123"
           |		}],
           |		"state": "Activated"
           |	}, {
           |		"key": "asdasd-asasdasd-IT",
           |		"identifiers": [{
           |			"key": "AgentReferenceNumber",
           |			"value": "123132321"
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
  def givenRequestIsAuthenticated(arn:String,mtdItId:String): AuthStub = {
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
             |			"value": "$arn"
             |		}],
             |		"state": "Activated"
             |	}, {
             |		"key": "HMRC-MTD-IT",
             |		"identifiers": [{
             |			"key": "AgentReferenceNumber",
             |			"value": "$mtdItId"
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
  def writeAuditSucceeds(): Unit = {
    stubFor(post(urlEqualTo("/write/audit"))
      .willReturn(aResponse()
        .withStatus(200)
      ))
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
