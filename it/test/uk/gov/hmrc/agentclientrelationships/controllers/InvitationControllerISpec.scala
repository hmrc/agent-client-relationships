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
import play.api.test.Helpers.{await, defaultAwaitTimeout, stubControllerComponents}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentReferenceRecord
import uk.gov.hmrc.agentclientrelationships.repository.MongoAgentReferenceRepository
import uk.gov.hmrc.agentclientrelationships.services.{InvitationLinkService, InvitationService}
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.auth.core.AuthConnector

import scala.collection.Seq
import scala.concurrent.ExecutionContext

class InvitationControllerISpec extends RelationshipsBaseControllerISpec with TestData {

  val uid = "TestUID"
  val normalizedAgentName = "TestNormalizedAgentName"
  val agentReferenceRecord: AgentReferenceRecord = AgentReferenceRecord(
    uid = uid,
    arn = arn,
    normalisedAgentNames = Seq(normalizedAgentName, "NormalisedAgentName2")
  )

  val invitationService: InvitationService = app.injector.instanceOf[InvitationService]
  val authConnector: AuthConnector = app.injector.instanceOf[AuthConnector]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  val controller =
    new InvitationController(invitationService, authConnector, appConfig, stubControllerComponents())

  def agentReferenceRepo: MongoAgentReferenceRepository = new MongoAgentReferenceRepository(mongoComponent)


  "create invitation link" should {

    "return 201 status and valid JSON when invitation is created" in {
      val fakeRequest = FakeRequest("POST", s"/agent-client-relationships/agent/${arn.value}/authorization-request")
      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      val normalisedName = agentRecord.agencyDetails.agencyName
        .toLowerCase()
        .replaceAll("\\s+", "-")
        .replaceAll("[^A-Za-z0-9-]", "")

      givenAgentRecordFound(arn, agentRecordResponse)
      await(agentReferenceRepo.create(agentReferenceRecord))

      val result =
        doAgentGetRequest(s"/agent-client-relationships/agent/agent-link")
      result.status shouldBe 200
      result.json shouldBe Json.obj(
        "uid"                 -> agentReferenceRecord.uid,
        "normalizedAgentName" -> normalisedName
      )
      agentReferenceRepo
        .findBy(agentReferenceRecord.uid)
        .futureValue
        .get
        .normalisedAgentNames should contain atLeastOneElementOf Seq(normalisedName)
    }

  }
}
