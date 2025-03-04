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

  s"GET $testEndpoint" should {
    "return Unauthorised" when {
      "user is not signed in" in {
        requestIsNotAuthenticated()
        givenAuditConnector()

        val result = doGetRequest(testEndpoint)
        result.status shouldBe 401
      }
    }

    "return OK" when {
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
    }
  }

}
