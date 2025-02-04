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

import play.api.http.Status.{NOT_FOUND, OK, UNAUTHORIZED}
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.agentclientrelationships.model.{CustomerStatus, Invitation, PartialAuthRelationship, Pending}
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentclientrelationships.stubs.HIPAgentClientRelationshipStub

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate}

class CustomerStatusControllerISpec extends BaseControllerISpec with HIPAgentClientRelationshipStub {

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

    "return 200 and the expected customer status JSON body" when {

      "there are pending invitations and an active relationship from partial auth" in {
        givenAuditConnector()
        givenAuthorisedItsaClientWithNino(request, mtdItId, nino)
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
        givenAuthorisedItsaClientWithNino(request, mtdItId, nino)
        getActiveRelationshipsViaClient(mtdItId, arn)
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
        givenAuthorisedItsaClientWithNino(request, mtdItId, nino)
        getActiveRelationshipFailsWith(mtdItId, NOT_FOUND)
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

      "there are no pending invitations and an active relationship from partial auth" in {
        givenAuditConnector()
        givenAuthorisedItsaClientWithNino(request, mtdItId, nino)
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
        givenAuthorisedItsaClientWithNino(request, mtdItId, nino)
        getActiveRelationshipsViaClient(mtdItId, arn)
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
        givenAuthorisedItsaClientWithNino(request, mtdItId, nino)
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

      "there are no pending invitations, inactive partial auth and no existing relationships" in {
        givenAuditConnector()
        givenAuthorisedItsaClientWithNino(request, mtdItId, nino)
        getActiveRelationshipFailsWith(mtdItId, NOT_FOUND)
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

      "there are no pending invitations, no partial auth history and no existing relationships" in {
        givenAuditConnector()
        givenAuthorisedItsaClientWithNino(request, mtdItId, nino)
        getActiveRelationshipFailsWith(mtdItId, NOT_FOUND)
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
    }

    "return 401 when the request is not authorised as a client" in {
      givenAuthorisedAsValidAgent(request, arn.value)
      val result = doGetRequest(request.uri)
      result.status shouldBe UNAUTHORIZED
    }
  }
}
