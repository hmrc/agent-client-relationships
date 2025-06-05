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

import play.api.libs.json.Json
import play.api.mvc.AnyContentAsEmpty
import play.api.test.FakeRequest
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthRelationship
import uk.gov.hmrc.agentclientrelationships.model.stride.PartialAuthRelationships
import uk.gov.hmrc.agentclientrelationships.model.stride.PartialAuth
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgencyDetails
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.stubs.CitizenDetailsStub
import uk.gov.hmrc.agentmtdidentifiers.model._

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class StrideGetPartialAuthsControllerISpec
extends BaseControllerISpec
with CitizenDetailsStub {

  val partialAuthRepo: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]

  val req: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()

  val testAgentRecord: TestAgentDetailsDesResponse = TestAgentDetailsDesResponse(
    uniqueTaxReference = None,
    agencyDetails = Some(
      TestAgencyDetails(
        agencyName = Some("ABC Ltd"),
        agencyEmail = None,
        agencyTelephone = None,
        agencyAddress = None
      )
    ),
    suspensionDetails = None
  )

  val testAgentRecord2: TestAgentDetailsDesResponse = TestAgentDetailsDesResponse(
    uniqueTaxReference = None,
    agencyDetails = Some(
      TestAgencyDetails(
        agencyName = Some("DEF Ltd"),
        agencyEmail = None,
        agencyTelephone = None,
        agencyAddress = None
      )
    ),
    suspensionDetails = None
  )

  val partialAuthStartInstant: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
  val partialAuthStartDate: LocalDate = partialAuthStartInstant.atZone(ZoneId.systemDefault()).toLocalDate

  val partialAuth: PartialAuth = PartialAuth(
    agentName = "ABC Ltd",
    arn = arn.value,
    startDate = partialAuthStartDate,
    "HMRC-MTD-IT"
  )

  val partialAuthRelationship: PartialAuthRelationship = PartialAuthRelationship(
    partialAuthStartInstant,
    arn.value,
    "HMRC-MTD-IT",
    nino.value,
    active = true,
    lastUpdated = partialAuthStartInstant
  )

  def agentDetailsDesResponse(suspended: Boolean = false): AgentDetailsDesResponse = AgentDetailsDesResponse(
    agencyDetails = AgencyDetails("ABC Ltd", ""),
    suspensionDetails = None
  )

  "GET /stride/partial-auths/nino/:nino" should {

    def requestPath(nino: String) = s"/agent-client-relationships/stride/partial-auths/nino/$nino"

    "return 200 with expected JSON body when all calls are successful" in {
      givenAuthorisedAsStrideUser(req, "user-123")
      givenAuditConnector()
      partialAuthRepo.collection.insertOne(partialAuthRelationship).toFuture().futureValue
      givenCitizenDetailsExists(nino.value)
      givenAgentRecordFound(arn, testAgentRecord)

      val expectedJsonBody = Json.obj(
        "clientName" -> "Matthew Kovacic",
        "nino" -> "AB123456C",
        "partialAuths" -> Json.arr(
          Json.toJson(partialAuth)
        )
      )

      val result = doGetRequest(requestPath(nino.value))
      result.status shouldBe 200
      result.json shouldBe expectedJsonBody
    }

    "return 422 with error body with code NOT_FOUND when no partial auths are found" in {
      givenAuthorisedAsStrideUser(req, "user-123")
      givenAuditConnector()
      givenCitizenDetailsExists(nino.value)
      givenAgentRecordFound(arn, testAgentRecord)

      val result = doGetRequest(requestPath(nino.value))
      result.status shouldBe 422
      result.json shouldBe Json.obj(
        "code" -> "NOT_FOUND",
        "message" -> "No partial authorisations for Making Tax Digital for Income Tax were found for this client id"
      )
    }

  }

}
