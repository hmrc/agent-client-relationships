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

import play.api.test.FakeRequest
import uk.gov.hmrc.agentclientrelationships.model.{ClientTaxAgentsData, Invitation, Pending}
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.stubs.{AfiRelationshipStub, ClientDetailsStub, HIPAgentClientRelationshipStub}
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentmtdidentifiers.model._

import java.time.{Instant, ZoneOffset}

class ClientTaxAgentsDataControllerISpec
    extends BaseControllerISpec
    with ClientDetailsStub
    with HIPAgentClientRelationshipStub
    with AfiRelationshipStub
    with TestData {

  //  val partialAuthRepo: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]
  val invitationsRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]

  val clientName = "TestClientName"
  val agentName1 = "testAgentName"
  val agentName2 = "testAgentName2"
  val agentName3 = "testAgentName3"
  val agentEmail = "agent@email.com"
  val expiryDate = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime.plusSeconds(60).toLocalDate

  val testAgentRecord1: TestAgentDetailsDesResponse = TestAgentDetailsDesResponse(
    uniqueTaxReference = None,
    agencyDetails = Some(
      TestAgencyDetails(
        agencyName = Some(agentName1),
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
        agencyName = Some(agentName2),
        agencyEmail = None,
        agencyTelephone = None,
        agencyAddress = None
      )
    ),
    suspensionDetails = None
  )

  val testAgentRecord3: TestAgentDetailsDesResponse = TestAgentDetailsDesResponse(
    uniqueTaxReference = None,
    agencyDetails = Some(
      TestAgencyDetails(
        agencyName = Some(agentName3),
        agencyEmail = None,
        agencyTelephone = None,
        agencyAddress = None
      )
    ),
    suspensionDetails = None
  )

  val pendingVatInvitationAgent1: Invitation = Invitation
    .createNew(
      arn.value,
      Service.Vat,
      vrn,
      ClientIdentifier(vrn),
      clientName,
      agentName1,
      "testAgent@email.com",
      expiryDate,
      None
    )
    .copy(status = Pending)

  val pendingItsaInvitationAgent1: Invitation = Invitation
    .createNew(
      arn.value,
      Service.MtdIt,
      mtdItId,
      ClientIdentifier(mtdItId),
      clientName,
      agentName1,
      "testAgent@email.com",
      expiryDate,
      None
    )
    .copy(status = Pending)

  val pendingCbcInvitationAgent1: Invitation = Invitation
    .createNew(
      arn.value,
      Service.Cbc,
      cbcId,
      ClientIdentifier(cbcId),
      clientName,
      agentName1,
      "testAgent@email.com",
      expiryDate,
      None
    )
    .copy(status = Pending)

  val pendingVatInvitationAgent2: Invitation = Invitation
    .createNew(
      arn2.value,
      Service.Vat,
      vrn,
      ClientIdentifier(vrn),
      clientName,
      agentName2,
      "testAgent@email.com",
      expiryDate,
      None
    )
    .copy(status = Pending)

  val pendingItsaInvitationAgent2: Invitation = Invitation
    .createNew(
      arn2.value,
      Service.MtdIt,
      mtdItId,
      ClientIdentifier(mtdItId),
      clientName,
      agentName2,
      "testAgent@email.com",
      expiryDate,
      None
    )
    .copy(status = Pending)

  val pendingCbcInvitationAgent2: Invitation = Invitation
    .createNew(
      arn2.value,
      Service.Cbc,
      cbcId,
      ClientIdentifier(cbcId),
      clientName,
      agentName2,
      "testAgent@email.com",
      expiryDate,
      None
    )
    .copy(status = Pending)

  val testEndpoint = "/agent-client-relationships/client/authorisations-relationships"
  val fakeRequest = FakeRequest("GET", s"/agent-client-relationships/client/authorisations-relationships")

  s"GET $testEndpoint returns" when {
    "pending invitations for one agent and no relationships, no events" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)
      givenAuditConnector()

      invitationsRepo.collection
        .insertMany(Seq(pendingVatInvitationAgent1, pendingItsaInvitationAgent1 /*, pendingCbcInvitation*/ ))
        .toFuture()
        .futureValue

      // no relationships
      getActiveRelationshipFailsWith(vrn, 422)
      getActiveRelationshipFailsWith(mtdItId, 422)

      // TODO WG - no events
      // TODO UID is created each time
      givenAgentRecordFound(arn, testAgentRecord1)
      val result = doGetRequest(testEndpoint)
      result.status shouldBe 200

      // CHeck how many Agents data we have
      val clientTaxAgentsData = result.json.as[ClientTaxAgentsData]
      clientTaxAgentsData.agentsInvitations.agentsInvitations.size shouldBe 1

      // Check how many invitations for agent we have
      val agentNameInvitations =
        clientTaxAgentsData.agentsInvitations.agentsInvitations.find(x => x.agentName == agentName1).get.invitations
      agentNameInvitations.size shouldBe 2

    }

    "pending invitations for two agents and no relationships" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)
      givenAuditConnector()

      invitationsRepo.collection
        .insertMany(
          Seq(
            pendingVatInvitationAgent1,
            pendingVatInvitationAgent2,
            pendingItsaInvitationAgent1
          )
        )
        .toFuture()
        .futureValue

      // no relationships
      getActiveRelationshipFailsWith(vrn, 422)
      getActiveRelationshipFailsWith(mtdItId, 422)

      givenAgentRecordFound(arn, testAgentRecord1)
      givenAgentRecordFound(arn2, testAgentRecord2)

      val result = doGetRequest(testEndpoint)
      result.status shouldBe 200

      // CHeck how many Agents data we have
      val clientTaxAgentsData = result.json.as[ClientTaxAgentsData]
      clientTaxAgentsData.agentsInvitations.agentsInvitations.size shouldBe 2

      // Check how many invitations for agent we have
      val agentName1Invitations =
        clientTaxAgentsData.agentsInvitations.agentsInvitations.find(x => x.agentName == agentName1).get.invitations
      agentName1Invitations.size shouldBe 2

      val agentName2Invitations =
        clientTaxAgentsData.agentsInvitations.agentsInvitations.find(x => x.agentName == agentName2).get.invitations
      agentName2Invitations.size shouldBe 1

    }

    "pending invitations for suspended agents not returned" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)
      givenAuditConnector()

      invitationsRepo.collection
        .insertMany(
          Seq(
            pendingVatInvitationAgent1,
            pendingVatInvitationAgent2,
            pendingItsaInvitationAgent1
          )
        )
        .toFuture()
        .futureValue

      // no relationships
      getActiveRelationshipFailsWith(vrn, 422)
      getActiveRelationshipFailsWith(mtdItId, 422)

      givenAgentRecordFound(arn, testAgentRecord1)
      givenAgentRecordFound(
        arn2,
        testAgentRecord2.copy(suspensionDetails =
          Some(SuspensionDetails(suspensionStatus = true, regimes = Some(Set("AGSV"))))
        )
      )

      val result = doGetRequest(testEndpoint)
      result.status shouldBe 200

      // CHeck how many Agents data we have
      val clientTaxAgentsData = result.json.as[ClientTaxAgentsData]
      clientTaxAgentsData.agentsInvitations.agentsInvitations.size shouldBe 1

      // Check how many invitations for agent we have
      val agentName1Invitations =
        clientTaxAgentsData.agentsInvitations.agentsInvitations.find(x => x.agentName == agentName1).get.invitations
      agentName1Invitations.size shouldBe 2

      clientTaxAgentsData.agentsInvitations.agentsInvitations.find(x => x.agentName == agentName2) shouldBe None

    }

    "no pending invitations no retaionships" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)
      givenAuditConnector()

      // no relationships
      getActiveRelationshipFailsWith(vrn, 422)
      getActiveRelationshipFailsWith(mtdItId, 422)

      givenAgentRecordFound(arn, testAgentRecord1)
      givenAgentRecordFound(arn2, testAgentRecord2)

      val result = doGetRequest(testEndpoint)
      result.status shouldBe 200

      // CHeck how many Agents data we have
      val clientTaxAgentsData = result.json.as[ClientTaxAgentsData]
      clientTaxAgentsData.agentsInvitations.agentsInvitations.size shouldBe 0

    }

    "active authorisation for all supported tax ids" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)
      givenAuditConnector()

      // no relationships
      getItsaMainAndSupportingActiveRelationshipsViaClient(
        taxIdentifier = mtdItId,
        arnMain = arn,
        arnSup = arn2,
        activeOnly = false
      )
      getAllActiveRelationshipsViaClient(taxIdentifier = vrn, arn = arn, activeOnly = false)
      getAllActiveRelationshipsViaClient(taxIdentifier = utr, arn = arn, activeOnly = false)
      getAllActiveRelationshipsViaClient(taxIdentifier = urn, arn = arn2, activeOnly = false)
      getAllActiveRelationshipsViaClient(taxIdentifier = pptRef, arn = arn3, activeOnly = false)
      getAllActiveRelationshipsViaClient(taxIdentifier = cgtRef, arn = arn3, activeOnly = false)

      givenAgentRecordFound(arn, testAgentRecord1)
      givenAgentRecordFound(arn2, testAgentRecord2)
      givenAgentRecordFound(arn3, testAgentRecord3)

      val result = doGetRequest(testEndpoint)
      result.status shouldBe 200

      val clientTaxAgentsData = result.json.as[ClientTaxAgentsData]
      clientTaxAgentsData.agentsAuthorisations.agentsAuthorisations.size shouldBe 3 // for 3 agents data

      val agent1Authorisations =
        clientTaxAgentsData.agentsAuthorisations.agentsAuthorisations
          .find(x => x.arn == arn.value)
          .get
          .authorisations
      agent1Authorisations.size shouldBe 3
      agent1Authorisations.exists(_.service == Service.MtdIt.id) shouldBe true
      agent1Authorisations.exists(_.service == Service.Vat.id) shouldBe true
      agent1Authorisations.exists(_.service == Service.Trust.id) shouldBe true

      val agent2Authorisations =
        clientTaxAgentsData.agentsAuthorisations.agentsAuthorisations
          .find(x => x.arn == arn2.value)
          .get
          .authorisations
      agent2Authorisations.size shouldBe 2
      agent2Authorisations.exists(_.service == Service.MtdItSupp.id) shouldBe true
      agent2Authorisations.exists(_.service == Service.TrustNT.id) shouldBe true

      val agent3Authorisations =
        clientTaxAgentsData.agentsAuthorisations.agentsAuthorisations
          .find(x => x.arn == arn3.value)
          .get
          .authorisations
      agent3Authorisations.size shouldBe 2
      agent3Authorisations.exists(_.service == Service.Ppt.id) shouldBe true
      agent3Authorisations.exists(_.service == Service.CapitalGains.id) shouldBe true

    }

    "active authorisation for ITSA Main and no authorisations for other tax identifiers - NotFount" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)
      givenAuditConnector()

      // no relationships
      getAllActiveRelationshipsViaClient(taxIdentifier = mtdItId, arn = arn, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = vrn, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = utr, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = urn, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = pptRef, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = cgtRef, status = 422, activeOnly = false)

      givenAgentRecordFound(arn, testAgentRecord1)

      val result = doGetRequest(testEndpoint)
      result.status shouldBe 200

      val clientTaxAgentsData = result.json.as[ClientTaxAgentsData]
      clientTaxAgentsData.agentsInvitations.agentsInvitations.size shouldBe 0
      clientTaxAgentsData.agentsAuthorisations.agentsAuthorisations.size shouldBe 1

      val agentAuthorisations =
        clientTaxAgentsData.agentsAuthorisations.agentsAuthorisations
          .find(x => x.agentName == agentName1)
          .get
          .authorisations

      agentAuthorisations.size shouldBe 1
      agentAuthorisations.exists(_.service == Service.MtdIt.id) shouldBe true

    }

    "active authorisation for ITSA Main and Supporting and no authorisations for other tax identifiers - NotFount" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)
      givenAuditConnector()

      // no relationships
      getItsaMainAndSupportingActiveRelationshipsViaClient(
        taxIdentifier = mtdItId,
        arnMain = arn,
        arnSup = arn2,
        activeOnly = false
      )
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = vrn, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = utr, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = urn, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = pptRef, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = cgtRef, status = 422, activeOnly = false)

      givenAgentRecordFound(arn, testAgentRecord1)
      givenAgentRecordFound(arn2, testAgentRecord2)

      val result = doGetRequest(testEndpoint)
      result.status shouldBe 200

      val clientTaxAgentsData = result.json.as[ClientTaxAgentsData]
      clientTaxAgentsData.agentsInvitations.agentsInvitations.size shouldBe 0
      clientTaxAgentsData.agentsAuthorisations.agentsAuthorisations.size shouldBe 2

      val agent1Authorisations =
        clientTaxAgentsData.agentsAuthorisations.agentsAuthorisations
          .find(x => x.agentName == agentName1)
          .get
          .authorisations
      agent1Authorisations.size shouldBe 1
      agent1Authorisations.exists(_.service == Service.MtdIt.id) shouldBe true

      val agent2Authorisations =
        clientTaxAgentsData.agentsAuthorisations.agentsAuthorisations
          .find(x => x.agentName == agentName2)
          .get
          .authorisations
      agent2Authorisations.size shouldBe 1
      agent2Authorisations.exists(_.service == Service.MtdItSupp.id) shouldBe true

    }

    "active authorisation for ITSA Main, and do not return inactive for Supporting" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)
      givenAuditConnector()

      // no relationships
      getItsaMainActiveAndSupportingInactiveRelationshipsViaClient(
        taxIdentifier = mtdItId,
        arnMain = arn,
        arnSup = arn2,
        activeOnly = false
      )
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = vrn, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = utr, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = urn, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = pptRef, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = cgtRef, status = 422, activeOnly = false)

      givenAgentRecordFound(arn, testAgentRecord1)
      givenAgentRecordFound(arn2, testAgentRecord2)

      val result = doGetRequest(testEndpoint)
      result.status shouldBe 200

      val clientTaxAgentsData = result.json.as[ClientTaxAgentsData]
      clientTaxAgentsData.agentsInvitations.agentsInvitations.size shouldBe 0
      clientTaxAgentsData.agentsAuthorisations.agentsAuthorisations.size shouldBe 1

      val agent1Authorisations =
        clientTaxAgentsData.agentsAuthorisations.agentsAuthorisations
          .find(x => x.agentName == agentName1)
          .get
          .authorisations
      agent1Authorisations.size shouldBe 1
      agent1Authorisations.exists(_.service == Service.MtdIt.id) shouldBe true

      clientTaxAgentsData.agentsAuthorisations.agentsAuthorisations
        .exists(x => x.agentName == agentName2) shouldBe false

    }

    "active authorisation for ITSA Main and  no authorisations for other tax identifiers - NotFount or Suspended" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)
      givenAuditConnector()

      // no relationships
      getAllActiveRelationshipsViaClient(taxIdentifier = mtdItId, arn = arn, activeOnly = false)
      getAllActiveRelationshipFailsWithSuspended(taxIdentifier = vrn, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = utr, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = urn, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = pptRef, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = cgtRef, status = 422, activeOnly = false)

      givenAgentRecordFound(arn, testAgentRecord1)

      val result = doGetRequest(testEndpoint)
      result.status shouldBe 200

      val clientTaxAgentsData = result.json.as[ClientTaxAgentsData]
      clientTaxAgentsData.agentsInvitations.agentsInvitations.size shouldBe 0
      clientTaxAgentsData.agentsAuthorisations.agentsAuthorisations.size shouldBe 1

      val agentAuthorisations =
        clientTaxAgentsData.agentsAuthorisations.agentsAuthorisations
          .find(x => x.agentName == agentName1)
          .get
          .authorisations

      agentAuthorisations.size shouldBe 1
      agentAuthorisations.exists(_.service == Service.MtdIt.id) shouldBe true

    }

    "active authorisation for ITSA Main and not found active relationship" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)
      givenAuditConnector()

      // no relationships
      getAllActiveRelationshipsViaClient(taxIdentifier = mtdItId, arn = arn, activeOnly = false)
      getAllActiveRelationshipFailsWith(taxIdentifier = vrn, status = 404, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = utr, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = urn, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = pptRef, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = cgtRef, status = 422, activeOnly = false)

      givenAgentRecordFound(arn, testAgentRecord1)

      val result = doGetRequest(testEndpoint)
      result.status shouldBe 200

      val clientTaxAgentsData = result.json.as[ClientTaxAgentsData]
      clientTaxAgentsData.agentsInvitations.agentsInvitations.size shouldBe 0
      clientTaxAgentsData.agentsAuthorisations.agentsAuthorisations.size shouldBe 1

      val agentAuthorisations =
        clientTaxAgentsData.agentsAuthorisations.agentsAuthorisations
          .find(x => x.agentName == agentName1)
          .get
          .authorisations

      agentAuthorisations.size shouldBe 1
      agentAuthorisations.exists(_.service == Service.MtdIt.id) shouldBe true

    }

    "BadRequest 400 if authorisation check returns  error BadRequest 400  for one of the tax identifiers" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)
      givenAuditConnector()

      // no relationships
      getAllActiveRelationshipsViaClient(taxIdentifier = mtdItId, arn = arn, activeOnly = false)
      getAllActiveRelationshipFailsWith(taxIdentifier = vrn, status = 400, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = utr, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = urn, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = pptRef, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = cgtRef, status = 422, activeOnly = false)

      givenAgentRecordFound(arn, testAgentRecord1)

      val result = doGetRequest(testEndpoint)
      result.status shouldBe 400

    }

    "ServiceUnavailable 503  if authorisation check returns any error other error 401  for one of the tax identifiers" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)
      givenAuditConnector()

      // no relationships
      getAllActiveRelationshipsViaClient(taxIdentifier = mtdItId, arn = arn, activeOnly = false)
      getAllActiveRelationshipFailsWith(taxIdentifier = vrn, status = 401, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = utr, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = urn, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = pptRef, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = cgtRef, status = 422, activeOnly = false)

      givenAgentRecordFound(arn, testAgentRecord1)

      val result = doGetRequest(testEndpoint)
      result.status shouldBe 503

    }

    "ServiceUnavailable 503 if agentDetails check returns any error 404 for one of the agents" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)
      givenAuditConnector()

      // no relationships
      getAllActiveRelationshipsViaClient(taxIdentifier = mtdItId, arn = arn, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = vrn, status = 404, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = utr, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = urn, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = pptRef, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = cgtRef, status = 422, activeOnly = false)

      givenAgentDetailsErrorResponse(arn, 404)

      val result = doGetRequest(testEndpoint)
      result.status shouldBe 503

    }

    "BadRequest if authorisation check returns Bad Request 400  for one of the tax identifiers and agentcheck 404" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)
      givenAuditConnector()

      // no relationships
      getAllActiveRelationshipsViaClient(taxIdentifier = mtdItId, arn = arn, activeOnly = false)
      getAllActiveRelationshipFailsWith(taxIdentifier = vrn, status = 400, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = utr, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = urn, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = pptRef, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = cgtRef, status = 422, activeOnly = false)

      givenAgentDetailsErrorResponse(arn, 404)

      val result = doGetRequest(testEndpoint)
      result.status shouldBe 400

    }

    // TODO WG - fix that with next iteration
    "last error from authorisation check - depends what future finish first " in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)
      givenAuditConnector()

      // no relationships
      getAllActiveRelationshipsViaClient(taxIdentifier = mtdItId, arn = arn, activeOnly = false)
      getAllActiveRelationshipFailsWith(taxIdentifier = vrn, status = 400, activeOnly = false)
      getAllActiveRelationshipFailsWith(taxIdentifier = utr, status = 401, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = urn, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = pptRef, status = 422, activeOnly = false)
      getAllActiveRelationshipFailsWithNotFound(taxIdentifier = cgtRef, status = 422, activeOnly = false)

      givenAgentRecordFound(arn, testAgentRecord1)

      val result = doGetRequest(testEndpoint)
      result.status should (be(400) or be(503))

    }

  }

  s"GET $testEndpoint " should {
    "return Unauthorised" when {
      "user is not signed in" in {
        requestIsNotAuthenticated()
        givenAuditConnector()

        val result = doGetRequest(testEndpoint)
        result.status shouldBe 401
      }
    }
  }

}
