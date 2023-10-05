package uk.gov.hmrc.agentclientrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent.AgentClientRelationshipEvent

trait DataStreamStub extends Eventually {

  private implicit val patience = PatienceConfig(scaled(Span(25, Seconds)), scaled(Span(500, Millis)))

  def verifyAuditRequestSent(
    count: Int,
    event: AgentClientRelationshipEvent,
    tags: Map[String, String] = Map.empty,
    detail: Map[String, String] = Map.empty) =
    eventually {
      verify(
        count,
        postRequestedFor(urlPathEqualTo(auditUrl))
          .withRequestBody(similarToJson(s"""{
          |  "auditSource": "agent-client-relationships",
          |  "auditType": "$event",
          |  "tags": ${Json.toJson(tags)},
          |  "detail": ${Json.toJson(detail)}
          |}"""))
      )
    }

  def verifyAuditRequestNotSent(event: AgentClientRelationshipEvent) =
    eventually {
      verify(
        0,
        postRequestedFor(urlPathEqualTo(auditUrl))
          .withRequestBody(similarToJson(s"""{
          |  "auditSource": "agent-client-relationships",
          |  "auditType": "$event"
          |}"""))
      )
    }

  def givenAuditConnector() = {
    stubFor(
      post(urlEqualTo("/write/audit/merged"))
        .willReturn(aResponse()
          .withStatus(204)))
    stubFor(
      post(urlEqualTo("/write/audit"))
        .willReturn(aResponse()
          .withStatus(204)))
  }

  private def auditUrl = "/write/audit"

  private def similarToJson(value: String) = equalToJson(value.stripMargin, true, true)

}
