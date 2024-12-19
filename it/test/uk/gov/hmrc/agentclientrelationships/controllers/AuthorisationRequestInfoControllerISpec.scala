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

package uk.gov.hmrc.agentclientrelationships.controllers

import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.{Invitation, Pending}
import uk.gov.hmrc.agentclientrelationships.repository.{AgentReferenceRepository, InvitationsRepository}
import uk.gov.hmrc.agentclientrelationships.support.TestData

import java.time.{Instant, LocalDate, ZoneId}
import scala.concurrent.ExecutionContext

class AuthorisationRequestInfoControllerISpec extends RelationshipsBaseControllerISpec with TestData {

  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  val testDate: LocalDate = LocalDate.now()
  val testTime: Instant = testDate.atStartOfDay(ZoneId.systemDefault()).toInstant
  val testInvitationId = "testInvitationId"
  val testInvitation: Invitation = Invitation(
    testInvitationId,
    arn.value,
    "HMRC-MTD-VAT",
    "123456789",
    "vrn",
    "234567890",
    "vrn",
    "testName",
    Pending,
    Some("Me"),
    Some("personal"),
    testDate,
    testTime,
    testTime
  )
  val invitationRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val agentReferenceRepo: AgentReferenceRepository = app.injector.instanceOf[AgentReferenceRepository]

  val testUrl = s"/agent-client-relationships/agent/${arn.value}/authorisation-request-info/$testInvitationId"

  s"GET $testUrl" should {
    "return 200 status and valid JSON when invitation exists and there is an agent record" in {
      val fakeRequest = FakeRequest("GET", testUrl)
      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      givenAgentRecordFound(arn, agentRecordResponse)

      await(invitationRepo.collection.insertOne(testInvitation).toFuture())

      val result = doAgentGetRequest(testUrl)
      result.status shouldBe 200

      val uid = await(agentReferenceRepo.findByArn(arn)).get.uid
      result.json shouldBe Json.obj(
        "agentDetails" -> Json.obj(
          "agencyDetails" -> Json.obj(
            "agencyName"  -> "My Agency",
            "agencyEmail" -> "abc@abc.com"
          ),
          "suspensionDetails" -> Json.obj(
            "suspensionStatus" -> false
          )
        ),
        "agentLink" -> Json.obj(
          "uid"                 -> uid,
          "normalizedAgentName" -> "my-agency"
        ),
        "authorisationRequest" -> Json.obj(
          "invitationId"         -> "testInvitationId",
          "arn"                  -> "AARN0000002",
          "service"              -> "HMRC-MTD-VAT",
          "clientId"             -> "123456789",
          "clientIdType"         -> "vrn",
          "suppliedClientId"     -> "234567890",
          "suppliedClientIdType" -> "vrn",
          "clientName"           -> "testName",
          "status"               -> "Pending",
          "relationshipEndedBy"  -> "Me",
          "clientType"           -> "personal",
          "expiryDate"           -> testDate,
          "created"              -> testTime,
          "lastUpdated"          -> testTime
        )
      )
    }
    "return 404 status when invitation doesnt exist" in {
      val fakeRequest = FakeRequest("GET", testUrl)
      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      val result = doAgentGetRequest(testUrl)
      result.status shouldBe 404
    }
  }
}
