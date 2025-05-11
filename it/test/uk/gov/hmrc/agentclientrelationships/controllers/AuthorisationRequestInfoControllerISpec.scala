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
import play.api.test.Helpers.await
import play.api.test.Helpers.defaultAwaitTimeout
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.Accepted
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.Pending
import uk.gov.hmrc.agentclientrelationships.repository.AgentReferenceRepository
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.support.TestData

import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import scala.concurrent.ExecutionContext

class AuthorisationRequestInfoControllerISpec extends BaseControllerISpec with TestData {

  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  val testDate: LocalDate = LocalDate.now()
  val testTime: Instant = testDate.atStartOfDay(ZoneId.systemDefault()).toInstant
  val testInvitationId = "testInvitationId"
  val testInvitation: Invitation = Invitation(
    testInvitationId,
    arn.value,
    "HMRC-MTD-VAT",
    vrn.value,
    "vrn",
    vrn.value,
    "vrn",
    "testName",
    "testAgentName",
    "agent@email.com",
    warningEmailSent = false,
    expiredEmailSent = false,
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
  val testClientUrl = s"/agent-client-relationships/client/authorisation-request-info/$testInvitationId"
  val testTrackRequestsUrl =
    s"/agent-client-relationships/agent/${arn.value}/authorisation-requests?pageNumber=1&pageSize=10"

  s"GET $testUrl" should {
    "return 200 status and valid JSON when invitation exists and there is an agent record" in {
      val fakeRequest = FakeRequest("GET", testUrl)
      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      givenAgentRecordFound(arn, agentRecordResponse)

      await(invitationRepo.collection.insertOne(testInvitation).toFuture())

      val result = doGetRequest(testUrl)
      result.status shouldBe 200

      val uid = await(agentReferenceRepo.findByArn(arn)).get.uid
      result.json shouldBe Json.obj(
        "agentLink" -> Json.obj("uid" -> uid, "normalizedAgentName" -> "my-agency"),
        "authorisationRequest" -> Json.obj(
          "invitationId"         -> "testInvitationId",
          "arn"                  -> "AARN0000002",
          "service"              -> "HMRC-MTD-VAT",
          "clientId"             -> vrn.value,
          "clientIdType"         -> "vrn",
          "suppliedClientId"     -> vrn.value,
          "suppliedClientIdType" -> "vrn",
          "clientName"           -> "testName",
          "agencyName"           -> "testAgentName",
          "agencyEmail"          -> "agent@email.com",
          "warningEmailSent"     -> false,
          "expiredEmailSent"     -> false,
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

      val result = doGetRequest(testUrl)
      result.status shouldBe 404
    }
  }

  s"GET $testClientUrl" should {
    "return 200 status and valid JSON when invitation exists and the client has authorisation with enrolment" in {
      val fakeRequest = FakeRequest("GET", testClientUrl)
      givenAuditConnector()
      givenAuthorisedAsVatClient(fakeRequest, vrn)
      givenAgentRecordFound(arn, agentRecordResponse)
      await(invitationRepo.collection.insertOne(testInvitation.copy(status = Accepted)).toFuture())
      val result = doGetRequest(testClientUrl)
      result.status shouldBe 200

      result.json shouldBe Json.obj(
        "agentName" -> "My Agency",
        "service"   -> "HMRC-MTD-VAT",
        "status"    -> "Accepted"
      )
    }
    "return 404 status when invitation doesnt exist" in {
      val fakeRequest = FakeRequest("GET", testClientUrl)
      givenAuditConnector()
      givenAuthorisedAsClient(
        fakeRequest,
        mtdItId,
        vrn,
        utr,
        urn,
        pptRef,
        cgtRef
      )
      val result = doGetRequest(testClientUrl)
      result.status shouldBe 404
    }
  }

  s"GET $testTrackRequestsUrl" should {
    "return 200 status and valid JSON to represent the result set" in {
      val fakeRequest = FakeRequest("GET", testTrackRequestsUrl)
      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      val listOfInvitations = Seq(
        testInvitation.copy(invitationId = "testInvitationId1"),
        testInvitation.copy(invitationId = "testInvitationId2"),
        testInvitation.copy(invitationId = "testInvitationId3")
      )

      await(invitationRepo.collection.insertMany(listOfInvitations).toFuture())

      val result = doGetRequest(testTrackRequestsUrl)
      result.status shouldBe 200

      result.json shouldBe Json.obj(
        "pageNumber"       -> 1,
        "requests"         -> Json.toJson(listOfInvitations),
        "clientNames"      -> Json.arr("testName"),
        "availableFilters" -> Json.arr("Pending"),
        "totalResults"     -> 3
      )
    }
    "correctly filter the result set when the clientName filter is applied" in {
      val clientNameFilter = URLEncoder.encode("Find Me", "UTF-8")
      val clientNameFilterUrl = testTrackRequestsUrl + "&clientName=" + clientNameFilter
      val fakeRequest = FakeRequest("GET", clientNameFilterUrl)
      val matchingInvitation = testInvitation.copy(invitationId = "testInvitationId2", clientName = "Find Me")
      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      val listOfInvitations = Seq(
        testInvitation.copy(invitationId = "testInvitationId1"),
        matchingInvitation,
        testInvitation.copy(invitationId = "testInvitationId3")
      )

      await(invitationRepo.collection.insertMany(listOfInvitations).toFuture())

      val result = doGetRequest(clientNameFilterUrl)
      result.status shouldBe 200

      result.json shouldBe Json.obj(
        "pageNumber"       -> 1,
        "requests"         -> Json.toJson(Seq(matchingInvitation)),
        "clientNames"      -> Json.arr("Find Me", "testName"),
        "availableFilters" -> Json.arr("Pending"),
        "filtersApplied"   -> Json.obj("clientFilter" -> "Find Me"),
        "totalResults"     -> 1
      )
    }
    "correctly filter the result set when the status filter is applied" in {
      val statusFilterUrl = testTrackRequestsUrl + "&statusFilter=Accepted"
      val fakeRequest = FakeRequest("GET", statusFilterUrl)
      val matchingInvitation = testInvitation.copy(invitationId = "testInvitationId2", status = Accepted)
      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      val listOfInvitations = Seq(
        testInvitation.copy(invitationId = "testInvitationId1"),
        matchingInvitation,
        testInvitation.copy(invitationId = "testInvitationId3")
      )

      await(invitationRepo.collection.insertMany(listOfInvitations).toFuture())

      val result = doGetRequest(statusFilterUrl)
      result.status shouldBe 200

      result.json shouldBe Json.obj(
        "pageNumber"       -> 1,
        "requests"         -> Json.toJson(Seq(matchingInvitation)),
        "clientNames"      -> Json.arr("testName"),
        "availableFilters" -> Json.arr("Accepted", "Pending"),
        "filtersApplied"   -> Json.obj("statusFilter" -> "Accepted"),
        "totalResults"     -> 1
      )
    }
    "correctly filter the result set when the all filters are applied" in {
      val clientNameFilter = URLEncoder.encode("Find Me", "UTF-8")
      val allFiltersUrl = testTrackRequestsUrl + "&statusFilter=Accepted&clientName=" + clientNameFilter
      val fakeRequest = FakeRequest("GET", allFiltersUrl)
      val matchingInvitation = testInvitation
        .copy(
          invitationId = "testInvitationId2",
          status = Accepted,
          clientName = "Find Me"
        )
      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      val listOfInvitations = Seq(
        testInvitation.copy(invitationId = "testInvitationId1"),
        matchingInvitation,
        testInvitation.copy(invitationId = "testInvitationId3")
      )

      await(invitationRepo.collection.insertMany(listOfInvitations).toFuture())

      val result = doGetRequest(allFiltersUrl)
      result.status shouldBe 200

      result.json shouldBe Json.obj(
        "pageNumber"       -> 1,
        "requests"         -> Json.toJson(Seq(matchingInvitation)),
        "clientNames"      -> Json.arr("Find Me", "testName"),
        "availableFilters" -> Json.arr("Accepted", "Pending"),
        "filtersApplied"   -> Json.obj("statusFilter" -> "Accepted", "clientFilter" -> "Find Me"),
        "totalResults"     -> 1
      )
    }
    "correctly return an empty result set when filters are not matched" in {
      val clientNameFilter = URLEncoder.encode("Find Me", "UTF-8")
      val allFiltersUrl = testTrackRequestsUrl + "&statusFilter=Accepted&clientName=" + clientNameFilter
      val fakeRequest = FakeRequest("GET", allFiltersUrl)
      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      val listOfInvitations = Seq(
        testInvitation.copy(invitationId = "testInvitationId1"),
        testInvitation.copy(invitationId = "testInvitationId2"),
        testInvitation.copy(invitationId = "testInvitationId3")
      )

      await(invitationRepo.collection.insertMany(listOfInvitations).toFuture())

      val result = doGetRequest(allFiltersUrl)
      result.status shouldBe 200

      result.json shouldBe Json.obj(
        "pageNumber"       -> 1,
        "requests"         -> Json.arr(),
        "clientNames"      -> Json.arr("testName"),
        "availableFilters" -> Json.arr("Pending"),
        "filtersApplied"   -> Json.obj("statusFilter" -> "Accepted", "clientFilter" -> "Find Me"),
        "totalResults"     -> 0
      )
    }
    "respect the page size" in {
      val expectedPageSize = 2
      val pageSizeUrl = testTrackRequestsUrl.replace("pageSize=10", "pageSize=" + expectedPageSize)
      val fakeRequest = FakeRequest("GET", pageSizeUrl)
      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      val listOfInvitations = Seq(
        testInvitation.copy(invitationId = "testInvitationId1"),
        testInvitation.copy(invitationId = "testInvitationId2"),
        testInvitation.copy(invitationId = "testInvitationId3")
      )

      await(invitationRepo.collection.insertMany(listOfInvitations).toFuture())

      val result = doGetRequest(pageSizeUrl)
      result.status shouldBe 200

      result.json shouldBe Json.obj(
        "pageNumber" -> 1,
        "requests" -> Json.toJson(
          Seq(
            testInvitation.copy(invitationId = "testInvitationId1"),
            testInvitation.copy(invitationId = "testInvitationId2")
          )
        ),
        "clientNames"      -> Json.arr("testName"),
        "availableFilters" -> Json.arr("Pending"),
        "totalResults"     -> 3
      )
    }
    "respect the page number" in {
      val expectedPageNumber = 2
      val expectedPageSize = 2
      val pageSizeUrl = testTrackRequestsUrl
        .replace("pageNumber=1&pageSize=10", "pageNumber=" + expectedPageNumber + "&pageSize=" + expectedPageSize)
      val fakeRequest = FakeRequest("GET", pageSizeUrl)
      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      val listOfInvitations = Seq(
        testInvitation.copy(invitationId = "testInvitationId1"),
        testInvitation.copy(invitationId = "testInvitationId2"),
        testInvitation.copy(invitationId = "testInvitationId3")
      )

      await(invitationRepo.collection.insertMany(listOfInvitations).toFuture())

      val result = doGetRequest(pageSizeUrl)
      result.status shouldBe 200

      result.json shouldBe Json.obj(
        "pageNumber"       -> expectedPageNumber,
        "requests"         -> Json.toJson(Seq(testInvitation.copy(invitationId = "testInvitationId3"))),
        "clientNames"      -> Json.arr("testName"),
        "availableFilters" -> Json.arr("Pending"),
        "totalResults"     -> 3
      )
    }
    "return 400 status when the query is not well formed" in {
      val badUrl = testTrackRequestsUrl.replace("&pageSize=10", "")
      val fakeRequest = FakeRequest("GET", badUrl)
      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      val result = doGetRequest(badUrl)
      result.status shouldBe 400
    }
  }

}
