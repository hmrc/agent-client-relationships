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

import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers.await
import play.api.test.Helpers.defaultAwaitTimeout
import uk.gov.hmrc.agentclientrelationships.mocks._
import uk.gov.hmrc.agentclientrelationships.model.RelationshipFailureResponse.ErrorRetrievingAgentDetails
import uk.gov.hmrc.agentclientrelationships.model.RelationshipFailureResponse.ErrorRetrievingRelationship
import uk.gov.hmrc.agentclientrelationships.model.RelationshipFailureResponse.RelationshipNotFound
import uk.gov.hmrc.agentclientrelationships.model.RelationshipFailureResponse.TaxIdentifierError
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ActiveMainAgent
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientDetailsNotFound
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientDetailsResponse
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientDetailsStrideResponse
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.KnownFactType.PostalCode
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service._
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.CbcId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.CgtRef
import uk.gov.hmrc.agentclientrelationships.model.identifiers.MtdItId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoType
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.model.identifiers.PlrId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.PptRef
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Vrn
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgencyDetails
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.agentclientrelationships.model.stride.ClientRelationship
import uk.gov.hmrc.agentclientrelationships.model.stride.InvitationWithAgentName
import uk.gov.hmrc.agentclientrelationships.model.stride.IrvAgent
import uk.gov.hmrc.agentclientrelationships.model.stride.IrvRelationships
import uk.gov.hmrc.agentclientrelationships.model.stride.RelationshipSource.AfrRelationshipRepo
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

import java.time.Instant
import java.time.LocalDate
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class StrideClientDetailsServiceSpec
extends UnitSpec
with ResettingMockitoSugar
with MockAgentFiRelationshipConnector
with MockAgentAssuranceService
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
    agentAssuranceService = mockAgentAssuranceService,
    findRelationshipsService = mockFindRelationshipService,
    validationService = mockValidationService
  )

  val testArn: Arn = Arn("ARN1234567890")
  val testArn2: Arn = Arn("ARN1234567891")
  val testName = "testClientName"
  val testAgentName = "testAgentName"
  val testAgentEmail = "agent@email.com"
  val testOldInvitationId = "testOldInvitationId"

  val testNino: NinoWithoutSuffix = NinoWithoutSuffix("AB123456")
  val testMtdItId: MtdItId = MtdItId("XAIT0000111122")

  val testVrn: Vrn = Vrn("1234567890")
  val testCbcId: CbcId = CbcId("XXCBC9872173612")
  val testCgtPdRef: CgtRef = CgtRef("XMCGTP123179159")
  val testPillar2Ref: PlrId = PlrId("XQPLR0799747149")
  val testPptRef: PptRef = PptRef("XKPPT0006812629")

  val itsaEnrolment: EnrolmentKey = EnrolmentKey(MtdIt, testMtdItId)
  val itsaSuppEnrolment: EnrolmentKey = EnrolmentKey(MtdItSupp, testMtdItId)

  val testAgentDetailsDesResponse: AgentDetailsDesResponse = AgentDetailsDesResponse(
    agencyDetails = AgencyDetails("ABC Ltd", ""),
    suspensionDetails = None
  )

  val testClientDetailsResponse: ClientDetailsResponse = ClientDetailsResponse(
    testName,
    None,
    isOverseas = Some(false),
    Seq("AA11AA"),
    Some(PostalCode)
  )

  val testClientRelationship: ClientRelationship = ClientRelationship(
    arn = testArn,
    dateTo = None,
    dateFrom = None,
    authProfile = None,
    isActive = true,
    relationshipSource = AfrRelationshipRepo,
    service = None
  )

  val itsaInvitation: Invitation = Invitation.createNew(
    testArn.value,
    MtdIt,
    testNino,
    testNino,
    testName,
    testAgentName,
    testAgentEmail,
    LocalDate.now(),
    Some("personal")
  )

  val itsaInvitationWithAgentName: InvitationWithAgentName = InvitationWithAgentName.fromInvitationAndAgentRecord(
    itsaInvitation,
    testAgentDetailsDesResponse
  )

  val testPartialauthRelationship: PartialAuthRelationship = PartialAuthRelationship(
    Instant.now,
    testArn2.value,
    HMRCMTDIT,
    testNino.value,
    active = true,
    Instant.now
  )

  implicit val ec: ExecutionContext = ExecutionContext.Implicits.global
  implicit val request: RequestHeader = FakeRequest()

  "getClientDetailsWithCheck" when {
    "pending invitations exist for HMRC-MTD-IT" should {
      "return Some(ClientDetailsStrideResponse)" in {
        mockFindAllBy(
          None,
          Seq(HMRCMTDIT, HMRCMTDITSUPP),
          Seq(testNino.value),
          Some(Pending),
          isSuppliedClientId = true
        )(Future.successful(Seq(itsaInvitation)))
        mockGetNonSuspendedAgentRecord(testArn)(Some(testAgentDetailsDesResponse))
        mockFindMainAgent(testNino.value)(Future.successful(Some(testPartialauthRelationship)))
        mockGetAgentRecord(testArn2)(testAgentDetailsDesResponse)

        val testEk = EnrolmentKey(s"HMRC-MTD-IT~NINO~${testNino.value}")

        await(TestService.getClientDetailsWithChecks(testEk)) shouldBe
          Some(
            ClientDetailsStrideResponse(
              "testClientName",
              List(itsaInvitationWithAgentName),
              Some(
                ActiveMainAgent(
                  "ABC Ltd",
                  "ARN1234567891",
                  "HMRC-MTD-IT"
                )
              )
            )
          )
      }
    }
    "pending invitations exist for HMRC-MTD-IT-SUPP" should {
      "return Some(ClientDetailsStrideResponse)" in {
        val itsaSuppPendingInvitation = itsaInvitation.copy(service = MtdItSupp.id)
        val itsaSuppPendingInvitationWithAgentName = InvitationWithAgentName.fromInvitationAndAgentRecord(
          itsaSuppPendingInvitation,
          testAgentDetailsDesResponse
        )
        mockFindAllBy(
          None,
          Seq(HMRCMTDIT, HMRCMTDITSUPP),
          Seq(testNino.value),
          Some(Pending),
          isSuppliedClientId = true
        )(Future.successful(Seq(itsaSuppPendingInvitation)))
        mockGetNonSuspendedAgentRecord(testArn)(Some(testAgentDetailsDesResponse))
        mockFindMainAgent(testNino.value)(
          Future.successful(Some(testPartialauthRelationship.copy(arn = testArn.value)))
        )
        mockGetAgentRecord(testArn)(testAgentDetailsDesResponse)

        val testEk = EnrolmentKey(s"HMRC-MTD-IT~NINO~${testNino.value}")

        await(TestService.getClientDetailsWithChecks(testEk)) shouldBe
          Some(
            ClientDetailsStrideResponse(
              "testClientName",
              List(itsaSuppPendingInvitationWithAgentName),
              Some(
                ActiveMainAgent(
                  "ABC Ltd",
                  testArn.value,
                  "HMRC-MTD-IT"
                )
              )
            )
          )
      }
    }
    "pending invitations exist for PERSONAL-INCOME-RECORD" should {
      "return Some(ClientDetailsStrideResponse)" in {
        val irvPendingInvitation = itsaInvitation.copy(service = PersonalIncomeRecord.id)
        val irvPendingInvitationWithAgentName = InvitationWithAgentName.fromInvitationAndAgentRecord(
          irvPendingInvitation,
          testAgentDetailsDesResponse
        )
        mockFindAllBy(
          None,
          Seq(PersonalIncomeRecord.id),
          Seq(testNino.value),
          Some(Pending),
          isSuppliedClientId = true
        )(Future.successful(Seq(irvPendingInvitation)))
        mockGetNonSuspendedAgentRecord(testArn)(Some(testAgentDetailsDesResponse))
        mockFindRelationshipForClient(testNino.value)(Right(List(testClientRelationship)))
        mockGetAgentRecord(testArn)(testAgentDetailsDesResponse)

        val testEk = EnrolmentKey(s"PERSONAL-INCOME-RECORD~NINO~${testNino.value}")

        await(TestService.getClientDetailsWithChecks(testEk)) shouldBe
          Some(
            ClientDetailsStrideResponse(
              "testClientName",
              List(irvPendingInvitationWithAgentName),
              Some(
                ActiveMainAgent(
                  "ABC Ltd",
                  testArn.value,
                  "PERSONAL-INCOME-RECORD"
                )
              )
            )
          )
      }
    }

    "pending invitations exist for HMRC-MTD-VAT" should {
      "return Some(ClientDetailsStrideResponse)" in {
        val vatPendingInvitation = itsaInvitation.copy(service = HMRCMTDVAT)
        val vatPendingInvitationWithAgentName = InvitationWithAgentName.fromInvitationAndAgentRecord(
          vatPendingInvitation,
          testAgentDetailsDesResponse
        )
        mockFindAllBy(
          None,
          Seq(HMRCMTDVAT),
          Seq(testVrn.value),
          Some(Pending),
          isSuppliedClientId = true
        )(Future.successful(Seq(vatPendingInvitation)))
        mockGetNonSuspendedAgentRecord(testArn)(Some(testAgentDetailsDesResponse))
        mockGetActiveRelationshipsForClient(testVrn, Vat)(
          Future.successful(
            Some(
              ActiveRelationship(
                testArn2,
                None,
                None
              )
            )
          )
        )
        mockGetAgentRecord(testArn2)(testAgentDetailsDesResponse)

        val testEk = EnrolmentKey(s"$HMRCMTDVAT~VRN~${testVrn.value}")

        await(TestService.getClientDetailsWithChecks(testEk)) shouldBe
          Some(
            ClientDetailsStrideResponse(
              "testClientName",
              List(vatPendingInvitationWithAgentName),
              Some(
                ActiveMainAgent(
                  "ABC Ltd",
                  testArn2.value,
                  "HMRC-MTD-VAT"
                )
              )
            )
          )
      }
    }
    "pending invitations exist for HMRC-CGT-PD" should {
      "return Some(ClientDetailsStrideResponse)" in {
        val cgtPendingInvitation = itsaInvitation.copy(service = HMRCCGTPD)
        val cgtPendingInvitationWithAgentName = InvitationWithAgentName.fromInvitationAndAgentRecord(
          cgtPendingInvitation,
          testAgentDetailsDesResponse
        )
        mockFindAllBy(
          None,
          Seq(HMRCCGTPD),
          Seq(testCgtPdRef.value),
          Some(Pending),
          isSuppliedClientId = true
        )(Future.successful(Seq(cgtPendingInvitation)))
        mockGetNonSuspendedAgentRecord(testArn)(Some(testAgentDetailsDesResponse))
        mockGetActiveRelationshipsForClient(testCgtPdRef, CapitalGains)(
          Future.successful(
            Some(
              ActiveRelationship(
                testArn2,
                None,
                None
              )
            )
          )
        )
        mockGetAgentRecord(testArn2)(testAgentDetailsDesResponse)

        val testEk = EnrolmentKey(s"$HMRCCGTPD~CGTPDRef~${testCgtPdRef.value}")

        await(TestService.getClientDetailsWithChecks(testEk)) shouldBe
          Some(
            ClientDetailsStrideResponse(
              "testClientName",
              List(cgtPendingInvitationWithAgentName),
              Some(
                ActiveMainAgent(
                  "ABC Ltd",
                  testArn2.value,
                  HMRCCGTPD
                )
              )
            )
          )
      }
    }

    "pending invitations exist for HMRC-CBC-ORG" should {
      "return Some(ClientDetailsStrideResponse)" in {
        val cbcPendingInvitation = itsaInvitation.copy(service = HMRCCBCORG)
        val cbcPendingInvitationWithAgentName = InvitationWithAgentName.fromInvitationAndAgentRecord(
          cbcPendingInvitation,
          testAgentDetailsDesResponse
        )
        mockFindAllBy(
          None,
          Seq(HMRCCBCORG),
          Seq(testCbcId.value),
          Some(Pending),
          isSuppliedClientId = true
        )(Future.successful(Seq(cbcPendingInvitation)))
        mockGetNonSuspendedAgentRecord(testArn)(Some(testAgentDetailsDesResponse))
        mockGetActiveRelationshipsForClient(testCbcId, Cbc)(
          Future.successful(
            Some(
              ActiveRelationship(
                testArn2,
                None,
                None
              )
            )
          )
        )
        mockGetAgentRecord(testArn2)(testAgentDetailsDesResponse)

        val testEk = EnrolmentKey(s"$HMRCCBCORG~cbcId~${testCbcId.value}")

        await(TestService.getClientDetailsWithChecks(testEk)) shouldBe
          Some(
            ClientDetailsStrideResponse(
              "testClientName",
              List(cbcPendingInvitationWithAgentName),
              Some(
                ActiveMainAgent(
                  "ABC Ltd",
                  testArn2.value,
                  HMRCCBCORG
                )
              )
            )
          )
      }
    }

    "pending invitations exist for HMRC-PILLAR2-ORG" should {
      "return Some(ClientDetailsStrideResponse)" in {
        val pillar2PendingInvitation = itsaInvitation.copy(service = HMRCPILLAR2ORG)
        val pillar2PendingInvitationWithAgentName = InvitationWithAgentName.fromInvitationAndAgentRecord(
          pillar2PendingInvitation,
          testAgentDetailsDesResponse
        )
        mockFindAllBy(
          None,
          Seq(HMRCPILLAR2ORG),
          Seq(testPillar2Ref.value),
          Some(Pending),
          isSuppliedClientId = true
        )(Future.successful(Seq(pillar2PendingInvitation)))
        mockGetNonSuspendedAgentRecord(testArn)(Some(testAgentDetailsDesResponse))
        mockGetActiveRelationshipsForClient(testPillar2Ref, Pillar2)(
          Future.successful(
            Some(
              ActiveRelationship(
                testArn2,
                None,
                None
              )
            )
          )
        )
        mockGetAgentRecord(testArn2)(testAgentDetailsDesResponse)

        val testEk = EnrolmentKey(s"$HMRCPILLAR2ORG~PLRID~${testPillar2Ref.value}")

        await(TestService.getClientDetailsWithChecks(testEk)) shouldBe
          Some(
            ClientDetailsStrideResponse(
              "testClientName",
              List(pillar2PendingInvitationWithAgentName),
              Some(
                ActiveMainAgent(
                  "ABC Ltd",
                  testArn2.value,
                  HMRCPILLAR2ORG
                )
              )
            )
          )
      }
    }

    "pending invitations exist for HMRC-PPT-ORG" should {
      "return Some(ClientDetailsStrideResponse)" in {
        val pptPendingInvitation = itsaInvitation.copy(service = HMRCPPTORG)
        val pptPendingInvitationWithAgentName = InvitationWithAgentName.fromInvitationAndAgentRecord(
          pptPendingInvitation,
          testAgentDetailsDesResponse
        )
        mockFindAllBy(
          None,
          Seq(HMRCPPTORG),
          Seq(testPptRef.value),
          Some(Pending),
          isSuppliedClientId = true
        )(Future.successful(Seq(pptPendingInvitation)))
        mockGetNonSuspendedAgentRecord(testArn)(Some(testAgentDetailsDesResponse))
        mockGetActiveRelationshipsForClient(testPptRef, Ppt)(
          Future.successful(
            Some(
              ActiveRelationship(
                testArn2,
                None,
                None
              )
            )
          )
        )
        mockGetAgentRecord(testArn2)(testAgentDetailsDesResponse)

        val testEk = EnrolmentKey(s"$HMRCPPTORG~EtmpRegistrationNumber~${testPptRef.value}")

        await(TestService.getClientDetailsWithChecks(testEk)) shouldBe
          Some(
            ClientDetailsStrideResponse(
              "testClientName",
              List(pptPendingInvitationWithAgentName),
              Some(
                ActiveMainAgent(
                  "ABC Ltd",
                  testArn2.value,
                  HMRCPPTORG
                )
              )
            )
          )
      }
    }
  }

  ".findActiveIrvRelationships" should {

    "return an IrvRelationships model" when {

      "there are no active relationships" in {
        mockValidateForTaxIdentifier(NinoType.id, testNino.value)(Right(NinoType.createUnderlying(testNino.value)))
        mockFindRelationshipForClient(testNino.value)(Left(RelationshipNotFound))
        mockFindClientDetails("PERSONAL-INCOME-RECORD", testNino.value)(Right(testClientDetailsResponse))
        val expectedResult = Right(IrvRelationships(
          testName,
          testNino.value,
          Seq()
        ))

        await(TestService.findActiveIrvRelationships(testNino.value)) shouldBe expectedResult
      }

      "there is one active relationship" in {
        mockValidateForTaxIdentifier(NinoType.id, testNino.value)(Right(NinoType.createUnderlying(testNino.value)))
        mockFindRelationshipForClient(testNino.value)(Right(Seq(testClientRelationship)))
        mockGetAgentRecord(testArn)(testAgentDetailsDesResponse)
        mockValidateAuthProfileToService(testNino)(Right(Service.PersonalIncomeRecord))
        mockFindClientDetails("PERSONAL-INCOME-RECORD", testNino.value)(Right(testClientDetailsResponse))
        val expectedResult = Right(IrvRelationships(
          testName,
          testNino.value,
          Seq(IrvAgent("ABC Ltd", testArn.value))
        ))

        await(TestService.findActiveIrvRelationships(testNino.value)) shouldBe expectedResult
      }

      "there are multiple active relationships" in {
        mockValidateForTaxIdentifier(NinoType.id, testNino.value)(Right(NinoType.createUnderlying(testNino.value)))
        mockFindRelationshipForClient(testNino.value)(Right(Seq(testClientRelationship, testClientRelationship.copy(arn = testArn2))))
        mockGetAgentRecord(testArn)(testAgentDetailsDesResponse)
        mockGetAgentRecord(testArn2)(testAgentDetailsDesResponse.copy(agencyDetails = AgencyDetails("XYZ Ltd", "")))
        mockValidateAuthProfileToService(testNino)(Right(Service.PersonalIncomeRecord))
        mockFindClientDetails("PERSONAL-INCOME-RECORD", testNino.value)(Right(testClientDetailsResponse))
        val expectedResult = Right(IrvRelationships(
          testName,
          testNino.value,
          Seq(IrvAgent("ABC Ltd", testArn.value), IrvAgent("XYZ Ltd", testArn2.value))
        ))

        await(TestService.findActiveIrvRelationships(testNino.value)) shouldBe expectedResult
      }
    }

    "return an error (Left)" when {

      "there was an issue validating the NINO" in {
        mockValidateForTaxIdentifier(NinoType.id, testNino.value)(Left(TaxIdentifierError))
        mockFindRelationshipForClient(testNino.value)(Left(RelationshipNotFound))
        mockFindClientDetails("PERSONAL-INCOME-RECORD", testNino.value)(Right(testClientDetailsResponse))
        val expectedResult = Left(TaxIdentifierError)

        await(TestService.findActiveIrvRelationships(testNino.value)) shouldBe expectedResult
      }

      "there was an unexpected error calling agent-fi-relationship to get relationship details" in {
        mockValidateForTaxIdentifier(NinoType.id, testNino.value)(Right(NinoType.createUnderlying(testNino.value)))
        mockFindRelationshipForClient(testNino.value)(Left(ErrorRetrievingRelationship(500, "oops")))
        mockFindClientDetails("PERSONAL-INCOME-RECORD", testNino.value)(Right(testClientDetailsResponse))
        val expectedResult = Left(ErrorRetrievingRelationship(500, "oops"))

        await(TestService.findActiveIrvRelationships(testNino.value)) shouldBe expectedResult
      }

      "there was an unexpected error calling agent-assurance to get agent details" in {
        mockValidateForTaxIdentifier(NinoType.id, testNino.value)(Right(NinoType.createUnderlying(testNino.value)))
        mockFindRelationshipForClient(testNino.value)(Right(Seq(testClientRelationship)))
        mockFailedGetAgentRecord(testArn)
        mockFindClientDetails("PERSONAL-INCOME-RECORD", testNino.value)(Right(testClientDetailsResponse))
        val expectedResult = Left(ErrorRetrievingAgentDetails("something went wrong"))

        await(TestService.findActiveIrvRelationships(testNino.value)) shouldBe expectedResult
      }

      "there was an unexpected error calling citizen-details to get client details" in {
        mockValidateForTaxIdentifier(NinoType.id, testNino.value)(Right(NinoType.createUnderlying(testNino.value)))
        mockFindRelationshipForClient(testNino.value)(Left(RelationshipNotFound))
        mockFindClientDetails("PERSONAL-INCOME-RECORD", testNino.value)(Left(ClientDetailsNotFound))
        val expectedResult = Left(RelationshipFailureResponse.ClientDetailsNotFound)

        await(TestService.findActiveIrvRelationships(testNino.value)) shouldBe expectedResult
      }
    }
  }

}
