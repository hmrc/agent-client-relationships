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

import org.apache.pekko.Done
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.{eq => eqs}
import org.mockito.Mockito._
import org.mockito.stubbing.OngoingStubbing
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.ConfigLoader
import play.api.Configuration
import play.api.mvc.AnyContentAsEmpty
import play.api.mvc.Request
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.audit.AuditService
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.RegistrationRelationshipResponse
import uk.gov.hmrc.agentclientrelationships.model.UserId
import uk.gov.hmrc.agentclientrelationships.repository.DeleteRecord
import uk.gov.hmrc.agentclientrelationships.repository.FakeDeleteRecordRepository
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.support.NoRequest
import uk.gov.hmrc.agentclientrelationships.support.RelationshipNotFound
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.MtdItId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.auth.core.retrieve.Credentials
import uk.gov.hmrc.domain._
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class DeleteRelationshipServiceSpec
extends UnitSpec {

  implicit val request: RequestHeader = FakeRequest()
  implicit val ec: ExecutionContext = ExecutionContext.Implicits.global
  implicit val testAuditData: AuditData = new AuditData

  def now: LocalDateTime = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime

  val arn: Arn = Arn("AARN0000002")
  val mtdItId: MtdItId = MtdItId("ABCDEF123456789")
  val mtdItEnrolmentKey: EnrolmentKey = EnrolmentKey(Service.MtdIt, mtdItId)
  val agentUserId = "testUserId"
  val agentGroupId = "testGroupId"
  val agentCodeForAsAgent: AgentCode = AgentCode("ABC1234")
  val agentUser: AgentUser = AgentUser(
    agentUserId,
    agentGroupId,
    agentCodeForAsAgent,
    arn
  )

  "deleteRelationship" should {

    "delete relationship and keep no deleteRecord if success" in
      new TestFixture {
        givenRelationshipBetweenAgentAndClientExists
        givenAgentExists
        givenETMPDeAuthSucceeds
        givenESDeAllocationSucceeds
        givenSetRelationshipEndedSucceeds
        givenAucdCacheRefresh

        implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()
        implicit val currentUser: CurrentUser = CurrentUser(
          credentials = Some(Credentials("GG-00001", "GovernmentGateway")),
          affinityGroup = None
        )
        await(
          underTest.deleteRelationship(
            arn,
            mtdItEnrolmentKey,
            None
          )
        ) shouldBe true

        verifyESDeAllocateHasBeenPerformed
        verifyETMPDeAuthorisationHasBeenPerformed

        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None
      }

    "save deleteRecord if ES de-allocation failed" in
      new TestFixture {
        givenRelationshipBetweenAgentAndClientExists
        givenAgentExists
        givenESDeAllocationFails

        implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()
        implicit val currentUser: CurrentUser = CurrentUser(
          credentials = Some(Credentials("GG-00001", "GovernmentGateway")),
          affinityGroup = None
        )

        intercept[Exception](
          await(
            underTest.deleteRelationship(
              arn,
              mtdItEnrolmentKey,
              None
            )
          )
        )

        verifyESDeAllocateHasBeenPerformed
        verifyETMPDeAuthorisationHasNOTBeenPerformed

        await(repo.findBy(arn, mtdItEnrolmentKey)) should
          matchPattern {
            case Some(
                  DeleteRecord(
                    arn.value,
                    _,
                    _,
                    None,
                    Some(Failed),
                    None,
                    _,
                    _,
                    Some("HMRC")
                  )
                ) =>
          }
      }

    "save deleteRecord if ETMP de-authorisation failed" in
      new TestFixture {
        givenAgentExists
        givenRelationshipBetweenAgentAndClientExists
        givenESDeAllocationSucceeds
        givenETMPDeAuthFails
        givenAucdCacheRefresh

        implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()
        implicit val currentUser: CurrentUser = CurrentUser(
          credentials = Some(Credentials("GG-00001", "GovernmentGateway")),
          affinityGroup = None
        )

        intercept[Exception](
          await(
            underTest.deleteRelationship(
              arn,
              mtdItEnrolmentKey,
              None
            )
          )
        )

        verifyESDeAllocateHasBeenPerformed
        verifyETMPDeAuthorisationHasBeenPerformed

        await(repo.findBy(arn, mtdItEnrolmentKey)) should
          matchPattern {
            case Some(
                  DeleteRecord(
                    arn.value,
                    _,
                    _,
                    Some(Failed),
                    Some(Success),
                    None,
                    _,
                    _,
                    Some("HMRC")
                  )
                ) =>
          }
        await(repo.remove(arn, mtdItEnrolmentKey))
      }

    "delete relationship and keep no deleteRecord if ES relationship does not exist but ETMP is deleted" in
      new TestFixture {
        givenAgentExists
        givenRelationshipBetweenAgentAndClientDoesNotExist
        givenETMPDeAuthSucceeds
        givenSetRelationshipEndedSucceeds
        givenAucdCacheRefresh

        implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()
        implicit val currentUser: CurrentUser = CurrentUser(
          credentials = Some(Credentials("GG-00001", "GovernmentGateway")),
          affinityGroup = None
        )

        await(
          underTest.deleteRelationship(
            arn,
            mtdItEnrolmentKey,
            None
          )
        ) shouldBe true

        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None
      }

    "delete relationship and keep no deleteRecord if ETMP relationship does not exist but ES is deleted" in
      new TestFixture {
        givenAgentExists
        givenRelationshipBetweenAgentAndClientExists
        givenESDeAllocationSucceeds
        givenETMPDeAuthNoRelationshipFound
        givenSetRelationshipEndedSucceeds
        givenAucdCacheRefresh

        implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()
        implicit val currentUser: CurrentUser = CurrentUser(
          credentials = Some(Credentials("GG-00001", "GovernmentGateway")),
          affinityGroup = None
        )

        await(
          underTest.deleteRelationship(
            arn,
            mtdItEnrolmentKey,
            None
          )
        ) shouldBe true

        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None
      }

    "delete relationship and keep no deleteRecord but return RelationshipNotFound if ETMP and ES records dont exist" in
      new TestFixture {
        givenAgentExists
        givenRelationshipBetweenAgentAndClientDoesNotExist
        givenETMPDeAuthNoRelationshipFound
        givenSetRelationshipEndedSucceeds
        givenAucdCacheRefresh

        implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()
        implicit val currentUser: CurrentUser = CurrentUser(
          credentials = Some(Credentials("GG-00001", "GovernmentGateway")),
          affinityGroup = None
        )

        intercept[RelationshipNotFound](
          await(
            underTest.deleteRelationship(
              arn,
              mtdItEnrolmentKey,
              None
            )
          )
        )

        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None
      }

    "resume failed ES de-allocation when matching deleteRecord found and remove record afterwards if recovery succeeds" in
      new TestFixture {
        await(repo.remove(arn, mtdItEnrolmentKey))
        val deleteRecord: DeleteRecord = DeleteRecord(
          arn.value,
          mtdItEnrolmentKey,
          syncToETMPStatus = None,
          syncToESStatus = Some(Failed)
        )
        await(repo.create(deleteRecord))
        givenAgentExists
        givenRelationshipBetweenAgentAndClientExists
        givenESDeAllocationSucceeds
        givenETMPDeAuthSucceeds
        givenSetRelationshipEndedSucceeds
        givenAucdCacheRefresh

        implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()
        implicit val currentUser: CurrentUser = CurrentUser(
          credentials = Some(Credentials("GG-00001", "GovernmentGateway")),
          affinityGroup = None
        )
        await(
          underTest.deleteRelationship(
            arn,
            mtdItEnrolmentKey,
            None
          )
        )

        verifyESDeAllocateHasBeenPerformed
        verifyETMPDeAuthorisationHasBeenPerformed

        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None
      }

    "delete relationship and succeed if setRelationshipFails" in
      new TestFixture {
        givenRelationshipBetweenAgentAndClientExists
        givenAgentExists
        givenETMPDeAuthSucceeds
        givenESDeAllocationSucceeds
        givenSetRelationshipEndedFails
        givenAucdCacheRefresh

        implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()
        implicit val currentUser: CurrentUser = CurrentUser(
          credentials = Some(Credentials("GG-00001", "GovernmentGateway")),
          affinityGroup = None
        )
        await(
          underTest.deleteRelationship(
            arn,
            mtdItEnrolmentKey,
            None
          )
        )

        verifyESDeAllocateHasBeenPerformed
        verifyETMPDeAuthorisationHasBeenPerformed

        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None
      }
  }

  "resumeRelationshipRemoval" should {

    // HAPPY PATHS :-)

    "Do nothing if ETMP and ES are in successful states" in
      new TestFixture {
        val deleteRecord: DeleteRecord = DeleteRecord(
          arn.value,
          mtdItEnrolmentKey,
          syncToETMPStatus = Some(Success),
          syncToESStatus = Some(Success)
        )
        await(repo.create(deleteRecord))

        val result: Boolean = await(underTest.resumeRelationshipRemoval(deleteRecord))
        result shouldBe true

        verifyESDeAllocateHasNOTBeenPerformed
        verifyETMPDeAuthorisationHasNOTBeenPerformed
      }

    "Retry ETMP de-authorisation when only ETMP requires action" in
      new TestFixture {
        val deleteRecord: DeleteRecord = DeleteRecord(
          arn.value,
          mtdItEnrolmentKey,
          syncToETMPStatus = Some(Failed),
          syncToESStatus = Some(Success)
        )
        await(repo.create(deleteRecord))
        givenETMPDeAuthSucceeds
        givenSetRelationshipEndedSucceeds

        val result: Boolean = await(underTest.resumeRelationshipRemoval(deleteRecord))
        result shouldBe true

        verifyESDeAllocateHasNOTBeenPerformed
        verifyETMPDeAuthorisationHasBeenPerformed

        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None
      }

    "Do not retry ETMP de-authorisation when ETMP state is InProgress" in
      new TestFixture {
        val deleteRecord: DeleteRecord = DeleteRecord(
          arn.value,
          mtdItEnrolmentKey,
          syncToETMPStatus = Some(InProgress),
          syncToESStatus = Some(Success)
        )
        await(repo.create(deleteRecord))
        givenETMPDeAuthSucceeds

        val result: Boolean = await(underTest.resumeRelationshipRemoval(deleteRecord))
        result shouldBe true

        verifyESDeAllocateHasNOTBeenPerformed
        verifyETMPDeAuthorisationHasNOTBeenPerformed

        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None
      }

    "Retry ES de-allocation when only ES requires action (impossible state since ES goes first)" in
      new TestFixture {
        val deleteRecord: DeleteRecord = DeleteRecord(
          arn.value,
          mtdItEnrolmentKey,
          syncToETMPStatus = Some(Success),
          syncToESStatus = Some(Failed)
        )
        await(repo.create(deleteRecord))
        givenAgentExists
        givenRelationshipBetweenAgentAndClientExists
        givenESDeAllocationSucceeds
        givenSetRelationshipEndedSucceeds
        givenAucdCacheRefresh

        val result: Boolean = await(underTest.resumeRelationshipRemoval(deleteRecord))
        result shouldBe true

        verifyESDeAllocateHasBeenPerformed
        verifyETMPDeAuthorisationHasNOTBeenPerformed

        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None
      }

    "Do not retry ES de-allocation when ES state is InProgress" in
      new TestFixture {
        val deleteRecord: DeleteRecord = DeleteRecord(
          arn.value,
          mtdItEnrolmentKey,
          syncToETMPStatus = Some(Success),
          syncToESStatus = Some(InProgress)
        )
        await(repo.create(deleteRecord))
        givenAgentExists
        givenRelationshipBetweenAgentAndClientExists
        givenESDeAllocationSucceeds

        val result: Boolean = await(underTest.resumeRelationshipRemoval(deleteRecord))
        result shouldBe true

        verifyESDeAllocateHasNOTBeenPerformed
        verifyETMPDeAuthorisationHasNOTBeenPerformed

        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None
      }

    "Retry both ETMP and ES de-allocation when both require action" in
      new TestFixture {
        val deleteRecord: DeleteRecord = DeleteRecord(
          arn.value,
          mtdItEnrolmentKey,
          syncToETMPStatus = Some(Failed),
          syncToESStatus = Some(Failed)
        )
        await(repo.create(deleteRecord))
        givenAgentExists
        givenRelationshipBetweenAgentAndClientExists
        givenETMPDeAuthSucceeds
        givenESDeAllocationSucceeds
        givenSetRelationshipEndedSucceeds
        givenAucdCacheRefresh

        val result: Boolean = await(underTest.resumeRelationshipRemoval(deleteRecord))
        result shouldBe true

        verifyESDeAllocateHasBeenPerformed
        verifyETMPDeAuthorisationHasBeenPerformed

        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None
      }

    // FAILURE SCENARIOS

    "keep ETMP sync status Failed when ETMP de-authorisation fails" in
      new TestFixture {
        val deleteRecord: DeleteRecord = DeleteRecord(
          arn.value,
          mtdItEnrolmentKey,
          syncToETMPStatus = Some(Failed),
          syncToESStatus = Some(Success)
        )
        await(repo.create(deleteRecord))
        givenETMPDeAuthFails

        intercept[Exception](await(underTest.resumeRelationshipRemoval(deleteRecord)))

        verifyESDeAllocateHasNOTBeenPerformed
        verifyETMPDeAuthorisationHasBeenPerformed

        val record: Option[DeleteRecord] = await(repo.findBy(arn, mtdItEnrolmentKey))
        record.flatMap(_.syncToETMPStatus) shouldBe Some(Failed)
        record.flatMap(_.syncToESStatus) shouldBe Some(Success)
      }

    "keep ES sync status Failed when ES de-allocation fails" in
      new TestFixture {
        val deleteRecord: DeleteRecord = DeleteRecord(
          arn.value,
          mtdItEnrolmentKey,
          syncToETMPStatus = Some(Success),
          syncToESStatus = Some(Failed)
        )
        await(repo.create(deleteRecord))
        givenAgentExists
        givenRelationshipBetweenAgentAndClientExists
        when(es.deallocateEnrolmentFromAgent("group0001", mtdItEnrolmentKey)).thenReturn(Future.failed(new Exception()))

        intercept[Exception](await(underTest.resumeRelationshipRemoval(deleteRecord)))

        verifyESDeAllocateHasBeenPerformed
        verifyETMPDeAuthorisationHasNOTBeenPerformed

        val record: Option[DeleteRecord] = await(repo.findBy(arn, mtdItEnrolmentKey))
        record.flatMap(_.syncToETMPStatus) shouldBe Some(Success)
        record.flatMap(_.syncToESStatus) shouldBe Some(Failed)
      }
  }

  "checkDeleteRecordAndEventuallyResume" should {
    implicit val request: Request[_] = NoRequest
    "return true if there is no pending delete record" in
      new TestFixture {
        val result: Boolean = await(underTest.checkDeleteRecordAndEventuallyResume(arn, mtdItEnrolmentKey))

        result shouldBe true
      }
    "return true if delete record found and resumption succeeded" in
      new TestFixture {
        val deleteRecord: DeleteRecord = DeleteRecord(
          arn.value,
          mtdItEnrolmentKey,
          syncToETMPStatus = Some(Success),
          syncToESStatus = Some(Failed)
        )
        await(repo.create(deleteRecord))
        givenRelationshipBetweenAgentAndClientExists
        givenAgentExists
        givenESDeAllocationSucceeds
        givenSetRelationshipEndedSucceeds
        givenAucdCacheRefresh

        val result: Boolean = await(underTest.checkDeleteRecordAndEventuallyResume(arn, mtdItEnrolmentKey))

        result shouldBe true
        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None
      }
    "return false if delete record found but resumption failed" in
      new TestFixture {
        val deleteRecord: DeleteRecord = DeleteRecord(
          arn.value,
          mtdItEnrolmentKey,
          syncToETMPStatus = Some(Success),
          syncToESStatus = Some(Failed)
        )
        await(repo.create(deleteRecord))
        givenAgentExists
        givenRelationshipBetweenAgentAndClientExists
        givenESDeAllocationFails

        val result: Boolean = await(underTest.checkDeleteRecordAndEventuallyResume(arn, mtdItEnrolmentKey))

        result shouldBe false
        await(repo.findBy(arn, mtdItEnrolmentKey)) should
          matchPattern {
            case Some(
                  DeleteRecord(
                    arn.value,
                    _,
                    _,
                    Some(Success),
                    Some(Failed),
                    Some(_),
                    _,
                    _,
                    _
                  )
                ) =>
          }
      }
    "return true if delete record found but authorisation missing and only need ETMP de-auth" in
      new TestFixture {
        val deleteRecord: DeleteRecord = DeleteRecord(
          arn.value,
          mtdItEnrolmentKey,
          syncToETMPStatus = Some(Failed),
          syncToESStatus = Some(Success)
        )
        await(repo.create(deleteRecord))
        givenAgentExists
        givenRelationshipBetweenAgentAndClientExists
        givenETMPDeAuthSucceeds
        givenSetRelationshipEndedSucceeds

        val result: Boolean = await(underTest.checkDeleteRecordAndEventuallyResume(arn, mtdItEnrolmentKey))

        result shouldBe true
        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None
      }
  }

  "tryToResume" should {
    "select not attempted delete record first" in
      new TestFixture {
        val deleteRecord1: DeleteRecord = DeleteRecord(
          arn.value + "1",
          EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001")),
          syncToETMPStatus = None,
          syncToESStatus = Some(Failed),
          lastRecoveryAttempt = Some(now.minusMinutes(1))
        )
        val deleteRecord2: DeleteRecord = DeleteRecord(
          arn.value,
          mtdItEnrolmentKey,
          syncToETMPStatus = Some(Failed),
          syncToESStatus = Some(Success),
          lastRecoveryAttempt = None,
          headerCarrier = None
        )
        val deleteRecord3: DeleteRecord = DeleteRecord(
          arn.value + "3",
          EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001")),
          syncToETMPStatus = Some(Success),
          syncToESStatus = Some(Failed),
          lastRecoveryAttempt = Some(now.minusMinutes(5))
        )

        await(repo.create(deleteRecord1))
        await(repo.create(deleteRecord2))
        await(repo.create(deleteRecord3))

        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe Some(deleteRecord2)

        givenRelationshipBetweenAgentAndClientExists
        givenAgentExists
        givenETMPDeAuthSucceeds
        givenSetRelationshipEndedSucceeds

        val result: Boolean = await(underTest.tryToResume(testAuditData))

        result shouldBe true
        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None
      }

    "select the oldest attempted delete record first" in
      new TestFixture {
        val deleteRecord1: DeleteRecord = DeleteRecord(
          arn.value + "1",
          EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001")),
          syncToETMPStatus = Some(Success),
          syncToESStatus = Some(Failed),
          lastRecoveryAttempt = Some(now.minusMinutes(1))
        )
        val deleteRecord2: DeleteRecord = DeleteRecord(
          arn.value,
          mtdItEnrolmentKey,
          syncToETMPStatus = Some(Success),
          syncToESStatus = Some(Failed),
          lastRecoveryAttempt = Some(now.minusMinutes(13))
        )
        val deleteRecord3: DeleteRecord = DeleteRecord(
          arn.value + "3",
          EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001")),
          syncToETMPStatus = Some(Success),
          syncToESStatus = Some(Failed),
          lastRecoveryAttempt = Some(now.minusMinutes(5))
        )

        await(repo.create(deleteRecord1))
        await(repo.create(deleteRecord2))
        await(repo.create(deleteRecord3))

        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe Some(deleteRecord2)

        givenRelationshipBetweenAgentAndClientExists
        givenAgentExists
        givenESDeAllocationSucceeds
        givenSetRelationshipEndedSucceeds
        givenAucdCacheRefresh

        val result: Boolean = await(underTest.tryToResume(testAuditData))

        result shouldBe true
        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None
      }
  }

  trait TestFixture {

    val es: EnrolmentStoreProxyConnector = mock[EnrolmentStoreProxyConnector]
    val auditService: AuditService = mock[AuditService]
    val checkService: CheckRelationshipsService = mock[CheckRelationshipsService]
    val hipConnector: HipConnector = mock[HipConnector]
    val aucdConnector: AgentUserClientDetailsConnector = mock[AgentUserClientDetailsConnector]
    val invitationService: InvitationService = mock[InvitationService]

    val repo = new FakeDeleteRecordRepository
    val lockService = new FakeLockService

    val servicesConfig: ServicesConfig = mock[ServicesConfig]
    val configuration: Configuration = mock[Configuration]

    when(servicesConfig.getInt(eqs("recovery-timeout"))).thenReturn(100)
    when(servicesConfig.getString(any[String])).thenReturn("")
    when(configuration.get[Seq[String]](eqs("internalServiceHostPatterns"))(any[ConfigLoader[Seq[String]]])).thenReturn(
      Seq(
        "^.*\\.service$",
        "^.*\\.mdtp$",
        "^localhost$"
      )
    )
    val appConfig: AppConfig = new AppConfig(configuration, servicesConfig)

    val underTest =
      new DeleteRelationshipsService(
        es,
        hipConnector,
        repo,
        aucdConnector,
        lockService,
        checkService,
        auditService,
        invitationService,
        appConfig
      )(ec)

    def givenAgentExists: OngoingStubbing[Future[String]] = when(
      es.getPrincipalGroupIdFor(eqs[Arn](arn))(any[RequestHeader])
    ).thenReturn(Future.successful(agentGroupId))

    def givenRelationshipBetweenAgentAndClientExists: OngoingStubbing[Future[Boolean]] = when(
      checkService.checkForRelationship(
        eqs(arn),
        any[Option[UserId]],
        eqs(mtdItEnrolmentKey)
      )(any[RequestHeader])
    ).thenReturn(Future.successful(true))

    def givenRelationshipBetweenAgentAndClientDoesNotExist: OngoingStubbing[Future[Boolean]] = when(
      checkService.checkForRelationship(
        eqs(arn),
        any[Option[UserId]],
        eqs(mtdItEnrolmentKey)
      )(any[RequestHeader])
    ).thenReturn(Future.successful(false))

    def givenETMPDeAuthSucceeds: OngoingStubbing[Future[Option[RegistrationRelationshipResponse]]] = when(
      hipConnector.deleteAgentRelationship(eqs(mtdItEnrolmentKey), eqs(arn))(any[RequestHeader])
    ).thenReturn(Future.successful(Some(RegistrationRelationshipResponse(now.toLocalDate.toString))))

    def givenETMPDeAuthFails: OngoingStubbing[Future[Option[RegistrationRelationshipResponse]]] = when(
      hipConnector.deleteAgentRelationship(eqs(mtdItEnrolmentKey), eqs(arn))(any[RequestHeader])
    ).thenReturn(Future.failed(new Exception))

    val noRelationshipFoundErrorMessage = """{"errors":{"processingDate":"2020-01-01T11:11:11Z","code":"014","text":"No active relationship found"}}"""

    def givenETMPDeAuthNoRelationshipFound: OngoingStubbing[Future[Option[RegistrationRelationshipResponse]]] = when(
      hipConnector.deleteAgentRelationship(eqs(mtdItEnrolmentKey), eqs(arn))(any[RequestHeader])
    ).thenReturn(Future.successful(None))

    def givenESDeAllocationSucceeds: OngoingStubbing[Future[Done]] = when(
      es.deallocateEnrolmentFromAgent(eqs(agentGroupId), eqs(mtdItEnrolmentKey))(any[RequestHeader])
    ).thenReturn(Future.successful(Done))

    def givenESDeAllocationFails: OngoingStubbing[Future[Done]] = when(
      es.deallocateEnrolmentFromAgent(eqs(agentGroupId), eqs(mtdItEnrolmentKey))(any[RequestHeader])
    ).thenReturn(Future.failed(new Exception))

    def givenESDeAllocationFailsWith(ex: Exception): OngoingStubbing[Future[Done]] = when(
      es.deallocateEnrolmentFromAgent(eqs(agentGroupId), eqs(mtdItEnrolmentKey))(any[RequestHeader])
    ).thenReturn(Future.failed(ex))

    def givenSetRelationshipEndedSucceeds: OngoingStubbing[Future[Boolean]] = when(
      invitationService.deauthoriseInvitation(
        eqs(arn),
        eqs(mtdItEnrolmentKey),
        eqs("HMRC")
      )
    ).thenReturn(Future.successful(true))

    def givenSetRelationshipEndedFails: OngoingStubbing[Future[Boolean]] = when(
      invitationService.deauthoriseInvitation(
        eqs(arn),
        eqs(mtdItEnrolmentKey),
        eqs("HMRC")
      )
    ).thenReturn(Future.successful(false))

    def givenAucdCacheRefresh: OngoingStubbing[Future[Unit]] = when(
      aucdConnector.cacheRefresh(eqs(arn))(any[RequestHeader])
    ).thenReturn(Future.successful(()))

    def verifyESDeAllocateHasBeenPerformed: Future[Done] = verify(es, times(1)).deallocateEnrolmentFromAgent(any[String], any[EnrolmentKey])(any[RequestHeader])

    def verifyESDeAllocateHasNOTBeenPerformed: Future[Done] = verify(es, never).deallocateEnrolmentFromAgent(any[String], any[EnrolmentKey])(any[RequestHeader])

    def verifyETMPDeAuthorisationHasBeenPerformed: Future[Option[RegistrationRelationshipResponse]] =
      verify(hipConnector, times(1)).deleteAgentRelationship(any[EnrolmentKey], any[Arn])(any[RequestHeader])

    def verifyETMPDeAuthorisationHasNOTBeenPerformed: Future[Option[RegistrationRelationshipResponse]] =
      verify(hipConnector, never).deleteAgentRelationship(any[EnrolmentKey], any[Arn])(any[RequestHeader])

  }

}
