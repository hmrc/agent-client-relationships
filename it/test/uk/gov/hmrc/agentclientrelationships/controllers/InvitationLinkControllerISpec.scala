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
import play.api.test.Helpers.{await, defaultAwaitTimeout, stubControllerComponents}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentReferenceRecord
import uk.gov.hmrc.agentclientrelationships.repository.MongoAgentReferenceRepository
import uk.gov.hmrc.agentclientrelationships.services.AgentReferenceService
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.auth.core.AuthConnector

import scala.collection.Seq
import scala.concurrent.ExecutionContext

class InvitationLinkControllerISpec extends RelationshipsBaseControllerISpec with TestData {

  val uid = "TestUID"
  val normalizedAgentName = "TestNormalizedAgentName"
  val agentReferenceRecord: AgentReferenceRecord = AgentReferenceRecord(
    uid = uid,
    arn = arn,
    normalisedAgentNames = Seq(normalizedAgentName, "NormalisedAgentName2")
  )

  val agentReferenceService: AgentReferenceService = app.injector.instanceOf[AgentReferenceService]
  val authConnector: AuthConnector = app.injector.instanceOf[AuthConnector]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  val controller = new InvitationLinkController(agentReferenceService, authConnector, stubControllerComponents())

  def agentReferenceRepo: MongoAgentReferenceRepository = new MongoAgentReferenceRepository(mongoComponent)

  "validate invitation link" should {

    "return 200 status and valid JSON when agent reference and details are found and agent is not suspended " in {
      givenAuditConnector()
      givenAgentRecordFound(agentRecord)
      await(agentReferenceRepo.create(agentReferenceRecord))

      val result = doAgentGetRequest(s"/agent-client-relationships/agent-reference/uid/$uid/$normalizedAgentName")
      result.status shouldBe 200
      result.json shouldBe Json.obj(
        "arn"  -> arn.value,
        "name" -> agentRecord.agencyDetails.flatMap(_.agencyName)
      )
    }

    "return 404 status when agent reference is not found" in {
      givenAuditConnector()
      givenAgentRecordFound(agentRecord)

      val result = doAgentGetRequest(s"/agent-client-relationships/agent-reference/uid/$uid/$normalizedAgentName")
      result.status shouldBe 404
    }

    "return 404 status when normalisedAgentNames is not on agent reference list" in {
      givenAuditConnector()
      givenAgentRecordFound(agentRecord)
      await(agentReferenceRepo.create(agentReferenceRecord.copy(normalisedAgentNames = Seq("DummyNotMatching"))))

      val result = doAgentGetRequest(s"/agent-client-relationships/agent-reference/uid/$uid/$normalizedAgentName")
      result.status shouldBe 404
    }

    "return 502 status agent details are not found" in {
      givenAuditConnector()
      givenAgentDetailsErrorResponse(502)
      await(agentReferenceRepo.create(agentReferenceRecord))

      val result = doAgentGetRequest(s"/agent-client-relationships/agent-reference/uid/$uid/$normalizedAgentName")
      result.status shouldBe 502
    }

    "return 403 status when agent is suspended" in {
      givenAuditConnector()
      givenAgentRecordFound(suspendedAgentRecordOption)
      await(agentReferenceRepo.create(agentReferenceRecord))

      val result = doAgentGetRequest(s"/agent-client-relationships/agent-reference/uid/$uid/$normalizedAgentName")
      result.status shouldBe 403
    }
  }
}
