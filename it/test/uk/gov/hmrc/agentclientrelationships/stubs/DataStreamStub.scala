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
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{ Millis, Seconds, Span }
import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.model.Invitation

trait DataStreamStub extends Eventually {

  private implicit val patience: PatienceConfig = PatienceConfig(scaled(Span(1, Seconds)), scaled(Span(50, Millis)))

  def verifyCreateInvitationAuditSent(requestPath: String, invitation: Invitation): Unit =
    verifyAuditRequestSent(
      1,
      event = AgentClientRelationshipEvent.CreateInvitation,
      detail = Map(
        "agentReferenceNumber" -> invitation.arn,
        "service" -> invitation.service,
        "clientId" -> invitation.clientId,
        "clientIdType" -> invitation.clientIdType,
        "suppliedClientId" -> invitation.suppliedClientId,
        "invitationId" -> invitation.invitationId),
      tags = Map("transactionName" -> "create-invitation", "path" -> requestPath))

  def verifyRespondToInvitationAuditSent(
    requestPath: String,
    invitation: Invitation,
    accepted: Boolean,
    isStride: Boolean): Unit =
    verifyAuditRequestSent(
      1,
      event = AgentClientRelationshipEvent.RespondToInvitation,
      detail = Map(
        "agentReferenceNumber" -> invitation.arn,
        "service" -> invitation.service,
        "clientId" -> invitation.clientId,
        "clientIdType" -> invitation.clientIdType,
        "suppliedClientId" -> invitation.suppliedClientId,
        "invitationId" -> invitation.invitationId,
        "response" -> (if (accepted) "Accepted" else "Rejected"),
        "respondedBy" -> (if (isStride) "HMRC" else "Client")),
      tags = Map("transactionName" -> "respond-to-invitation", "path" -> requestPath))

  def verifyCreateRelationshipAuditSent(
    requestPath: String,
    arn: String,
    clientId: String,
    clientIdType: String,
    service: String,
    howRelationshipCreated: String,
    enrolmentDelegated: Boolean = true,
    etmpRelationshipCreated: Boolean = true,
    credId: Option[String] = Some("any"),
    agentCode: Option[String] = Some("bar")): Unit =
    verifyAuditRequestSent(
      1,
      event = AgentClientRelationshipEvent.CreateRelationship,
      detail = Map(
        "agentReferenceNumber" -> arn,
        "service" -> service,
        "clientId" -> clientId,
        "clientIdType" -> clientIdType,
        "etmpRelationshipCreated" -> etmpRelationshipCreated.toString,
        "enrolmentDelegated" -> enrolmentDelegated.toString,
        "howRelationshipCreated" -> howRelationshipCreated) ++ Seq(credId.map(id => "credId" -> id), agentCode.map(code => "agentCode" -> code)).flatten,
      tags = Map("transactionName" -> "create-relationship", "path" -> requestPath))

  def verifyTerminateRelationshipAuditSent(
    requestPath: String,
    arn: String,
    clientId: String,
    clientIdType: String,
    service: String,
    howRelationshipTerminated: String,
    enrolmentDeallocated: Boolean = true,
    etmpRelationshipRemoved: Boolean = true,
    credId: Option[String] = Some("any"),
    agentCode: Option[String] = Some("bar")): Unit =
    verifyAuditRequestSent(
      1,
      event = AgentClientRelationshipEvent.TerminateRelationship,
      detail = Map(
        "agentReferenceNumber" -> arn,
        "clientId" -> clientId,
        "clientIdType" -> clientIdType,
        "service" -> service,
        "enrolmentDeallocated" -> enrolmentDeallocated.toString,
        "etmpRelationshipRemoved" -> etmpRelationshipRemoved.toString,
        "howRelationshipTerminated" -> howRelationshipTerminated) ++ Seq(credId.map(id => "credId" -> id), agentCode.map(code => "agentCode" -> code)).flatten,
      tags = Map("transactionName" -> "terminate-relationship", "path" -> requestPath))

  def verifyTerminatePartialAuthAuditSent(
    requestPath: String,
    arn: String,
    clientId: String,
    service: String,
    howRelationshipTerminated: String): Unit =
    verifyAuditRequestSent(
      1,
      event = AgentClientRelationshipEvent.TerminatePartialAuthorisation,
      detail = Map(
        "agentReferenceNumber" -> arn,
        "clientId" -> clientId,
        "clientIdType" -> "nino",
        "service" -> service,
        "howPartialAuthorisationTerminated" -> howRelationshipTerminated),
      tags = Map("transactionName" -> "terminate-partial-auth", "path" -> requestPath))

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
          |}""")))
    }

  def verifyAuditRequestNotSent(event: AgentClientRelationshipEvent) =
    eventually {
      verify(
        0,
        postRequestedFor(urlPathEqualTo(auditUrl))
          .withRequestBody(similarToJson(s"""{
          |  "auditSource": "agent-client-relationships",
          |  "auditType": "$event"
          |}""")))
    }

  def givenAuditConnector() = {
    stubFor(
      post(urlEqualTo("/write/audit/merged"))
        .willReturn(
          aResponse()
            .withStatus(204)))
    stubFor(
      post(urlEqualTo("/write/audit"))
        .willReturn(
          aResponse()
            .withStatus(204)))
  }

  private def auditUrl = "/write/audit"

  private def similarToJson(value: String) = equalToJson(value.stripMargin, true, true)

}
