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

package uk.gov.hmrc.agentclientrelationships.testOnly.controllers

import play.api.libs.json.Json
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.agentclientrelationships.controllers.BaseControllerISpec
import uk.gov.hmrc.agentclientrelationships.model.{Invitation, Pending}
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate}

class TestOnlyInvitationsControllerISpec extends BaseControllerISpec {

  val invitationsRepository: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val validInvitationId = "QWERTYUIOP123"
  val invalidInvitationId = "ABC123"
  def url(invitationId: String) = s"/test-only/invitation/$invitationId"
  val invitation: Invitation = Invitation(
    "QWERTYUIOP123",
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

  "GET /test-only/invitation/:invitationId" should {

    "return 200 when an invitation is found" in {
      givenAuthorised()
      await(invitationsRepository.collection.insertOne(invitation).toFuture())
      val result = doGetRequest(url(validInvitationId))
      result.status shouldBe 200
      result.json shouldBe Json.toJson(invitation)
    }

    "return 400 when the invitation ID is in the wrong format" in {
      givenAuthorised()
      val result = doGetRequest(url(invalidInvitationId))
      result.status shouldBe 400
      result.body shouldBe "Invalid invitation ID format"
    }

    "return 401 when the request is unauthorised" in {
      requestIsNotAuthenticated()
      val result = doGetRequest(url(validInvitationId))
      result.status shouldBe 401
    }

    "return 404 when an invitation is not found" in {
      givenAuthorised()
      val result = doGetRequest(url(validInvitationId))
      result.status shouldBe 404
    }
  }
}
