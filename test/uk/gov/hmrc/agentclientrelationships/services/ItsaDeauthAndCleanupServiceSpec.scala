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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import play.api.mvc.AnyContentAsEmpty
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers.await
import play.api.test.Helpers.defaultAwaitTimeout
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.mocks._
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdIt
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdItSupp
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Vat
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.MtdItId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.auth.core.AffinityGroup
import uk.gov.hmrc.auth.core.retrieve.Credentials

import java.time.Instant
import java.time.LocalDate
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class ItsaDeauthAndCleanupServiceSpec
extends UnitSpec
with ResettingMockitoSugar
with MockCheckRelationshipsService
with MockDeleteRelationshipsService
with MockPartialAuthRepository
with MockInvitationsRepository
with MockAuditService {

  object TestService
  extends ItsaDeauthAndCleanupService(
    partialAuthRepository = mockPartialAuthRepository,
    checkRelationshipsService = mockCheckRelationshipsService,
    deleteRelationshipsService = mockDeleteRelationshipsService,
    invitationsRepository = mockInvitationsRepository,
    auditService = mockAuditService
  )

  implicit val ec: ExecutionContext = ExecutionContext.Implicits.global
  implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()
  implicit val auditData: AuditData = new AuditData
  implicit val currentUser: CurrentUser = CurrentUser(
    credentials = Some(Credentials("GG-00001", "GovernmentGateway")),
    affinityGroup = None
  )

  val testArn: Arn = Arn("ARN1234567890")
  val testName = "testClientName"
  val testAgentName = "testAgentName"
  val testAgentEmail = "agent@email.com"
  val testOldInvitationId = "testOldInvitationId"

  val testNino: NinoWithoutSuffix = NinoWithoutSuffix("AB123456")
  val testMtdItId: MtdItId = MtdItId("XAIT0000111122")
  override val mockDeleteRelationshipsService: DeleteRelationshipsService = resettingMock[DeleteRelationshipsService]

  val itsaEnrolment: EnrolmentKey = EnrolmentKey(MtdIt, testMtdItId)
  val itsaSuppEnrolment: EnrolmentKey = EnrolmentKey(MtdItSupp, testMtdItId)

  val oldItsaInvitation: Invitation = Invitation
    .createNew(
      testArn.value,
      MtdIt,
      testMtdItId,
      testNino,
      testName,
      testAgentName,
      testAgentEmail,
      LocalDate.now(),
      Some("personal")
    )
    .copy(status = Accepted)
  val oldAltItsaInvitation: Invitation = Invitation
    .createNew(
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
    .copy(status = PartialAuth)

  "deleteSameAgentRelationship" when {
    "called with non ITSA service" should {
      "return false" in {
        await(
          TestService.deleteSameAgentRelationship(
            Vat.id,
            testArn.value,
            None,
            testNino.nino
          )
        ) shouldBe false
      }
    }
    "called for a user with an existing partial auth" should {
      "check for partial auth and relationships, remove partial auth and update old alt itsa invitation then return true" in {
        mockDeauthorisePartialAuth(
          MtdItSupp.id,
          testNino,
          testArn
        )(Future.successful(true))
        mockCheckForRelationshipAgencyLevel(testArn, itsaSuppEnrolment)(Future.successful((false, "groupId")))
        mockFindAllBy(
          Some(testArn.value),
          Seq(MtdItSupp.id),
          Seq(testNino.value),
          Some(PartialAuth)
        )(Future.successful(Seq(oldAltItsaInvitation)))
        mockDeauthInvitation(oldAltItsaInvitation.invitationId, "Client")(
          Future.successful(Some(oldAltItsaInvitation.copy(status = DeAuthorised)))
        )

        await(
          TestService.deleteSameAgentRelationship(
            MtdIt.id,
            testArn.value,
            Some(testMtdItId.value),
            testNino.nino
          )
        ) shouldBe true

        verify(mockPartialAuthRepository, times(1)).deauthorise(
          any[String],
          any[NinoWithoutSuffix],
          any[Arn],
          any[Instant]
        )
        verify(mockCheckRelationshipsService, times(1)).checkForRelationshipAgencyLevel(any[Arn], any[EnrolmentKey])(
          any[RequestHeader]()
        )
        verify(mockDeleteRelationshipsService, times(0)).deleteRelationship(
          any[Arn],
          any[EnrolmentKey],
          any[Option[AffinityGroup]]
        )(
          any[RequestHeader],
          any[CurrentUser],
          any[AuditData]
        )
        verify(mockInvitationsRepository, times(1)).findAllBy(
          any[Option[String]],
          any[Seq[String]],
          any[Seq[String]],
          any[Option[InvitationStatus]],
          any[Boolean]
        )
        verify(mockInvitationsRepository, times(1)).deauthInvitation(
          any[String],
          any[String],
          any[Option[Instant]]
        )
      }
    }
    "called for a user with an existing relationship" should {
      "check for partial auth and relationships, remove relationship and update old itsa invitation then return true" in {
        mockDeauthorisePartialAuth(
          MtdItSupp.id,
          testNino,
          testArn
        )(Future.successful(false))
        mockCheckForRelationshipAgencyLevel(testArn, itsaSuppEnrolment)(Future.successful((true, "groupId")))
        mockDeleteRelationship(
          testArn,
          itsaSuppEnrolment,
          currentUser.affinityGroup
        )()
        mockFindAllBy(
          Some(testArn.value),
          Seq(MtdItSupp.id),
          Seq(testMtdItId.value),
          Some(Accepted)
        )(Future.successful(Seq(oldItsaInvitation)))
        mockDeauthInvitation(oldAltItsaInvitation.invitationId, "Client")(
          Future.successful(Some(oldItsaInvitation.copy(status = DeAuthorised)))
        )

        await(
          TestService.deleteSameAgentRelationship(
            MtdIt.id,
            testArn.value,
            Some(testMtdItId.value),
            testNino.nino
          )
        ) shouldBe true

        verify(mockPartialAuthRepository, times(1)).deauthorise(
          any[String],
          any[NinoWithoutSuffix],
          any[Arn],
          any[Instant]
        )
        verify(mockCheckRelationshipsService, times(1)).checkForRelationshipAgencyLevel(any[Arn], any[EnrolmentKey])(
          any[RequestHeader]()
        )
        verify(mockDeleteRelationshipsService, times(1)).deleteRelationship(
          any[Arn],
          any[EnrolmentKey],
          any[Option[AffinityGroup]]
        )(
          any[RequestHeader],
          any[CurrentUser],
          any[AuditData]
        )
        verify(mockInvitationsRepository, times(1)).findAllBy(
          any[Option[String]],
          any[Seq[String]],
          any[Seq[String]],
          any[Option[InvitationStatus]],
          any[Boolean]
        )
        verify(mockInvitationsRepository, times(1)).deauthInvitation(
          any[String],
          any[String],
          any[Option[Instant]]
        )
      }
    }
    "called for a user without an mtd id" should {
      "check only for partial auth" in {
        mockDeauthorisePartialAuth(
          MtdIt.id,
          testNino,
          testArn
        )(Future.successful(false))

        await(
          TestService.deleteSameAgentRelationship(
            MtdItSupp.id,
            testArn.value,
            None,
            testNino.nino
          )
        ) shouldBe false

        verify(mockPartialAuthRepository, times(1)).deauthorise(
          any[String],
          any[NinoWithoutSuffix],
          any[Arn],
          any[Instant]
        )
        verify(mockCheckRelationshipsService, times(0)).checkForRelationshipAgencyLevel(any[Arn], any[EnrolmentKey])(
          any[RequestHeader]()
        )
        verify(mockDeleteRelationshipsService, times(0)).deleteRelationship(
          any[Arn],
          any[EnrolmentKey],
          any[Option[AffinityGroup]]
        )(
          any[RequestHeader],
          any[CurrentUser],
          any[AuditData]
        )
        verify(mockInvitationsRepository, times(0)).findAllBy(
          any[Option[String]],
          any[Seq[String]],
          any[Seq[String]],
          any[Option[InvitationStatus]],
          any[Boolean]
        )
        verify(mockInvitationsRepository, times(0)).deauthInvitation(
          any[String],
          any[String],
          any[Option[Instant]]
        )
      }
    }
  }

}
