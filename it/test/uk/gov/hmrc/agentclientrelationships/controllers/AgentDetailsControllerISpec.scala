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
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentReferenceRecord
import uk.gov.hmrc.agentclientrelationships.repository.MongoAgentReferenceRepository
import uk.gov.hmrc.agentclientrelationships.support.TestData

import scala.concurrent.ExecutionContext

class AgentDetailsControllerISpec extends BaseControllerISpec with TestData {

  val uid = "TestUID"
  val normalizedAgentName = "TestNormalizedAgentName"
  val agentReferenceRecord: AgentReferenceRecord = AgentReferenceRecord(
    uid = uid,
    arn = arn,
    normalisedAgentNames = Seq(normalizedAgentName, "NormalisedAgentName2")
  )

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  def agentReferenceRepo: MongoAgentReferenceRepository = new MongoAgentReferenceRepository(mongoComponent)

  val testUrl: String = s"/agent-client-relationships/agent/${arn.value}/details"

  s"GET $testUrl" should {
    "return 200 status with valid agent details when they are found" in {
      val fakeRequest = FakeRequest("GET", testUrl)
      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      givenAgentRecordFound(arn, agentRecordResponse)
      await(agentReferenceRepo.create(agentReferenceRecord))

      val result =
        doGetRequest(testUrl)
      result.status shouldBe 200
      result.json shouldBe Json.obj(
        "agencyDetails" -> Json.obj(
          "agencyName"  -> "My Agency",
          "agencyEmail" -> "abc@abc.com"
        ),
        "suspensionDetails" -> Json.obj(
          "suspensionStatus" -> false
        )
      )

    }
  }
}
