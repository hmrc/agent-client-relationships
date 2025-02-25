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

package uk.gov.hmrc.agentclientrelationships.services

import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.agentclientrelationships.mocks._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.{ActiveMainAgent, ClientDetailsStrideResponse}
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.{AgencyDetails, AgentDetailsDesResponse}
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.stride.InvitationWithAgentName
import uk.gov.hmrc.agentclientrelationships.support.{ResettingMockitoSugar, UnitSpec}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, CbcId, CgtRef, MtdItId, PlrId, PptRef, Vrn}
import uk.gov.hmrc.agentmtdidentifiers.model.Service._
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Instant, LocalDate}
import scala.concurrent.{ExecutionContext, Future}

class StrideClientDetailsServiceSpec
    extends UnitSpec
    with ResettingMockitoSugar
    with MockAgentFiRelationshipConnector
    with MockAgentAssuranceConnector
    with MockClientDetailsService
    with MockFindRelationshipsService
    with MockPartialAuthRepository
    with MockInvitationsRepository
    with MockValidationService {

  object TestService
      extends StrideClientDetailsService(
        invitationsRepository = mockInvitationsRepository,
        partialAuthRepository = mockPartialAuthRepository,
        agentFiRelationshipConnector = mockAgentFiRelationshipConnector,
        clientDetailsService = mockClientDetailsService,
        agentAssuranceConnector = mockAgentAssuranceConnector,
        findRelationshipsService = mockFindRelationshipService,
        validationService = mockValidationService
      )

  val testArn: Arn = Arn("ARN1234567890")
  val testArn2: Arn = Arn("ARN1234567891")
  val testName = "testClientName"
  val testOldInvitationId = "testOldInvitationId"

  val testNino: Nino = Nino("AB123456A")
  val testMtdItId: MtdItId = MtdItId("XAIT0000111122")

  val testVrn: Vrn = Vrn("1234567890")
  val testCbcId: CbcId = CbcId("XXCBC9872173612")
  val testCgtPdRef = CgtRef("XMCGTP123179159")
  val testPillar2Ref = PlrId("XQPLR0799747149")
  val testPptRef = PptRef("XKPPT0006812629")

  val itsaEnrolment: EnrolmentKey = EnrolmentKey(MtdIt, testMtdItId)
  val itsaSuppEnrolment: EnrolmentKey = EnrolmentKey(MtdItSupp, testMtdItId)

  val testAgentDetailsDesResponse: AgentDetailsDesResponse =
    AgentDetailsDesResponse(agencyDetails = AgencyDetails("ABC Ltd", ""), suspensionDetails = None)

  val itsaInvitation: Invitation = Invitation
    .createNew(testArn.value, MtdIt, testNino, testNino, testName, LocalDate.now(), Some("personal"))

  val itsaInvitationWithAgentName: InvitationWithAgentName =
    InvitationWithAgentName.fromInvitationAndAgentRecord(itsaInvitation, testAgentDetailsDesResponse)

  val testPartialauthRelationship: PartialAuthRelationship =
    PartialAuthRelationship(Instant.now, testArn2.value, HMRCMTDIT, testNino.value, active = true, Instant.now)

  implicit val ec: ExecutionContext = ExecutionContext.Implicits.global
  implicit val hc: HeaderCarrier = HeaderCarrier()

  "getClientDetailsWithCheck" when {
    "pending invitations exist for HMRC-MTD-IT" should {
      "return Some(ClientDetailsStrideResponse)" in {
        mockFindAllPendingForClient(testNino.value, Seq(HMRCMTDIT, HMRCMTDITSUPP))(Seq(itsaInvitation))
        mockGetAgentRecordWithChecks(testArn)(testAgentDetailsDesResponse)
        mockFindMainAgent(testNino.value)(Future.successful(Some(testPartialauthRelationship)))
        mockGetAgentRecordWithChecks(testArn2)(testAgentDetailsDesResponse)

        val testEk = EnrolmentKey(s"HMRC-MTD-IT~NINO~${testNino.value}")

        await(TestService.getClientDetailsWithChecks(testEk)) shouldBe Some(
          ClientDetailsStrideResponse(
            "testClientName",
            List(itsaInvitationWithAgentName),
            Some(ActiveMainAgent("ABC Ltd", "ARN1234567891", "HMRC-MTD-IT"))
          )
        )
      }
    }
    "pending invitations exist for HMRC-MTD-IT-SUPP" should {
      "return Some(ClientDetailsStrideResponse)" in {
        val itsaSuppPendingInvitation = itsaInvitation.copy(service = MtdItSupp.id)
        val itsaSuppPendingInvitationWithAgentName =
          InvitationWithAgentName.fromInvitationAndAgentRecord(itsaSuppPendingInvitation, testAgentDetailsDesResponse)
        mockFindAllPendingForClient(testNino.value, Seq(HMRCMTDIT, HMRCMTDITSUPP))(Seq(itsaSuppPendingInvitation))
        mockGetAgentRecordWithChecks(testArn)(testAgentDetailsDesResponse)
        mockFindMainAgent(testNino.value)(
          Future.successful(Some(testPartialauthRelationship.copy(arn = testArn.value)))
        )
        mockGetAgentRecordWithChecks(testArn)(testAgentDetailsDesResponse)

        val testEk = EnrolmentKey(s"HMRC-MTD-IT~NINO~${testNino.value}")

        await(TestService.getClientDetailsWithChecks(testEk)) shouldBe Some(
          ClientDetailsStrideResponse(
            "testClientName",
            List(itsaSuppPendingInvitationWithAgentName),
            Some(ActiveMainAgent("ABC Ltd", testArn.value, "HMRC-MTD-IT"))
          )
        )
      }
    }
    "pending invitations exist for PERSONAL-INCOME-RECORD" should {
      "return Some(ClientDetailsStrideResponse)" in {
        val irvPendingInvitation = itsaInvitation.copy(service = PersonalIncomeRecord.id)
        val irvPendingInvitationWithAgentName =
          InvitationWithAgentName.fromInvitationAndAgentRecord(irvPendingInvitation, testAgentDetailsDesResponse)
        mockFindAllPendingForClient(testNino.value, Seq(PersonalIncomeRecord.id))(Seq(irvPendingInvitation))
        mockGetAgentRecordWithChecks(testArn)(testAgentDetailsDesResponse)
        mockFindRelationshipForClient(testNino.value)(Some(IrvRelationship(testArn2)))
        mockGetAgentRecordWithChecks(testArn2)(testAgentDetailsDesResponse)

        val testEk = EnrolmentKey(s"PERSONAL-INCOME-RECORD~NINO~${testNino.value}")

        await(TestService.getClientDetailsWithChecks(testEk)) shouldBe Some(
          ClientDetailsStrideResponse(
            "testClientName",
            List(irvPendingInvitationWithAgentName),
            Some(ActiveMainAgent("ABC Ltd", testArn2.value, "PERSONAL-INCOME-RECORD"))
          )
        )
      }
    }

    "pending invitations exist for HMRC-MTD-VAT" should {
      "return Some(ClientDetailsStrideResponse)" in {
        val vatPendingInvitation = itsaInvitation.copy(service = HMRCMTDVAT)
        val vatPendingInvitationWithAgentName =
          InvitationWithAgentName.fromInvitationAndAgentRecord(vatPendingInvitation, testAgentDetailsDesResponse)
        mockFindAllPendingForClient(testVrn.value, Seq(HMRCMTDVAT))(Seq(vatPendingInvitation))
        mockGetAgentRecordWithChecks(testArn)(testAgentDetailsDesResponse)
        mockGetActiveRelationshipsForClient(testVrn, Vat)(
          Future.successful(Some(ActiveRelationship(testArn2, None, None)))
        )
        mockGetAgentRecordWithChecks(testArn2)(testAgentDetailsDesResponse)

        val testEk = EnrolmentKey(s"$HMRCMTDVAT~VRN~${testVrn.value}")

        await(TestService.getClientDetailsWithChecks(testEk)) shouldBe Some(
          ClientDetailsStrideResponse(
            "testClientName",
            List(vatPendingInvitationWithAgentName),
            Some(ActiveMainAgent("ABC Ltd", testArn2.value, "HMRC-MTD-VAT"))
          )
        )
      }
    }
    "pending invitations exist for HMRC-CGT-PD" should {
      "return Some(ClientDetailsStrideResponse)" in {
        val cgtPendingInvitation = itsaInvitation.copy(service = HMRCCGTPD)
        val cgtPendingInvitationWithAgentName =
          InvitationWithAgentName.fromInvitationAndAgentRecord(cgtPendingInvitation, testAgentDetailsDesResponse)
        mockFindAllPendingForClient(testCgtPdRef.value, Seq(HMRCCGTPD))(Seq(cgtPendingInvitation))
        mockGetAgentRecordWithChecks(testArn)(testAgentDetailsDesResponse)
        mockGetActiveRelationshipsForClient(testCgtPdRef, CapitalGains)(
          Future.successful(Some(ActiveRelationship(testArn2, None, None)))
        )
        mockGetAgentRecordWithChecks(testArn2)(testAgentDetailsDesResponse)

        val testEk = EnrolmentKey(s"$HMRCCGTPD~CGTPDRef~${testCgtPdRef.value}")

        await(TestService.getClientDetailsWithChecks(testEk)) shouldBe Some(
          ClientDetailsStrideResponse(
            "testClientName",
            List(cgtPendingInvitationWithAgentName),
            Some(ActiveMainAgent("ABC Ltd", testArn2.value, HMRCCGTPD))
          )
        )
      }
    }

    "pending invitations exist for HMRC-CBC-ORG" should {
      "return Some(ClientDetailsStrideResponse)" in {
        val cbcPendingInvitation = itsaInvitation.copy(service = HMRCCBCORG)
        val cbcPendingInvitationWithAgentName =
          InvitationWithAgentName.fromInvitationAndAgentRecord(cbcPendingInvitation, testAgentDetailsDesResponse)
        mockFindAllPendingForClient(testCbcId.value, Seq(HMRCCBCORG))(Seq(cbcPendingInvitation))
        mockGetAgentRecordWithChecks(testArn)(testAgentDetailsDesResponse)
        mockGetActiveRelationshipsForClient(testCbcId, Cbc)(
          Future.successful(Some(ActiveRelationship(testArn2, None, None)))
        )
        mockGetAgentRecordWithChecks(testArn2)(testAgentDetailsDesResponse)

        val testEk = EnrolmentKey(s"$HMRCCBCORG~cbcId~${testCbcId.value}")

        await(TestService.getClientDetailsWithChecks(testEk)) shouldBe Some(
          ClientDetailsStrideResponse(
            "testClientName",
            List(cbcPendingInvitationWithAgentName),
            Some(ActiveMainAgent("ABC Ltd", testArn2.value, HMRCCBCORG))
          )
        )
      }
    }

    "pending invitations exist for HMRC-PILLAR2-ORG" should {
      "return Some(ClientDetailsStrideResponse)" in {
        val pillar2PendingInvitation = itsaInvitation.copy(service = HMRCPILLAR2ORG)
        val pillar2PendingInvitationWithAgentName =
          InvitationWithAgentName.fromInvitationAndAgentRecord(pillar2PendingInvitation, testAgentDetailsDesResponse)
        mockFindAllPendingForClient(testPillar2Ref.value, Seq(HMRCPILLAR2ORG))(Seq(pillar2PendingInvitation))
        mockGetAgentRecordWithChecks(testArn)(testAgentDetailsDesResponse)
        mockGetActiveRelationshipsForClient(testPillar2Ref, Pillar2)(
          Future.successful(Some(ActiveRelationship(testArn2, None, None)))
        )
        mockGetAgentRecordWithChecks(testArn2)(testAgentDetailsDesResponse)

        val testEk = EnrolmentKey(s"$HMRCPILLAR2ORG~PLRID~${testPillar2Ref.value}")

        await(TestService.getClientDetailsWithChecks(testEk)) shouldBe Some(
          ClientDetailsStrideResponse(
            "testClientName",
            List(pillar2PendingInvitationWithAgentName),
            Some(ActiveMainAgent("ABC Ltd", testArn2.value, HMRCPILLAR2ORG))
          )
        )
      }
    }

    "pending invitations exist for HMRC-PPT-ORG" should {
      "return Some(ClientDetailsStrideResponse)" in {
        val pptPendingInvitation = itsaInvitation.copy(service = HMRCPPTORG)
        val pptPendingInvitationWithAgentName =
          InvitationWithAgentName.fromInvitationAndAgentRecord(pptPendingInvitation, testAgentDetailsDesResponse)
        mockFindAllPendingForClient(testPptRef.value, Seq(HMRCPPTORG))(Seq(pptPendingInvitation))
        mockGetAgentRecordWithChecks(testArn)(testAgentDetailsDesResponse)
        mockGetActiveRelationshipsForClient(testPptRef, Ppt)(
          Future.successful(Some(ActiveRelationship(testArn2, None, None)))
        )
        mockGetAgentRecordWithChecks(testArn2)(testAgentDetailsDesResponse)

        val testEk = EnrolmentKey(s"$HMRCPPTORG~EtmpRegistrationNumber~${testPptRef.value}")

        await(TestService.getClientDetailsWithChecks(testEk)) shouldBe Some(
          ClientDetailsStrideResponse(
            "testClientName",
            List(pptPendingInvitationWithAgentName),
            Some(ActiveMainAgent("ABC Ltd", testArn2.value, HMRCPPTORG))
          )
        )
      }
    }
  }
}
