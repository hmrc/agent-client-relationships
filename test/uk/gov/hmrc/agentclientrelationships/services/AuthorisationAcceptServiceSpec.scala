/*
 * Copyright 2023 HM Revenue & Customs
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
import play.api.test.FakeRequest
import play.api.test.Helpers.await
import play.api.test.Helpers.defaultAwaitTimeout
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.mocks._
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.repository.DbUpdateSucceeded
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentmtdidentifiers.model.Service.MtdIt
import uk.gov.hmrc.agentmtdidentifiers.model.Service.MtdItSupp
import uk.gov.hmrc.agentmtdidentifiers.model.Service.PersonalIncomeRecord
import uk.gov.hmrc.agentmtdidentifiers.model.Service.Vat
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentmtdidentifiers.model.MtdItId
import uk.gov.hmrc.agentmtdidentifiers.model.Vrn
import uk.gov.hmrc.auth.core.retrieve.Credentials
import uk.gov.hmrc.domain.Nino
import play.api.mvc.RequestHeader

import java.time.Instant
import java.time.LocalDate
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class AuthorisationAcceptServiceSpec
extends UnitSpec
with ResettingMockitoSugar
with MockCreateRelationshipsService
with MockItsaDeauthAndCleanupService
with MockEmailService
with MockInvitationsRepository
with MockPartialAuthRepository
with MockAgentFiRelationshipConnector
with MockAuditService {

  def also: AfterWord = afterWord("also")

  object TestService
  extends AuthorisationAcceptService(
    createRelationshipsService = mockCreateRelationshipsService,
    emailService = mockEmailService,
    itsaDeauthAndCleanupService = mockItsaDeauthAndCleanupService,
    invitationsRepository = mockInvitationsRepository,
    partialAuthRepository = mockPartialAuthRepository,
    agentFiRelationshipConnector = mockAgentFiRelationshipConnector,
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
  val testArn2: Arn = Arn("ARN1234567899")
  val testArn3: Arn = Arn("ARN1234567895")
  val testName = "testClientName"
  val testAgentName = "testAgentName"
  val testAgentEmail = "agent@email.com"
  val testOldInvitationId = "testOldInvitationId"
  val testVrn: Vrn = Vrn("1234567890")
  val testNino: Nino = Nino("AB123456A")
  val testMtdItId: MtdItId = MtdItId("XAIT0000111122")

  val vatEnrolment: EnrolmentKey = EnrolmentKey(Vat, testVrn)
  val vatInvitation: Invitation = Invitation.createNew(
    testArn.value,
    Vat,
    testVrn,
    testVrn,
    testName,
    testAgentName,
    testAgentEmail,
    LocalDate.now(),
    Some("business")
  )
  val oltVatInvitation: Invitation = Invitation
    .createNew(
      testArn2.value,
      Vat,
      testVrn,
      testVrn,
      testName,
      testAgentName,
      testAgentEmail,
      LocalDate.now(),
      Some("business")
    )
    .copy(status = Accepted)

  val pirEnrolment: EnrolmentKey = EnrolmentKey(PersonalIncomeRecord, testNino)
  val pirInvitation: Invitation = Invitation.createNew(
    testArn.value,
    PersonalIncomeRecord,
    testNino,
    testNino,
    testName,
    testAgentName,
    testAgentEmail,
    LocalDate.now(),
    Some("personal")
  )
  val oldPirInvitation: Invitation = Invitation
    .createNew(
      testArn2.value,
      PersonalIncomeRecord,
      testNino,
      testNino,
      testName,
      testAgentName,
      testAgentEmail,
      LocalDate.now(),
      Some("personal")
    )
    .copy(status = Accepted)

  val itsaEnrolment: EnrolmentKey = EnrolmentKey(MtdIt, testMtdItId)
  val itsaInvitation: Invitation = Invitation.createNew(
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
  val altItsaInvitation: Invitation = Invitation.createNew(
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
  val oldItsaInvitation: Invitation = Invitation
    .createNew(
      testArn2.value,
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
      testArn3.value,
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
  val oldAltItsaPartialAuth: PartialAuthRelationship = PartialAuthRelationship(
    Instant.now(),
    testArn3.value,
    MtdIt.id,
    testNino.nino,
    active = true,
    Instant.now()
  )
  val itsaSuppInvitation: Invitation = Invitation.createNew(
    testArn.value,
    MtdItSupp,
    testMtdItId,
    testNino,
    testName,
    testAgentName,
    testAgentEmail,
    LocalDate.now(),
    Some("personal")
  )
  val altItsaSuppInvitation: Invitation = Invitation.createNew(
    testArn.value,
    MtdItSupp,
    testNino,
    testNino,
    testName,
    testAgentName,
    testAgentEmail,
    LocalDate.now(),
    Some("personal")
  )

  "accept" when {
    // GENERIC CASES
    s"called with a general case invitation" should {
      "create a relationship" should
        also {
          "update the invitation's status, check for and deauth previously accepted invitations and send a confirmation email" in {
            mockCreateRelationship(testArn, vatEnrolment)(Future.successful(Some(DbUpdateSucceeded)))
            mockUpdateStatus(vatInvitation.invitationId, Accepted)(
              Future.successful(vatInvitation.copy(status = Accepted))
            )
            mockFindAllBy(
              None,
              Seq(vatEnrolment.service),
              Seq(vatInvitation.clientId),
              Some(Accepted)
            )(Future.successful(Seq(vatInvitation.copy(status = Accepted), oltVatInvitation)))
            mockDeauthInvitation(oltVatInvitation.invitationId, "Client")(
              Future.successful(Some(oltVatInvitation.copy(status = DeAuthorised)))
            )
            mockSendAcceptedEmail(vatInvitation)()

            TestService.accept(vatInvitation, vatEnrolment).futureValue shouldBe vatInvitation.copy(status = Accepted)

            // Verifying non blocking side effects actually happen
            verifySideEffectsOccur { _ =>
              verify(mockInvitationsRepository, times(1)).findAllBy(
                any[Option[String]],
                any[Seq[String]],
                any[Seq[String]],
                any[Option[InvitationStatus]]
              )
              verify(mockInvitationsRepository, times(1)).deauthInvitation(
                any[String],
                any[String],
                any[Option[Instant]]
              )
              verify(mockEmailService, times(1)).sendAcceptedEmail(any[Invitation])(any[RequestHeader])
            }
          }
        }
    }
    // PERSONAL-INCOME-RECORD
    "called with a PERSONAL-INCOME-RECORD invitation" should {
      "create a relationship via agent fi relationship" should
        also {
          "update the invitation's status, check for and deauth previously accepted invitations and send a confirmation email" in {
            mockCreateFiRelationship(
              testArn,
              pirInvitation.service,
              pirInvitation.clientId
            )
            mockUpdateStatus(pirInvitation.invitationId, Accepted)(
              Future.successful(pirInvitation.copy(status = Accepted))
            )
            mockFindAllBy(
              None,
              Seq(pirEnrolment.service),
              Seq(pirInvitation.clientId),
              Some(Accepted)
            )(Future.successful(Seq(pirInvitation.copy(status = Accepted), oldPirInvitation)))
            mockDeauthInvitation(oldPirInvitation.invitationId, "Client")(
              Future.successful(Some(oldPirInvitation.copy(status = DeAuthorised)))
            )
            mockSendAcceptedEmail(pirInvitation)()

            await(TestService.accept(pirInvitation, pirEnrolment)) shouldBe pirInvitation.copy(status = Accepted)

            // Verifying non blocking side effects actually happen
            verifySideEffectsOccur { _ =>
              verify(mockInvitationsRepository, times(1)).findAllBy(
                any[Option[String]],
                any[Seq[String]],
                any[Seq[String]],
                any[Option[InvitationStatus]]
              )
              verify(mockInvitationsRepository, times(1)).deauthInvitation(
                any[String],
                any[String],
                any[Option[Instant]]
              )
              verify(mockEmailService, times(1)).sendAcceptedEmail(any[Invitation])(any[RequestHeader])
            }
          }
        }
    }
    // ITSA
    "called with a HMRC-MTD-IT invitation" should {
      "check for and deauth an existing itsa relationship with the same agent" should
        also {
          "check for and deauth an existing partial auth and create a relationship" should
            also {
              "update the invitation's status, check for and deauth previously accepted invitations and send a confirmation email" in {
                mockDeleteSameAgentRelationship(
                  MtdIt.id,
                  testArn.value,
                  Some(testMtdItId.value),
                  testNino.nino
                )(Future.successful(true))
                mockFindMainAgent(testNino.nino)(Future.successful(Some(oldAltItsaPartialAuth)))
                mockDeauthorisePartialAuth(
                  MtdIt.id,
                  testNino,
                  testArn3
                )(Future.successful(true))
                mockCreateRelationship(testArn, itsaEnrolment)(Future.successful(Some(DbUpdateSucceeded)))
                mockUpdateStatus(itsaInvitation.invitationId, Accepted)(
                  Future.successful(itsaInvitation.copy(status = Accepted))
                )
                mockFindAllBy(
                  None,
                  Seq(itsaEnrolment.service),
                  Seq(itsaInvitation.clientId),
                  Some(Accepted)
                )(Future.successful(Seq(itsaInvitation.copy(status = Accepted), oldItsaInvitation)))
                mockDeauthInvitation(oldItsaInvitation.invitationId, "Client")(
                  Future.successful(Some(oldItsaInvitation.copy(status = DeAuthorised)))
                )
                mockFindAllBy(
                  None,
                  Seq(itsaEnrolment.service),
                  Seq(itsaInvitation.suppliedClientId),
                  Some(PartialAuth)
                )(Future.successful(Seq(oldAltItsaInvitation)))
                mockDeauthInvitation(oldAltItsaInvitation.invitationId, "Client")(
                  Future.successful(Some(oldAltItsaInvitation.copy(status = DeAuthorised)))
                )
                mockSendAcceptedEmail(itsaInvitation)()

                await(TestService.accept(itsaInvitation, itsaEnrolment)) shouldBe itsaInvitation.copy(status = Accepted)

                // Verifying non blocking side effects actually happen
                verifySideEffectsOccur { _ =>
                  verify(mockInvitationsRepository, times(2)).findAllBy(
                    any[Option[String]],
                    any[Seq[String]],
                    any[Seq[String]],
                    any[Option[InvitationStatus]]
                  )
                  verify(mockInvitationsRepository, times(2)).deauthInvitation(
                    any[String],
                    any[String],
                    any[Option[Instant]]
                  )
                  verify(mockEmailService, times(1)).sendAcceptedEmail(any[Invitation])(any[RequestHeader])
                }
              }
            }
        }
    }
    // ITSA-SUPP
    "called with a HMRC-MTD-IT-SUPP invitation" should {
      "check for and deauth an existing itsa relationship with the same agent" should
        also {
          "create a relationship" should
            also {
              "update the invitation's status and send a confirmation email" in {
                mockDeleteSameAgentRelationship(
                  MtdItSupp.id,
                  testArn.value,
                  Some(testMtdItId.value),
                  testNino.nino
                )(Future.successful(true))
                mockCreateRelationship(testArn, itsaEnrolment)(Future.successful(Some(DbUpdateSucceeded)))
                mockUpdateStatus(itsaSuppInvitation.invitationId, Accepted)(
                  Future.successful(itsaSuppInvitation.copy(status = Accepted))
                )
                mockSendAcceptedEmail(itsaSuppInvitation)()

                await(TestService.accept(itsaSuppInvitation, itsaEnrolment)) shouldBe
                  itsaSuppInvitation.copy(status = Accepted)

                // Verifying non blocking side effects actually happen
                verifySideEffectsOccur { _ =>
                  verify(mockEmailService, times(1)).sendAcceptedEmail(any[Invitation])(any[RequestHeader])
                }
              }
            }
        }
    }
    // ALT ITSA
    "called with a HMRC-MTD-IT alt itsa invitation" should {
      "check for and deauth an existing itsa relationship with the same agent" should
        also {
          "check for and deauth an existing partial auth then create a new one" should
            also {
              "update the invitation's status, check for and deauth previously accepted invitations and send a confirmation email" in {
                mockDeleteSameAgentRelationship(
                  MtdIt.id,
                  testArn.value,
                  None,
                  testNino.nino
                )(Future.successful(true))
                mockFindMainAgent(testNino.nino)(Future.successful(Some(oldAltItsaPartialAuth)))
                mockDeauthorisePartialAuth(
                  MtdIt.id,
                  testNino,
                  testArn3
                )(Future.successful(true))
                mockCreatePartialAuth(
                  testArn,
                  MtdIt.id,
                  testNino
                )()
                mockUpdateStatus(altItsaInvitation.invitationId, PartialAuth)(
                  Future.successful(altItsaInvitation.copy(status = PartialAuth))
                )
                mockFindAllBy(
                  None,
                  Seq(altItsaInvitation.service),
                  Seq(altItsaInvitation.clientId),
                  Some(PartialAuth)
                )(Future.successful(Seq(altItsaInvitation.copy(status = PartialAuth), oldAltItsaInvitation)))
                mockDeauthInvitation(oldAltItsaInvitation.invitationId, "Client")(
                  Future.successful(Some(oldAltItsaInvitation.copy(status = DeAuthorised)))
                )
                mockSendAcceptedEmail(altItsaInvitation)()

                await(TestService.accept(altItsaInvitation, itsaEnrolment)) shouldBe
                  altItsaInvitation.copy(status = PartialAuth)

                // Verifying non blocking side effects actually happen
                verifySideEffectsOccur { _ =>
                  verify(mockInvitationsRepository, times(1)).findAllBy(
                    any[Option[String]],
                    any[Seq[String]],
                    any[Seq[String]],
                    any[Option[InvitationStatus]]
                  )
                  verify(mockInvitationsRepository, times(1)).deauthInvitation(
                    any[String],
                    any[String],
                    any[Option[Instant]]
                  )
                  verify(mockEmailService, times(1)).sendAcceptedEmail(any[Invitation])(any[RequestHeader])
                }
              }
            }
        }
    }
    // ALT ITSA SUPP
    "called with a HMRC-MTD-IT-SUPP alt itsa invitation" should {
      "check for and deauth an existing itsa relationship with the same agent" should
        also {
          "create a new partial auth" should
            also {
              "update the invitation's status and send a confirmation email" in {
                mockDeleteSameAgentRelationship(
                  MtdItSupp.id,
                  testArn.value,
                  None,
                  testNino.nino
                )(Future.successful(true))
                mockCreatePartialAuth(
                  testArn,
                  MtdItSupp.id,
                  testNino
                )()
                mockUpdateStatus(altItsaSuppInvitation.invitationId, PartialAuth)(
                  Future.successful(altItsaSuppInvitation.copy(status = PartialAuth))
                )
                mockSendAcceptedEmail(altItsaSuppInvitation)()

                await(TestService.accept(altItsaSuppInvitation, itsaEnrolment)) shouldBe
                  altItsaSuppInvitation.copy(status = PartialAuth)

                // Verifying non blocking side effects actually happen
                verifySideEffectsOccur { _ =>
                  verify(mockEmailService, times(1)).sendAcceptedEmail(any[Invitation])(any[RequestHeader])
                }
              }
            }
        }
    }
  }

}
