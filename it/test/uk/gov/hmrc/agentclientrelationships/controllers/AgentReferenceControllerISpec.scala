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
import play.api.test.Helpers.{ await, defaultAwaitTimeout }
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentReferenceRecord
import uk.gov.hmrc.agentclientrelationships.repository.AgentReferenceRepository
import uk.gov.hmrc.agentmtdidentifiers.model.Arn

class AgentReferenceControllerISpec extends BaseControllerISpec {

  val referenceRepo: AgentReferenceRepository = app.injector.instanceOf[AgentReferenceRepository]
  val fakeRequest: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()

  val fetchOrCreateUrl = s"/agent-client-relationships/agent-reference"
  val fetchUrl = "/agent-client-relationships/agent-reference/uid"

  val testArn: Arn = Arn("ARN1234567890")
  val testName1 = "testName1"
  val testName2 = "testName2"
  val testUid = "testUid"
  val testRecord: AgentReferenceRecord = AgentReferenceRecord(testUid, testArn, Seq(testName1))

  s"GET $fetchOrCreateUrl" should {
    "return Unauthorised" when {
      "the user is not authorised" in {
        requestIsNotAuthenticated()
        givenAuditConnector()

        val result = doAgentPutRequest(fetchOrCreateUrl + s"/${testArn.value}", Json.obj())

        result.status shouldBe 401
      }
    }
    "return BadRequest" when {
      "request is missing a normalisedAgentName" in {
        givenAuditConnector()
        givenAuthorised()

        val result = doAgentPutRequest(fetchOrCreateUrl + s"/${testArn.value}", Json.obj())

        result.status shouldBe 400
      }
    }
    "return Ok with an agent reference record" when {
      "there is already a record with the provided name" in {
        givenAuditConnector()
        givenAuthorised()

        await(referenceRepo.collection.insertOne(testRecord).toFuture())

        val result =
          doAgentPutRequest(fetchOrCreateUrl + s"/${testArn.value}", Json.obj("normalisedAgentName" -> testName1))

        result.status shouldBe 200
        result.json shouldBe Json.toJson(testRecord)
      }
      "there is already a record, but it needs an update to add the new name" in {
        givenAuditConnector()
        givenAuthorised()

        await(referenceRepo.collection.insertOne(testRecord).toFuture())

        val result =
          doAgentPutRequest(fetchOrCreateUrl + s"/${testArn.value}", Json.obj("normalisedAgentName" -> testName2))

        val expected = testRecord.copy(normalisedAgentNames = Seq(testName1, testName2))
        result.status shouldBe 200
        result.json shouldBe Json.toJson(expected)
        await(referenceRepo.findByArn(testArn)).get shouldBe expected
      }
      "there is no agent record and a new one is inserted" in {
        givenAuditConnector()
        givenAuthorised()

        val result =
          doAgentPutRequest(fetchOrCreateUrl + s"/${testArn.value}", Json.obj("normalisedAgentName" -> testName1))

        val newRecord = await(referenceRepo.findByArn(testArn))
        result.status shouldBe 200
        result.json shouldBe Json.toJson(newRecord)
      }
    }
  }

  s"GET $fetchUrl" should {
    "return NotFound" when {
      "queried with uid that does not match any data" in {
        givenAuditConnector()

        val result = doGetRequest(fetchUrl + s"/$testUid")

        result.status shouldBe 404
      }
    }
    "return OK with invitations" when {
      "queried with invitationId that matches some data" in {
        givenAuditConnector()

        await(referenceRepo.collection.insertOne(testRecord).toFuture())

        val result = doGetRequest(fetchUrl + s"/$testUid")

        result.status shouldBe 200
        result.json shouldBe Json.toJson(testRecord)
      }
    }
  }

}
