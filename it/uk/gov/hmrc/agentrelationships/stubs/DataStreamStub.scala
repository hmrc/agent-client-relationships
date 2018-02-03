package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent.AgentClientRelationshipEvent

trait DataStreamStub extends Eventually {

  def verifyAuditRequestSent(count: Int, event: AgentClientRelationshipEvent, tags: Map[String, String] = Map.empty,
                             detail: Map[String, String] = Map.empty) = {
    eventually {
      verify(count, postRequestedFor(urlPathEqualTo(auditUrl))
        .withRequestBody(similarToJson(
          s"""{
             |  "auditSource": "agent-client-relationships",
             |  "auditType": "$event",
             |  "tags": ${Json.toJson(tags)},
             |  "detail": ${Json.toJson(detail)}
             |}"""
        ))
      )
    } (PatienceConfig(scaled(Span(2,Seconds)), scaled(Span(500,Millis))))
  }

  def verifyAuditRequestNotSent(event: AgentClientRelationshipEvent) = {
    eventually {
      verify(0, postRequestedFor(urlPathEqualTo(auditUrl))
        .withRequestBody(similarToJson(
          s"""{
             |  "auditSource": "agent-client-relationships",
             |  "auditType": "$event"
             |}"""
        ))
      )
    } (PatienceConfig(scaled(Span(2,Seconds)), scaled(Span(500,Millis))))
  }

  def givenAuditConnector() = {
    stubFor(post(urlPathEqualTo(auditUrl)).willReturn(aResponse().withStatus(204)))
    stubFor(post(urlPathEqualTo(auditUrl + "/merged")).willReturn(aResponse().withStatus(204)))
  }

  private def auditUrl = "/write/audit"

  private def similarToJson(value: String) = equalToJson(value.stripMargin, true, true)

}
