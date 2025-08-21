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

import play.api.http.Status.NOT_FOUND
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.await
import play.api.test.Helpers.defaultAwaitTimeout
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentReferenceRecord
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.ExistingMainAgent
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.ValidateInvitationResponse
import uk.gov.hmrc.agentclientrelationships.repository.AgentReferenceRepository
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.services.InvitationLinkService
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Cbc
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdIt
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdItSupp
import uk.gov.hmrc.auth.core.AuthConnector

import java.time.temporal.ChronoUnit
import java.time.Instant
import java.time.LocalDate
import scala.concurrent.ExecutionContext

class InvitationLinkControllerISpec
  extends BaseControllerISpec
    with TestData
    with HipStub {

  val uid = "TestUID"
  val existingAgentUid = "ExitingAgentUid"
  val normalizedAgentName = "TestNormalizedAgentName"
  val normalizedExistingAgentName = "ExistingAgent"
  val agentReferenceRecord: AgentReferenceRecord = AgentReferenceRecord(
    uid = uid,
    arn = arn,
    normalisedAgentNames = Seq(normalizedAgentName, "NormalisedAgentName2")
  )

  val existingAgentReferenceRecord: AgentReferenceRecord = AgentReferenceRecord(
    uid = existingAgentUid,
    arn = existingAgentArn,
    normalisedAgentNames = Seq(normalizedExistingAgentName)
  )

  val invitationsRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val partialAuthRepo: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]
  val agentReferenceRepo: AgentReferenceRepository = app.injector.instanceOf[AgentReferenceRepository]

  val agentReferenceService: InvitationLinkService = app.injector.instanceOf[InvitationLinkService]
  val authConnector: AuthConnector = app.injector.instanceOf[AuthConnector]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  "validate invitation link" should {

    "return 200 status and valid JSON when agent reference and details are found and agent is not suspended " in {
      givenAuditConnector()

      givenAgentRecordFound(arn, agentRecordResponse)
      await(agentReferenceRepo.create(agentReferenceRecord))

      val result = doGetRequest(s"/agent-client-relationships/agent/agent-reference/uid/$uid/$normalizedAgentName")
      result.status shouldBe 200
      result.json shouldBe Json.obj("arn" -> arn.value, "name" -> agentRecord.agencyDetails.agencyName)
    }

    "return 404 status when agent reference is not found" in {
      givenAuditConnector()
      givenAgentRecordFound(arn, agentRecordResponse)

      val result = doGetRequest(s"/agent-client-relationships/agent/agent-reference/uid/$uid/$normalizedAgentName")
      result.status shouldBe 404
    }

    "return 404 status when normalisedAgentNames is not on agent reference list" in {
      givenAuditConnector()
      givenAgentRecordFound(arn, agentRecordResponse)
      await(agentReferenceRepo.create(agentReferenceRecord.copy(normalisedAgentNames = Seq("DummyNotMatching"))))

      val result = doGetRequest(s"/agent-client-relationships/agent/agent-reference/uid/$uid/$normalizedAgentName")
      result.status shouldBe 404
    }

    "return 404 status when agent name is missing" in {
      givenAuditConnector()
      givenAgentRecordFound(arn, agentRecordResponseWithNoAgentName)

      val result = doGetRequest(s"/agent-client-relationships/agent/agent-reference/uid/$uid/$normalizedAgentName")
      result.status shouldBe 404
    }

    "return 502 status agent details are not found" in {
      givenAuditConnector()
      givenAgentDetailsErrorResponse(arn, 502)
      await(agentReferenceRepo.create(agentReferenceRecord))

      val result = doGetRequest(s"/agent-client-relationships/agent/agent-reference/uid/$uid/$normalizedAgentName")
      result.status shouldBe 502
    }

    "return 403 status when agent is suspended" in {
      givenAuditConnector()
      givenAgentRecordFound(arn, suspendedAgentRecordResponse)
      await(agentReferenceRepo.create(agentReferenceRecord))

      val result = doGetRequest(s"/agent-client-relationships/agent/agent-reference/uid/$uid/$normalizedAgentName")
      result.status shouldBe 403
    }
  }

  "create invitation link" should {

    "return 200 status and valid JSON when agent reference and details are found" in {
      val fakeRequest = FakeRequest("GET", s"/agent-client-relationships/agent/agent-link")
      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      val normalisedName = agentRecord.agencyDetails.agencyName
        .toLowerCase()
        .replaceAll("\\s+", "-")
        .replaceAll("[^A-Za-z0-9-]", "")

      givenAgentRecordFound(arn, agentRecordResponse)
      await(agentReferenceRepo.create(agentReferenceRecord))

      val result = doGetRequest(s"/agent-client-relationships/agent/agent-link")
      result.status shouldBe 200
      result.json shouldBe Json.obj("uid" -> agentReferenceRecord.uid, "normalizedAgentName" -> normalisedName)
      agentReferenceRepo
        .findBy(agentReferenceRecord.uid)
        .futureValue
        .get
        .normalisedAgentNames should contain atLeastOneElementOf Seq(normalisedName)
    }

    "return 200 status and valid JSON when details are found and create new agent reference" in {
      val fakeRequest = FakeRequest("GET", s"/agent-client-relationships/agent/agent-link")
      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      val normalisedName = agentRecord.agencyDetails.agencyName
        .toLowerCase()
        .replaceAll("\\s+", "-")
        .replaceAll("[^A-Za-z0-9-]", "")

      givenAgentRecordFound(arn, agentRecordResponse)

      val result = doGetRequest(s"/agent-client-relationships/agent/agent-link")
      result.status shouldBe 200

      agentReferenceRepo.findByArn(arn).futureValue.get.normalisedAgentNames should contain atLeastOneElementOf Seq(
        normalisedName
      )
    }
  }

  "validate invitation for client" should {

    val fakeRequest = FakeRequest("POST", s"/agent-client-relationships/client/validate-invitation")

    "return 200 status and appropriate JSON body when a matching agent and invitation is found" in {
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
      givenAgentRecordFound(arn, agentRecordResponse)
      givenDelegatedGroupIdsNotExistFor(mtdItEnrolmentKey)
      await(agentReferenceRepo.create(agentReferenceRecord))
      val pendingInvitation = await(
        invitationsRepo.create(
          arn.value,
          MtdIt,
          mtdItId,
          mtdItId,
          "Erling Haal",
          "testAgentName",
          "agent@email.com",
          LocalDate.now(),
          Some("personal")
        )
      )

      val requestBody = Json.obj("uid" -> uid, "serviceKeys" -> Json.arr("HMRC-MTD-IT"))
      val result = doAgentPostRequest(fakeRequest.uri, requestBody)
      val expectedResponse = ValidateInvitationResponse(
        pendingInvitation.invitationId,
        pendingInvitation.service,
        agentRecord.agencyDetails.agencyName,
        pendingInvitation.status,
        pendingInvitation.lastUpdated.truncatedTo(ChronoUnit.MILLIS),
        existingMainAgent = None,
        clientType = Some("personal")
      )

      result.status shouldBe 200
      result.json shouldBe Json.toJson(expectedResponse)
    }

    "return 200 status and appropriate JSON body when a matching agent and invitation plus existing main agent is found" in {
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
      givenAgentRecordFound(arn, agentRecordResponse)
      givenDelegatedGroupIdsExistFor(mtdItEnrolmentKey, Set(testExistingAgentGroup))
      givenGetAgentReferenceNumberFor(testExistingAgentGroup, existingAgentArn.value)
      givenAgentRecordFound(existingAgentArn, existingAgentRecordResponse)
      await(agentReferenceRepo.create(agentReferenceRecord))
      val expectedExistingMainAgent = ExistingMainAgent(agencyName = "ExistingAgent", sameAgent = false)
      val pendingInvitation = await(
        invitationsRepo.create(
          arn.value,
          MtdIt,
          mtdItId,
          mtdItId,
          "Erling Haal",
          "testAgentName",
          "agent@email.com",
          LocalDate.now(),
          Some("personal")
        )
      )

      val requestBody = Json.obj("uid" -> uid, "serviceKeys" -> Json.arr("HMRC-MTD-IT"))
      val result = doAgentPostRequest(fakeRequest.uri, requestBody)
      val expectedResponse = ValidateInvitationResponse(
        pendingInvitation.invitationId,
        pendingInvitation.service,
        agentRecord.agencyDetails.agencyName,
        pendingInvitation.status,
        pendingInvitation.lastUpdated.truncatedTo(ChronoUnit.MILLIS),
        existingMainAgent = Some(expectedExistingMainAgent),
        clientType = Some("personal")
      )

      result.status shouldBe 200
      result.json shouldBe Json.toJson(expectedResponse)
    }

    "return 200 status and appropriate JSON body when a matching agent and invitation plus existing same main agent is found" in {
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
      givenAgentRecordFound(existingAgentArn, existingAgentRecordResponse)
      givenDelegatedGroupIdsExistFor(mtdItEnrolmentKey, Set(testExistingAgentGroup))
      givenGetAgentReferenceNumberFor(testExistingAgentGroup, existingAgentArn.value)
      givenAgentRecordFound(existingAgentArn, existingAgentRecordResponse)
      await(agentReferenceRepo.create(existingAgentReferenceRecord))
      val expectedExistingMainAgent = ExistingMainAgent(agencyName = "ExistingAgent", sameAgent = true)
      val pendingInvitation = await(
        invitationsRepo.create(
          existingAgentArn.value,
          MtdItSupp,
          mtdItId,
          nino,
          "Erling Haal",
          "testAgentName",
          "agent@email.com",
          LocalDate.now(),
          Some("personal")
        )
      )

      val requestBody = Json.obj("uid" -> existingAgentUid, "serviceKeys" -> Json.arr("HMRC-MTD-IT"))
      val result = doAgentPostRequest(fakeRequest.uri, requestBody)
      val expectedResponse = ValidateInvitationResponse(
        pendingInvitation.invitationId,
        pendingInvitation.service,
        existingAgentRecord.agencyDetails.agencyName,
        pendingInvitation.status,
        pendingInvitation.lastUpdated.truncatedTo(ChronoUnit.MILLIS),
        existingMainAgent = Some(expectedExistingMainAgent),
        clientType = Some("personal")
      )

      result.status shouldBe 200
      result.json shouldBe Json.toJson(expectedResponse)
    }

    "return 200 status and correct JSON when a matching agent, invitation and existing main agent with partial auth is found" in {
      givenAuditConnector()
      givenAuthorisedAsClientWithNino(fakeRequest, nino)
      await(
        partialAuthRepo.create(
          created = Instant.now(),
          existingAgentArn,
          "HMRC-MTD-IT",
          nino
        )
      )
      givenMtdItIdIsUnKnownFor(nino)
      givenAgentRecordFound(arn, agentRecordResponse)
      givenAgentRecordFound(existingAgentArn, existingAgentRecordResponse)
      await(agentReferenceRepo.create(agentReferenceRecord))
      val expectedExistingMainAgent = ExistingMainAgent(agencyName = "ExistingAgent", sameAgent = false)
      val pendingInvitation = await(
        invitationsRepo.create(
          arn.value,
          MtdIt,
          nino,
          nino,
          "Erling Haal",
          "testAgentName",
          "agent@email.com",
          LocalDate.now(),
          Some("personal")
        )
      )

      val requestBody = Json.obj(
        "uid" -> uid,
        "serviceKeys" -> Json.arr(
          "HMRC-MTD-IT",
          "HMRC-NI",
          "HMRC-PT"
        )
      )
      val result = doAgentPostRequest(fakeRequest.uri, requestBody)
      val expectedResponse = ValidateInvitationResponse(
        pendingInvitation.invitationId,
        pendingInvitation.service,
        agentRecord.agencyDetails.agencyName,
        pendingInvitation.status,
        pendingInvitation.lastUpdated.truncatedTo(ChronoUnit.MILLIS),
        existingMainAgent = Some(expectedExistingMainAgent),
        clientType = Some("personal")
      )

      result.status shouldBe 200
      result.json shouldBe Json.toJson(expectedResponse)
    }

    "return 200 status and correct JSON when a matching agent, invitation and existing same main agent with partial auth is found" in {
      givenAuditConnector()
      givenAuthorisedAsClientWithNino(fakeRequest, nino)
      await(
        partialAuthRepo.create(
          created = Instant.now(),
          existingAgentArn,
          "HMRC-MTD-IT",
          nino
        )
      )
      givenMtdItIdIsUnKnownFor(nino)
      givenAgentRecordFound(existingAgentArn, existingAgentRecordResponse)
      await(agentReferenceRepo.create(existingAgentReferenceRecord))
      val expectedExistingMainAgent = ExistingMainAgent(agencyName = "ExistingAgent", sameAgent = true)
      val pendingInvitation = await(
        invitationsRepo.create(
          existingAgentArn.value,
          MtdItSupp,
          nino,
          nino,
          "Erling Haal",
          "testAgentName",
          "agent@email.com",
          LocalDate.now(),
          Some("personal")
        )
      )

      val requestBody = Json.obj("uid" -> existingAgentUid, "serviceKeys" -> Json.arr("HMRC-MTD-IT", "HMRC-PT"))
      val result = doAgentPostRequest(fakeRequest.uri, requestBody)
      val expectedResponse = ValidateInvitationResponse(
        pendingInvitation.invitationId,
        pendingInvitation.service,
        existingAgentRecord.agencyDetails.agencyName,
        pendingInvitation.status,
        pendingInvitation.lastUpdated.truncatedTo(ChronoUnit.MILLIS),
        existingMainAgent = Some(expectedExistingMainAgent),
        clientType = Some("personal")
      )

      result.status shouldBe 200
      result.json shouldBe Json.toJson(expectedResponse)
    }

    "return 200 status and appropriate JSON body when a matching agent and invitation for ITSA supporting agent is found" in {
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
      givenAgentRecordFound(arn, agentRecordResponse)
      givenDelegatedGroupIdsNotExistFor(mtdItEnrolmentKey)
      await(agentReferenceRepo.create(agentReferenceRecord))
      val pendingInvitation = await(
        invitationsRepo.create(
          arn.value,
          MtdItSupp,
          mtdItId,
          nino,
          "Erling Haal",
          "testAgentName",
          "agent@email.com",
          LocalDate.now(),
          Some("personal")
        )
      )

      val requestBody = Json.obj("uid" -> uid, "serviceKeys" -> Json.arr("HMRC-MTD-IT"))
      val result = doAgentPostRequest(fakeRequest.uri, requestBody)
      val expectedResponse = ValidateInvitationResponse(
        pendingInvitation.invitationId,
        pendingInvitation.service,
        agentRecord.agencyDetails.agencyName,
        pendingInvitation.status,
        pendingInvitation.lastUpdated.truncatedTo(ChronoUnit.MILLIS),
        existingMainAgent = None,
        clientType = Some("personal")
      )

      result.status shouldBe 200
      result.json shouldBe Json.toJson(expectedResponse)
    }

    "return 200 status and appropriate JSON body when a matching agent, invitation and existing agent for CBC UK is found" in {
      givenAuditConnector()
      givenAuthorisedAsCbcUkClient(
        fakeRequest,
        utr,
        cbcId
      )
      givenAgentRecordFound(arn, agentRecordResponse)
      givenDelegatedGroupIdsExistFor(cbcUkEnrolmentKey, Set(testExistingAgentGroup))
      givenGetAgentReferenceNumberFor(testExistingAgentGroup, existingAgentArn.value)
      givenAgentRecordFound(existingAgentArn, existingAgentRecordResponse)
      await(agentReferenceRepo.create(agentReferenceRecord))
      val pendingInvitation = await(
        invitationsRepo.create(
          arn.value,
          Cbc,
          cbcId,
          cbcId,
          "Erling Haal",
          "testAgentName",
          "agent@email.com",
          LocalDate.now(),
          Some("personal")
        )
      )

      val requestBody = Json.obj("uid" -> uid, "serviceKeys" -> Json.arr("HMRC-CBC-ORG"))
      val result = doAgentPostRequest(fakeRequest.uri, requestBody)
      val expectedExistingMainAgent = ExistingMainAgent(agencyName = "ExistingAgent", sameAgent = false)
      val expectedResponse = ValidateInvitationResponse(
        pendingInvitation.invitationId,
        pendingInvitation.service,
        agentRecord.agencyDetails.agencyName,
        pendingInvitation.status,
        pendingInvitation.lastUpdated.truncatedTo(ChronoUnit.MILLIS),
        existingMainAgent = Some(expectedExistingMainAgent),
        clientType = Some("personal")
      )

      result.status shouldBe 200
      result.json shouldBe Json.toJson(expectedResponse)
    }

    "return 400 status when invalid JSON is provided" in {
      givenAuditConnector()

      val requestBody = Json.obj("foo" -> "bar")
      val result = doAgentPostRequest(fakeRequest.uri, requestBody)

      result.status shouldBe 400
    }

    "return 403 status" when {

      "the agent is suspended" in {
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
        givenAgentRecordFound(arn, suspendedAgentRecordResponse)
        givenDelegatedGroupIdsNotExistFor(mtdItEnrolmentKey)
        await(agentReferenceRepo.create(agentReferenceRecord))
        await(
          invitationsRepo.create(
            arn.value,
            MtdIt,
            mtdItId,
            mtdItId,
            "Erling Haal",
            "testAgentName",
            "agent@email.com",
            LocalDate.now(),
            Some("personal")
          )
        )

        val requestBody = Json.obj("uid" -> uid, "serviceKeys" -> Json.arr("HMRC-MTD-IT"))
        val result = doAgentPostRequest(fakeRequest.uri, requestBody)

        result.status shouldBe 403
      }

      "the provided service key does not exist in the client's enrolments" in {
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
        givenAgentRecordFound(arn, agentRecordResponse)
        await(agentReferenceRepo.create(agentReferenceRecord))
        await(
          invitationsRepo.create(
            arn.value,
            MtdIt,
            mtdItId,
            mtdItId,
            "Erling Haal",
            "testAgentName",
            "agent@email.com",
            LocalDate.now(),
            Some("personal")
          )
        )

        val requestBody = Json.obj("uid" -> uid, "serviceKeys" -> Json.arr("HMRC-MADE-UP"))
        val result = doAgentPostRequest(fakeRequest.uri, requestBody)

        result.status shouldBe 403
      }
    }

    "return 404 status" when {

      "no invitations were found in the invitations collection for the given agent" in {
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
        givenAgentRecordFound(arn, agentRecordResponse)
        await(agentReferenceRepo.create(agentReferenceRecord))

        val requestBody = Json.obj("uid" -> uid, "serviceKeys" -> Json.arr("HMRC-MTD-IT"))
        val result = doAgentPostRequest(fakeRequest.uri, requestBody)

        result.status shouldBe 404
      }

      "the provided service key does not match with an existing invitation" in {
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
        givenAgentRecordFound(arn, agentRecordResponse)
        await(agentReferenceRepo.create(agentReferenceRecord))
        await(
          invitationsRepo.create(
            arn.value,
            MtdIt,
            mtdItId,
            mtdItId,
            "Erling Haal",
            "testAgentName",
            "agent@email.com",
            LocalDate.now(),
            Some("personal")
          )
        )

        val requestBody = Json.obj("uid" -> uid, "serviceKeys" -> Json.arr("HMRC-MTD-VAT"))
        val result = doAgentPostRequest(fakeRequest.uri, requestBody)

        result.status shouldBe 404
      }

      "the agent reference record could not be obtained for the given agent" in {
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
        givenAgentDetailsErrorResponse(arn, NOT_FOUND)
        await(
          invitationsRepo.create(
            arn.value,
            MtdIt,
            mtdItId,
            mtdItId,
            "Erling Haal",
            "testAgentName",
            "agent@email.com",
            LocalDate.now(),
            Some("personal")
          )
        )

        val requestBody = Json.obj("uid" -> uid, "serviceKeys" -> Json.arr("HMRC-MTD-IT"))
        val result = doAgentPostRequest(fakeRequest.uri, requestBody)

        result.status shouldBe 404
      }
    }
  }

}
