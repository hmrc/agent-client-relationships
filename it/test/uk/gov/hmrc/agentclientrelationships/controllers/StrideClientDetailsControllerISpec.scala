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
import play.api.test.FakeRequest
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.{ActiveMainAgent, ClientDetailsStrideResponse}
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.{AgencyDetails, AgentDetailsDesResponse}
import uk.gov.hmrc.agentclientrelationships.model.stride.InvitationWithAgentName
import uk.gov.hmrc.agentclientrelationships.model.{Invitation, PartialAuthRelationship, Pending}
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentclientrelationships.stubs.{AfiRelationshipStub, ClientDetailsStub, HIPAgentClientRelationshipStub}
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{Cbc, CbcNonUk}
import uk.gov.hmrc.agentmtdidentifiers.model.{Identifier, SuspensionDetails}

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate}

class StrideClientDetailsControllerISpec
    extends BaseControllerISpec
    with ClientDetailsStub
    with HIPAgentClientRelationshipStub
    with AfiRelationshipStub {

  val partialAuthRepo: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]
  val invitationsRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]

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

  val pendingInvitation: Invitation = Invitation(
    "123",
    arn.value,
    "HMRC-MTD-VAT",
    vrn.value,
    "vrn",
    vrn.value,
    "vrn",
    "Macrosoft",
    "testAgentName",
    "agent@email.com",
    Pending,
    None,
    Some("personal"),
    LocalDate.parse("2020-01-01"),
    Instant.now().truncatedTo(ChronoUnit.SECONDS),
    Instant.now().truncatedTo(ChronoUnit.SECONDS)
  )

  val partialAuthRelationship: PartialAuthRelationship = PartialAuthRelationship(
    Instant.now().truncatedTo(ChronoUnit.SECONDS),
    arn2.value,
    "HMRC-MTD-IT",
    nino.value,
    active = true,
    lastUpdated = Instant.now().truncatedTo(ChronoUnit.SECONDS)
  )

  def agentDetailsDesResponse(suspended: Boolean = false) = AgentDetailsDesResponse(
    agencyDetails = AgencyDetails("ABC Ltd", ""),
    suspensionDetails = Option(SuspensionDetails(suspended, Some(Set("AGSV"))))
  )

  def invitationWithAgentName(invitation: Invitation, suspended: Boolean = false) =
    InvitationWithAgentName.fromInvitationAndAgentRecord(invitation, agentDetailsDesResponse(suspended))

  val testEndpoint = "/agent-client-relationships/stride/client-details/service/"

  def makeRequestUrl(service: String, clientIdType: String, clientId: String): String =
    s"$testEndpoint$service/client/$clientIdType/$clientId"

  s"GET $testEndpoint" should {
    "return Unauthorised" when {
      "user is not signed in" in {

        requestIsNotAuthenticated()
        givenAuditConnector()

        val result = doGetRequest(makeRequestUrl("HMRC-MTD-IT", "NI", s"$nino"))
        result.status shouldBe 401
      }
    }

    "return BadRequest" when {
      "invalid parameters" in {
        givenAuditConnector()

        val result = doGetRequest(makeRequestUrl("HMRC-MTD-IT", "VRN", s"$nino"))
        result.status shouldBe 400
      }
    }

    "return Not Found" when {
      "no invitations exist for HMRC-MTD-VAT and client unknown in HOD" in {
        val req = FakeRequest()
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        givenVatCustomerInfoError(vrn.value, 404)
        getActiveRelationshipsViaClient(vrn, arn)
        givenAgentRecordFound(
          arn,
          testAgentRecord
        )
        val result = doGetRequest(makeRequestUrl("HMRC-MTD-VAT", "VRN", s"${vrn.value}"))
        result.status shouldBe 404
      }
    }

    "return OK" when {
      "no invitations exist for HMRC-MTD-VAT but client is known in HOD" in {
        val req = FakeRequest()
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        givenVatCustomerInfoExists(vrn.value)
        getActiveRelationshipsViaClient(vrn, arn)
        givenAgentRecordFound(
          arn,
          testAgentRecord
        )
        val result = doGetRequest(makeRequestUrl("HMRC-MTD-VAT", "VRN", s"${vrn.value}"))
        result.status shouldBe 200

        result.body shouldBe """{"clientName":"CFG Solutions","pendingInvitations":[],"activeMainAgent":{"agentName":"ABC Ltd","arn":"AARN0000002","service":"HMRC-MTD-VAT"}}"""
      }
    }

    "return OK" when {
      "no invitations exist for HMRC-MTD-VAT but client is known in HOD and no existing main agent" in {
        val req = FakeRequest()
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        givenVatCustomerInfoExists(vrn.value)
        getActiveRelationshipFailsWith(vrn, 422)
        givenAgentRecordFound(
          arn,
          testAgentRecord
        )
        val result = doGetRequest(makeRequestUrl("HMRC-MTD-VAT", "VRN", s"${vrn.value}"))
        result.status shouldBe 200

        result.body shouldBe """{"clientName":"CFG Solutions","pendingInvitations":[]}"""
      }

      "pending invitations exist for HMRC-MTD-VAT and no active relationships" in {
        val req = FakeRequest()
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        invitationsRepo.collection.insertOne(pendingInvitation).toFuture().futureValue

        getActiveRelationshipFailsWith(vrn, 422)
        givenAgentRecordFound(
          arn,
          testAgentRecord
        )
        val result = doGetRequest(makeRequestUrl("HMRC-MTD-VAT", "VRN", s"${vrn.value}"))
        result.status shouldBe 200

        result.body shouldBe Json
          .toJson(
            ClientDetailsStrideResponse(
              clientName = pendingInvitation.clientName,
              pendingInvitations = Seq(invitationWithAgentName(pendingInvitation)),
              activeMainAgent = None
            )
          )
          .toString()
      }

      "pending invitations exist for HMRC-MTD-VAT and there is an active relationship" in {
        val req = FakeRequest()
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        invitationsRepo.collection.insertOne(pendingInvitation).toFuture().futureValue

        givenAgentRecordFound(
          arn,
          testAgentRecord
        )

        getActiveRelationshipsViaClient(vrn, arn2)

        givenAgentRecordFound(
          arn2,
          testAgentRecord
        )

        val result = doGetRequest(makeRequestUrl("HMRC-MTD-VAT", "VRN", s"${vrn.value}"))
        result.status shouldBe 200

        result.body shouldBe Json
          .toJson(
            ClientDetailsStrideResponse(
              clientName = pendingInvitation.clientName,
              pendingInvitations = Seq(invitationWithAgentName(pendingInvitation)),
              activeMainAgent = Some(ActiveMainAgent(agentName = "ABC Ltd", arn2.value, "HMRC-MTD-VAT"))
            )
          )
          .toString()
      }

      "pending invitations exist for HMRC-MTD-VAT from a suspended agent and there is an active relationship" in {
        val req = FakeRequest()
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        givenVatCustomerInfoExists(vrn.value)

        invitationsRepo.collection.insertOne(pendingInvitation).toFuture().futureValue

        givenAgentRecordFound(
          arn,
          testAgentRecord.copy(suspensionDetails =
            Some(SuspensionDetails(suspensionStatus = true, regimes = Some(Set("AGSV"))))
          )
        )

        getActiveRelationshipsViaClient(vrn, arn2)

        givenAgentRecordFound(
          arn2,
          testAgentRecord
        )

        val result = doGetRequest(makeRequestUrl("HMRC-MTD-VAT", "VRN", s"${vrn.value}"))
        result.status shouldBe 200

        result.body shouldBe Json
          .toJson(
            ClientDetailsStrideResponse(
              clientName = "CFG Solutions",
              pendingInvitations = Seq(),
              activeMainAgent = Some(ActiveMainAgent(agentName = "ABC Ltd", arn2.value, "HMRC-MTD-VAT"))
            )
          )
          .toString()
      }

      "pending invitations exist for HMRC-MTD-IT and there is an active partial-auth relationship" in {
        val req = FakeRequest()
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        val altItsaPendingInvitation =
          pendingInvitation.copy(service = "HMRC-MTD-IT", suppliedClientId = nino.value, clientId = nino.value)

        invitationsRepo.collection.insertOne(altItsaPendingInvitation).toFuture().futureValue

        partialAuthRepo.collection.insertOne(partialAuthRelationship).toFuture().futureValue

        givenAgentRecordFound(
          arn,
          testAgentRecord
        )

        givenAgentRecordFound(
          arn2,
          testAgentRecord
        )

        val result = doGetRequest(makeRequestUrl("HMRC-MTD-IT", "NI", s"${nino.value}"))
        result.status shouldBe 200

        result.body shouldBe Json
          .toJson(
            ClientDetailsStrideResponse(
              clientName = altItsaPendingInvitation.clientName,
              pendingInvitations = Seq(invitationWithAgentName(altItsaPendingInvitation)),
              activeMainAgent = Some(ActiveMainAgent(agentName = "ABC Ltd", arn2.value, "HMRC-MTD-IT"))
            )
          )
          .toString()
      }

      "pending invitations exist for HMRC-MTD-IT and there is a MTD relationship" in {
        val req = FakeRequest()
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        val itsaPendingInvitation =
          pendingInvitation.copy(service = "HMRC-MTD-IT", suppliedClientId = nino.value, clientId = mtdItId.value)

        invitationsRepo.collection.insertOne(itsaPendingInvitation).toFuture().futureValue

        givenAgentRecordFound(
          arn,
          testAgentRecord
        )

        givenMtdItIdIsKnownFor(nino, mtdItId)
        getActiveRelationshipsViaClient(mtdItId, arn2)

        givenAgentRecordFound(
          arn2,
          testAgentRecord
        )

        val result = doGetRequest(makeRequestUrl("HMRC-MTD-IT", "NI", s"${nino.value}"))
        result.status shouldBe 200

        result.body shouldBe Json
          .toJson(
            ClientDetailsStrideResponse(
              clientName = itsaPendingInvitation.clientName,
              pendingInvitations = Seq(invitationWithAgentName(itsaPendingInvitation)),
              activeMainAgent = Some(ActiveMainAgent(agentName = "ABC Ltd", arn2.value, "HMRC-MTD-IT"))
            )
          )
          .toString()
      }

      "pending invitations exist for PERSONAL-INCOME-RECORD and there is an IRV relationship" in {
        val req = FakeRequest()
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        val irvPendingInvitation =
          pendingInvitation.copy(
            service = "PERSONAL-INCOME-RECORD",
            suppliedClientId = nino.value,
            clientId = nino.value
          )

        invitationsRepo.collection.insertOne(irvPendingInvitation).toFuture().futureValue

        givenAgentRecordFound(
          arn,
          testAgentRecord
        )

        givenAfiRelationshipForClientIsActive(arn2, "PERSONAL-INCOME-RECORD", nino.value, fromCesa = true)

        givenAgentRecordFound(
          arn2,
          testAgentRecord
        )

        val result = doGetRequest(makeRequestUrl("PERSONAL-INCOME-RECORD", "NINO", s"${nino.value}"))
        result.status shouldBe 200

        result.body shouldBe Json
          .toJson(
            ClientDetailsStrideResponse(
              clientName = irvPendingInvitation.clientName,
              pendingInvitations = Seq(invitationWithAgentName(irvPendingInvitation)),
              activeMainAgent = Some(ActiveMainAgent(agentName = "ABC Ltd", arn2.value, "PERSONAL-INCOME-RECORD"))
            )
          )
          .toString()
      }

      "pending invitations exist for HMRC-CBC-ORG and there is an active CBC relationship" in {
        val req = FakeRequest()
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        val cbcPendingInvitation =
          pendingInvitation.copy(
            service = "HMRC-CBC-ORG",
            suppliedClientId = cbcId.value,
            clientId = cbcId.value
          )

        invitationsRepo.collection.insertOne(cbcPendingInvitation).toFuture().futureValue

        givenAgentRecordFound(
          arn,
          testAgentRecord
        )

        givenKnownFactsQuery(Cbc, cbcId, Some(Seq(Identifier("cbcId", cbcId.value))))

        getActiveRelationshipsViaClient(cbcId, arn2)

        givenAgentRecordFound(
          arn2,
          testAgentRecord
        )

        val result = doGetRequest(makeRequestUrl("HMRC-CBC-ORG", "cbcId", s"${cbcId.value}"))
        result.status shouldBe 200

        result.body shouldBe Json
          .toJson(
            ClientDetailsStrideResponse(
              clientName = cbcPendingInvitation.clientName,
              pendingInvitations = Seq(invitationWithAgentName(cbcPendingInvitation)),
              activeMainAgent = Some(ActiveMainAgent(agentName = "ABC Ltd", arn2.value, "HMRC-CBC-ORG"))
            )
          )
          .toString()
      }

      "pending invitations exist for HMRC-CBC-NONUK-ORG and there is an active CBC relationship" in {
        val req = FakeRequest()
        givenAuthorisedAsStrideUser(req, "user-123")
        givenAuditConnector()

        val cbcPendingInvitation =
          pendingInvitation.copy(
            service = "HMRC-CBC-NONUK-ORG",
            suppliedClientId = cbcId.value,
            clientId = cbcId.value
          )

        invitationsRepo.collection.insertOne(cbcPendingInvitation).toFuture().futureValue

        givenAgentRecordFound(
          arn,
          testAgentRecord
        )

        givenCbcUkDoesNotExistInES(cbcId)
        givenKnownFactsQuery(CbcNonUk, cbcId, Some(Seq(Identifier("cbcId", cbcId.value))))

        getActiveRelationshipsViaClient(cbcId, arn2)

        givenAgentRecordFound(
          arn2,
          testAgentRecord
        )

        val result = doGetRequest(makeRequestUrl("HMRC-CBC-ORG", "cbcId", s"${cbcId.value}"))
        result.status shouldBe 200

        result.body shouldBe Json
          .toJson(
            ClientDetailsStrideResponse(
              clientName = cbcPendingInvitation.clientName,
              pendingInvitations = Seq(invitationWithAgentName(cbcPendingInvitation)),
              activeMainAgent = Some(ActiveMainAgent(agentName = "ABC Ltd", arn2.value, "HMRC-CBC-NONUK-ORG"))
            )
          )
          .toString()
      }
    }
  }

}
