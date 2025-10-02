/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.controllers

import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import play.api.http.Status.UNAUTHORIZED
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.await
import play.api.test.Helpers.defaultAwaitTimeout
import uk.gov.hmrc.agentclientrelationships.model.CustomerStatus
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthRelationship
import uk.gov.hmrc.agentclientrelationships.model.Pending
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.stubs.AfiRelationshipStub
import uk.gov.hmrc.agentclientrelationships.stubs.AgentAssuranceStubs
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.verify

import java.time.temporal.ChronoUnit
import java.time.Instant
import java.time.LocalDate

class CustomerStatusControllerISpec
extends BaseControllerISpec
with AfiRelationshipStub
with AgentAssuranceStubs
with HipStub {

  val invitationsRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val partialAuthRepo: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]
  val pendingInvitation: Invitation = Invitation(
    "123",
    arn.value,
    "HMRC-MTD-IT",
    mtdItId.value,
    "MTDITID",
    mtdItId.value,
    "MTDITID",
    "Macrosoft",
    "testAgentName",
    "agent@email.com",
    warningEmailSent = false,
    expiredEmailSent = false,
    Pending,
    None,
    Some("personal"),
    LocalDate.parse("2020-01-01"),
    Instant.now().truncatedTo(ChronoUnit.SECONDS),
    Instant.now().truncatedTo(ChronoUnit.SECONDS)
  )
  val partialAuthRelationship: PartialAuthRelationship = PartialAuthRelationship(
    Instant.now().truncatedTo(ChronoUnit.SECONDS),
    arn.value,
    "HMRC-MTD-IT",
    nino.value,
    active = true,
    Instant.now().truncatedTo(ChronoUnit.SECONDS)
  )
  val inactivePartialAuthRelationship: PartialAuthRelationship = partialAuthRelationship.copy(active = false)

  ".customerStatus" should {

    lazy val request = FakeRequest("GET", "/agent-client-relationships/customer-status")

    "Cache results of existing customer relationships and do not call ETMP if record exists in Cache" in {
      givenAuditConnector()
      givenAuthorisedItsaClientWithNino(
        request,
        mtdItId,
        nino
      )
      getActiveRelationshipFailsWith(mtdItId, NOT_FOUND)
      givenAfiRelationshipForClientNotFound(nino.value)
      val expectedBody = Json.toJson(
        CustomerStatus(
          hasPendingInvitations = false,
          hasInvitationsHistory = false,
          hasExistingRelationships = false
        )
      )

      val firstCall = doGetRequest(request.uri)
      firstCall.status shouldBe OK
      firstCall.json shouldBe expectedBody

      val secondCall = doGetRequest(request.uri)
      secondCall.status shouldBe OK
      secondCall.json shouldBe expectedBody

      verify(
        1,
        getRequestedFor(urlEqualTo(
          relationshipHipUrl(
            taxIdentifier = mtdItId,
            authProfileOption = Some("ALL00001")
          )
        ))
      )
    }

    "return 200 and the expected customer status JSON body" when {

      "there are pending invitations and an active relationship from partial auth" in {
        givenAuditConnector()
        givenAuthorisedItsaClientWithNino(
          request,
          mtdItId,
          nino
        )
        givenAfiRelationshipForClientNotFound(nino.value)
        givenAgentRecordFound(arn, existingAgentRecordResponse)
        await(invitationsRepo.collection.insertOne(pendingInvitation).toFuture())
        await(partialAuthRepo.collection.insertOne(partialAuthRelationship).toFuture())
        val expectedBody = Json.toJson(
          CustomerStatus(
            hasPendingInvitations = true,
            hasInvitationsHistory = true,
            hasExistingRelationships = true
          )
        )

        val result = doGetRequest(request.uri)
        result.status shouldBe OK
        result.json shouldBe expectedBody
      }

      "there are pending invitations and an existing relationship from HODs" in {
        givenAuditConnector()
        givenAuthorisedItsaClientWithNino(
          request,
          mtdItId,
          nino
        )
        getActiveRelationshipsViaClient(mtdItId, arn)
        givenAfiRelationshipForClientNotFound(nino.value)
        givenAgentRecordFound(arn, existingAgentRecordResponse)
        await(invitationsRepo.collection.insertOne(pendingInvitation).toFuture())
        val expectedBody = Json.toJson(
          CustomerStatus(
            hasPendingInvitations = true,
            hasInvitationsHistory = true,
            hasExistingRelationships = true
          )
        )

        val result = doGetRequest(request.uri)
        result.status shouldBe OK
        result.json shouldBe expectedBody
      }

      "there are pending invitations and no existing relationship" in {
        givenAuditConnector()
        givenAuthorisedItsaClientWithNino(
          request,
          mtdItId,
          nino
        )
        getActiveRelationshipFailsWith(mtdItId, NOT_FOUND)
        givenAfiRelationshipForClientNotFound(nino.value)
        givenAgentRecordFound(arn, existingAgentRecordResponse)
        await(invitationsRepo.collection.insertOne(pendingInvitation).toFuture())
        val expectedBody = Json.toJson(
          CustomerStatus(
            hasPendingInvitations = true,
            hasInvitationsHistory = true,
            hasExistingRelationships = false
          )
        )

        val result = doGetRequest(request.uri)
        result.status shouldBe OK
        result.json shouldBe expectedBody
      }

      "there is a pending invitation from a suspended agent and no existing relationships" in {
        givenAuditConnector()
        givenAuthorisedItsaClientWithNino(
          request,
          mtdItId,
          nino
        )
        getActiveRelationshipFailsWith(mtdItId, NOT_FOUND)
        givenAfiRelationshipForClientNotFound(nino.value)
        givenAgentRecordFound(arn, suspendedAgentRecordResponse)
        await(invitationsRepo.collection.insertOne(pendingInvitation).toFuture())
        val expectedBody = Json.toJson(
          CustomerStatus(
            hasPendingInvitations = false,
            hasInvitationsHistory = false,
            hasExistingRelationships = false
          )
        )

        val result = doGetRequest(request.uri)
        result.status shouldBe OK
        result.json shouldBe expectedBody
      }

      "there are no pending invitations and an active relationship from partial auth" in {
        givenAuditConnector()
        givenAuthorisedItsaClientWithNino(
          request,
          mtdItId,
          nino
        )
        givenAfiRelationshipForClientNotFound(nino.value)
        await(partialAuthRepo.collection.insertOne(partialAuthRelationship).toFuture())
        val expectedBody = Json.toJson(
          CustomerStatus(
            hasPendingInvitations = false,
            hasInvitationsHistory = true,
            hasExistingRelationships = true
          )
        )

        val result = doGetRequest(request.uri)
        result.status shouldBe OK
        result.json shouldBe expectedBody
      }

      "there are no pending invitations, inactive partial auth and an existing relationship from HODs" in {
        givenAuditConnector()
        givenAuthorisedItsaClientWithNino(
          request,
          mtdItId,
          nino
        )
        getActiveRelationshipsViaClient(mtdItId, arn)
        givenAfiRelationshipForClientNotFound(nino.value)
        await(partialAuthRepo.collection.insertOne(inactivePartialAuthRelationship).toFuture())
        val expectedBody = Json.toJson(
          CustomerStatus(
            hasPendingInvitations = false,
            hasInvitationsHistory = true,
            hasExistingRelationships = true
          )
        )

        val result = doGetRequest(request.uri)
        result.status shouldBe OK
        result.json shouldBe expectedBody
      }

      "there are no pending invitations, no partial auth history and an existing relationship from HODs" in {
        givenAuditConnector()
        givenAuthorisedItsaClientWithNino(
          request,
          mtdItId,
          nino
        )
        givenAfiRelationshipForClientNotFound(nino.value)
        getActiveRelationshipsViaClient(mtdItId, arn)
        val expectedBody = Json.toJson(
          CustomerStatus(
            hasPendingInvitations = false,
            hasInvitationsHistory = false,
            hasExistingRelationships = true
          )
        )

        val result = doGetRequest(request.uri)
        result.status shouldBe OK
        result.json shouldBe expectedBody
      }

      "there are no pending invitations, no partial auth history and an existing IRV relationship" in {
        givenAuditConnector()
        givenAuthorisedItsaClientWithNino(
          request,
          mtdItId,
          nino
        )
        givenAfiRelationshipForClientIsActive(
          arn,
          "PERSONAL-INCOME-RECORD",
          nino.value,
          true
        )
        getActiveRelationshipFailsWith(mtdItId, NOT_FOUND)
        val expectedBody = Json.toJson(
          CustomerStatus(
            hasPendingInvitations = false,
            hasInvitationsHistory = false,
            hasExistingRelationships = true
          )
        )

        val result = doGetRequest(request.uri)
        result.status shouldBe OK
        result.json shouldBe expectedBody
      }

      "there are no pending invitations, inactive partial auth and no existing relationships" in {
        givenAuditConnector()
        givenAuthorisedItsaClientWithNino(
          request,
          mtdItId,
          nino
        )
        getActiveRelationshipFailsWith(mtdItId, NOT_FOUND)
        givenAfiRelationshipForClientNotFound(nino.value)
        await(partialAuthRepo.collection.insertOne(inactivePartialAuthRelationship).toFuture())
        val expectedBody = Json.toJson(
          CustomerStatus(
            hasPendingInvitations = false,
            hasInvitationsHistory = true,
            hasExistingRelationships = false
          )
        )

        val result = doGetRequest(request.uri)
        result.status shouldBe OK
        result.json shouldBe expectedBody
      }

      "return 401 when the request is not authorised as a client" in {
        givenAuthorisedAsValidAgent(request, arn.value)
        val result = doGetRequest(request.uri)
        result.status shouldBe UNAUTHORIZED
      }
    }

  }

}
