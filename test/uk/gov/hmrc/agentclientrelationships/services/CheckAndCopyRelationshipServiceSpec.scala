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

import com.codahale.metrics.MetricRegistry
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq => eqs}
import org.mockito.Mockito._
import org.mockito.stubbing.OngoingStubbing
import org.scalatest.BeforeAndAfterEach
import play.api.mvc.Request
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.{ConfigLoader, Configuration}
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentKey, PartialAuthRelationship, RegistrationRelationshipResponse}
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipReference.{SaRef, VatRef}
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _, _}
import uk.gov.hmrc.agentclientrelationships.support.{Monitoring, ResettingMockitoSugar, UnitSpec}
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Service, Vrn}
import uk.gov.hmrc.domain._
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class CheckAndCopyRelationshipServiceSpec extends UnitSpec with BeforeAndAfterEach with ResettingMockitoSugar {

  val testDataGenerator = new Generator()
  val arn: Arn = Arn("AARN0000002")
  val saAgentRef: SaAgentReference = SaAgentReference("T1113T")
  val mtdItId: MtdItId = MtdItId("ABCDEF123456789")
  val mtdItEnrolmentKey: EnrolmentKey = EnrolmentKey("HMRC-MTD-IT~MTDITID~ABCDEF123456789")
  val mtdItSuppEnrolmentKey: EnrolmentKey = EnrolmentKey("HMRC-MTD-IT-SUPP~MTDITID~ABCDEF123456789")
  val vrn: Vrn = Vrn("101747641")
  val vatEnrolmentKey: EnrolmentKey = EnrolmentKey(Service.Vat, vrn)
  val agentUserId = "testUserId"
  val agentGroupId = "testGroupId"
  val agentCodeForVatDecAgent: AgentCode = AgentCode("oldAgentCode")
  val agentCodeForAsAgent: AgentCode = AgentCode("ABC1234")
  val agentUserForAsAgent = Right(AgentUser(agentUserId, agentGroupId, agentCodeForAsAgent, arn))
  val nino: Nino = testDataGenerator.nextNino
  val defaultRecord = RelationshipCopyRecord(
    arn.value,
    Some(mtdItEnrolmentKey),
    references = Some(Set(SaRef(saAgentRef))),
    syncToETMPStatus = None,
    syncToESStatus = None
  )
  val defaultRecordForMtdVat = RelationshipCopyRecord(
    arn.value,
    Some(vatEnrolmentKey),
    references = Some(Set(VatRef(agentCodeForVatDecAgent))),
    syncToETMPStatus = None,
    syncToESStatus = None
  )

  val es = resettingMock[EnrolmentStoreProxyConnector]
  val des = resettingMock[DesConnector]
  val ifConnector = resettingMock[IFConnector]
  val relationshipConnector = resettingMock[RelationshipConnector]
  val mapping = resettingMock[MappingConnector]
  val ugs = resettingMock[UsersGroupsSearchConnector]
  val aca = resettingMock[AgentClientAuthorisationConnector]
  val auditService = resettingMock[AuditService]
  val metrics = resettingMock[Metrics]
  val monitoring = resettingMock[Monitoring]
  val deleteRecordRepository = resettingMock[DeleteRecordRepository]
  val agentUserService = resettingMock[AgentUserService]
  val servicesConfig = resettingMock[ServicesConfig]
  val configuration = resettingMock[Configuration]
  val agentCacheProvider = resettingMock[AgentCacheProvider]
  val aucdConnector = resettingMock[AgentUserClientDetailsConnector]
  val partialAuthRepository = resettingMock[PartialAuthRepository]
  val invitationsRepository = resettingMock[InvitationsRepository]
  val itsaDeauthAndCleanupService = resettingMock[ItsaDeauthAndCleanupService]

  when(servicesConfig.getBoolean(eqs("features.copy-relationship.mtd-it"))).thenReturn(true)
  when(servicesConfig.getBoolean(eqs("features.copy-relationship.mtd-vat"))).thenReturn(true)
  when(servicesConfig.getBoolean(eqs("agent.cache.enabled"))).thenReturn(false)
  when(servicesConfig.getString(any[String])).thenReturn("")
  when(configuration.get[Seq[String]](eqs("internalServiceHostPatterns"))(any[ConfigLoader[Seq[String]]]))
    .thenReturn(Seq("^.*\\.service$", "^.*\\.mdtp$", "^localhost$"))
  implicit val appConfig: AppConfig = new AppConfig(configuration, servicesConfig)
  val appConfig1: AppConfig = new AppConfig(configuration, servicesConfig)

  val needsRetryStatuses = Seq[Option[SyncStatus]](None, Some(InProgress), Some(IncompleteInputParams), Some(Failed))

  val hc = HeaderCarrier()
  val ec = implicitly[ExecutionContext]

  "tryCreateITSARelationshipFromPartialAuthOrCopyAcross" should {

    needsRetryStatuses.foreach { status =>
      s"create ETMP relationship and return FoundAndCopied if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToESStatus = None" in {

        val auditData = new AuditData()
        val request = FakeRequest()

        ninoExists()
        partialAuthDoesNotExistInAca()
        cesaRelationshipExists()
        adminUserExistsForArn()
        relationshipWillBeCreated(mtdItEnrolmentKey)
        metricsStub()
        sendCreateRelationshipAuditEvent()
        deleteSameAgentOtherItsaService()

        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService

        val relationshipsService = new CheckAndCopyRelationshipsService(
          es,
          ifConnector,
          des,
          mapping,
          ugs,
          aca,
          relationshipCopyRepository,
          new CreateRelationshipsService(
            es,
            relationshipConnector,
            relationshipCopyRepository,
            lockService,
            deleteRecordRepository,
            agentUserService,
            aucdConnector,
            metrics
          ),
          partialAuthRepository,
          invitationsRepository,
          itsaDeauthAndCleanupService,
          auditService,
          metrics
        )

        val check = relationshipsService
          .checkForOldRelationshipAndCopy(arn, mtdItEnrolmentKey)(ec, hc, request, auditData)

        await(check) shouldBe FoundAndCopied

        verifyEtmpRecordCreated()
        val auditDetails = verifyAuditEventSent()
        auditDetails("etmpRelationshipCreated") shouldBe true
        await(relationshipCopyRepository.findBy(arn, mtdItEnrolmentKey)).value.syncToETMPStatus shouldBe Some(Success)
      }

      s"skip recovery of ETMP relationship but still return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = $status and syncToESStatus = None " +
        s"and recovery of this relationship is already in progress" in {
          val record = defaultRecord.copy(syncToETMPStatus = status, syncToESStatus = None)
          val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
          await(relationshipCopyRepository.create(record))
          val lockService = new FakeLockService
          val relationshipsService = new CheckAndCopyRelationshipsService(
            es,
            ifConnector,
            des,
            mapping,
            ugs,
            aca,
            relationshipCopyRepository,
            new CreateRelationshipsService(
              es,
              relationshipConnector,
              relationshipCopyRepository,
              lockService,
              deleteRecordRepository,
              agentUserService,
              aucdConnector,
              metrics
            ),
            partialAuthRepository,
            invitationsRepository,
            itsaDeauthAndCleanupService,
            auditService,
            metrics
          )

          val auditData = new AuditData()
          val request = FakeRequest()

          ninoExists()
          partialAuthDoesNotExistInAca()
          cesaRelationshipExists()
          relationshipWillBeCreated(mtdItEnrolmentKey)
          metricsStub()

          val maybeCheck: Option[CheckAndCopyResult] = await(lockService.tryLock(arn, mtdItEnrolmentKey) {
            relationshipsService
              .checkForOldRelationshipAndCopy(arn, mtdItEnrolmentKey)(ec, hc, request, auditData)
          })

          maybeCheck.value shouldBe FoundButLockedCouldNotCopy

          verifyEtmpRecordNotCreated()
          val auditDetails = verifyAuditEventSent()
          auditDetails.get("etmpRelationshipCreated") shouldBe None
          await(relationshipCopyRepository.findBy(arn, mtdItEnrolmentKey)).value.syncToETMPStatus shouldBe status
        }
    }
    // We ignore the RelationshipCopyRecord if there is no relationship in CESA as a failsafe in case we have made a logic error.
    // However we will probably need to change this when we implement recovery for relationships that were created explicitly (i.e. not copied from CESA).
    needsRetryStatuses.foreach { status =>
      s"not create ETMP relationship if no relationship currently exists in CESA and there is no PartialAuth invitation" +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToESStatus = None" in {
          val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
          val lockService = new FakeLockService
          val relationshipsService = new CheckAndCopyRelationshipsService(
            es,
            ifConnector,
            des,
            mapping,
            ugs,
            aca,
            relationshipCopyRepository,
            new CreateRelationshipsService(
              es,
              relationshipConnector,
              relationshipCopyRepository,
              lockService,
              deleteRecordRepository,
              agentUserService,
              aucdConnector,
              metrics
            ),
            partialAuthRepository,
            invitationsRepository,
            itsaDeauthAndCleanupService,
            auditService,
            metrics
          )

          val auditData = new AuditData()
          val request = FakeRequest()

          ninoExists()
          partialAuthDoesNotExistInAca()
          cesaRelationshipDoesNotExist()

          val check = relationshipsService
            .checkForOldRelationshipAndCopy(arn, mtdItEnrolmentKey)(ec, hc, request, auditData)
          await(check) shouldBe NotFound

          verifyEtmpRecordNotCreated()
        }
    }

    needsRetryStatuses.filterNot(s => s.contains(InProgress) || s.contains(InProgress)) foreach { status =>
      s"create ES relationship (only) and return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = Success and syncToESStatus = $status" in {
          val record = defaultRecord.copy(syncToETMPStatus = Some(Success), syncToESStatus = status)
          val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
          val lockService = new FakeLockService
          when(deleteRecordRepository.create(any[DeleteRecord])).thenReturn(Future.successful(1))
          when(deleteRecordRepository.remove(any[Arn], any[EnrolmentKey]))
            .thenReturn(Future.successful(1))
          when(
            agentUserService.getAgentAdminUserFor(any[Arn])(any[ExecutionContext], any[HeaderCarrier], any[AuditData])
          )
            .thenReturn(Future.successful(agentUserForAsAgent))
          when(
            agentUserService.getAgentAdminUserFor(any[Arn])(any[ExecutionContext], any[HeaderCarrier], any[AuditData])
          )
            .thenReturn(Future.successful(agentUserForAsAgent))
          sendCreateRelationshipAuditEvent()
          deleteSameAgentOtherItsaService()

          val relationshipsService = new CheckAndCopyRelationshipsService(
            es,
            ifConnector,
            des,
            mapping,
            ugs,
            aca,
            relationshipCopyRepository,
            new CreateRelationshipsService(
              es,
              relationshipConnector,
              relationshipCopyRepository,
              lockService,
              deleteRecordRepository,
              agentUserService,
              aucdConnector,
              metrics
            ),
            partialAuthRepository,
            invitationsRepository,
            itsaDeauthAndCleanupService,
            auditService,
            metrics
          )
          await(relationshipCopyRepository.create(record))
          val auditData = new AuditData()
          val request = FakeRequest()

          ninoExists()
          partialAuthDoesNotExistInAca()
          arnExistsForGroupId()
          previousRelationshipWillBeRemoved(mtdItEnrolmentKey)
          cesaRelationshipExists()
          relationshipWillBeCreated(mtdItEnrolmentKey)
          metricsStub()

          val check = relationshipsService
            .checkForOldRelationshipAndCopy(arn, mtdItEnrolmentKey)(ec, hc, request, auditData)

          await(check) shouldBe FoundAndCopied

          verifyEsRecordCreated()
          verifyEtmpRecordNotCreated()
          val auditDetails = verifyAuditEventSent()
          auditDetails.get("etmpRelationshipCreated") shouldBe None
          auditDetails("enrolmentDelegated") shouldBe true
          await(relationshipCopyRepository.findBy(arn, mtdItEnrolmentKey)).value.syncToESStatus shouldBe Some(Success)
        }

      s"skip recovery of ES relationship and return FoundButLockedCouldNotCopy if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = $status and syncToESStatus = None " +
        s"and recovery of this relationship is already in progress" in {
          val record = defaultRecord.copy(syncToETMPStatus = Some(Success), syncToESStatus = status)
          val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
          val lockService = new FakeLockService
          val relationshipsService = new CheckAndCopyRelationshipsService(
            es,
            ifConnector,
            des,
            mapping,
            ugs,
            aca,
            relationshipCopyRepository,
            new CreateRelationshipsService(
              es,
              relationshipConnector,
              relationshipCopyRepository,
              lockService,
              deleteRecordRepository,
              agentUserService,
              aucdConnector,
              metrics
            ),
            partialAuthRepository,
            invitationsRepository,
            itsaDeauthAndCleanupService,
            auditService,
            metrics
          )
          await(relationshipCopyRepository.create(record))
          val auditData = new AuditData()
          val request = FakeRequest()

          ninoExists()
          partialAuthDoesNotExistInAca()
          cesaRelationshipExists()
          relationshipWillBeCreated(mtdItEnrolmentKey)
          metricsStub()

          val maybeCheck = await(lockService.tryLock(arn, mtdItEnrolmentKey) {
            relationshipsService
              .checkForOldRelationshipAndCopy(arn, mtdItEnrolmentKey)(ec, hc, request, auditData)
          })

          maybeCheck.value shouldBe FoundButLockedCouldNotCopy

          verifyEtmpRecordNotCreated()
          verifyEsRecordNotCreated()
          val auditDetails = verifyAuditEventSent()
          auditDetails.get("etmpRelationshipCreated") shouldBe None
          auditDetails.get("enrolmentDelegated") shouldBe None
          await(relationshipCopyRepository.findBy(arn, mtdItEnrolmentKey)).value.syncToESStatus shouldBe status
        }
    }

    needsRetryStatuses.foreach { status =>
      s"not create ES relationship if no relationship currently exists in CESA and no AltItsa invitation " +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = Success and syncToESStatus = $status" in {
          val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
          val lockService = new FakeLockService
          val relationshipsService = new CheckAndCopyRelationshipsService(
            es,
            ifConnector,
            des,
            mapping,
            ugs,
            aca,
            relationshipCopyRepository,
            new CreateRelationshipsService(
              es,
              relationshipConnector,
              relationshipCopyRepository,
              lockService,
              deleteRecordRepository,
              agentUserService,
              aucdConnector,
              metrics
            ),
            partialAuthRepository,
            invitationsRepository,
            itsaDeauthAndCleanupService,
            auditService,
            metrics
          )

          val auditData = new AuditData()
          val request = FakeRequest()

          ninoExists()
          partialAuthDoesNotExistInAca()
          cesaRelationshipDoesNotExist()

          val check = relationshipsService
            .checkForOldRelationshipAndCopy(arn, mtdItEnrolmentKey)(ec, hc, request, auditData)
          await(check) shouldBe NotFound

          verifyEsRecordNotCreated()
          verifyEtmpRecordNotCreated()
        }
    }

    needsRetryStatuses.foreach { status =>
      s"create ETMP relationship (only) and return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = $status and syncToESStatus = Success " +
        s"even though we don't expect this to happen because we always create the ETMP record first" in {
          val record = defaultRecord.copy(syncToETMPStatus = status, syncToESStatus = Some(Success))
          val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
          await(relationshipCopyRepository.create(record))
          val lockService = new FakeLockService
          val relationshipsService = new CheckAndCopyRelationshipsService(
            es,
            ifConnector,
            des,
            mapping,
            ugs,
            aca,
            relationshipCopyRepository,
            new CreateRelationshipsService(
              es,
              relationshipConnector,
              relationshipCopyRepository,
              lockService,
              deleteRecordRepository,
              agentUserService,
              aucdConnector,
              metrics
            ),
            partialAuthRepository,
            invitationsRepository,
            itsaDeauthAndCleanupService,
            auditService,
            metrics
          )

          val auditData = new AuditData()
          val request = FakeRequest()

          ninoExists()
          partialAuthDoesNotExistInAca()
          cesaRelationshipExists()
          relationshipWillBeCreated(mtdItEnrolmentKey)
          metricsStub()
          auditStub()
          sendCreateRelationshipAuditEvent()
          deleteSameAgentOtherItsaService()

          val check = relationshipsService
            .checkForOldRelationshipAndCopy(arn, mtdItEnrolmentKey)(ec, hc, request, auditData)

          await(check) shouldBe FoundAndCopied

          verifyEsRecordNotCreated()
          verifyEtmpRecordCreated()
          val auditDetails = verifyAuditEventSent()
          auditDetails("etmpRelationshipCreated") shouldBe true
          auditDetails.get("enrolmentDelegated") shouldBe None
          await(relationshipCopyRepository.findBy(arn, mtdItEnrolmentKey)).value.syncToETMPStatus shouldBe Some(Success)
        }
    }

    // We ignore the RelationshipCopyRecord if there is no relationship in CESA as a failsafe in case we have made a logic error.
    // However we will probably need to change this when we implement recovery for relationships that were created explicitly (i.e. not copied from CESA).
    needsRetryStatuses.foreach { status =>
      s"not create ES relationship if no relationship currently exists in CESA (and no AltItsa invitation found)" +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToESStatus = Success" in {
          val record = defaultRecord.copy(syncToETMPStatus = status, syncToESStatus = Some(Success))
          val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
          await(relationshipCopyRepository.create(record))
          val lockService = new FakeLockService
          val relationshipsService = new CheckAndCopyRelationshipsService(
            es,
            ifConnector,
            des,
            mapping,
            ugs,
            aca,
            relationshipCopyRepository,
            new CreateRelationshipsService(
              es,
              relationshipConnector,
              relationshipCopyRepository,
              lockService,
              deleteRecordRepository,
              agentUserService,
              aucdConnector,
              metrics
            ),
            partialAuthRepository,
            invitationsRepository,
            itsaDeauthAndCleanupService,
            auditService,
            metrics
          )

          val auditData = new AuditData()
          val request = FakeRequest()

          ninoExists()
          partialAuthDoesNotExistInAca()
          cesaRelationshipDoesNotExist()

          val check = relationshipsService
            .checkForOldRelationshipAndCopy(arn, mtdItEnrolmentKey)(ec, hc, request, auditData)
          await(check) shouldBe NotFound

          verifyEsRecordNotCreated()
          verifyEtmpRecordNotCreated()
        }
    }

    s"not create relationship if Partialauth is for $HMRCMTDITSUPP but the request is for $HMRCMTDIT" in {
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      val lockService = new FakeLockService
      val relationshipsService = new CheckAndCopyRelationshipsService(
        es,
        ifConnector,
        des,
        mapping,
        ugs,
        aca,
        relationshipCopyRepository,
        new CreateRelationshipsService(
          es,
          relationshipConnector,
          relationshipCopyRepository,
          lockService,
          deleteRecordRepository,
          agentUserService,
          aucdConnector,
          metrics
        ),
        partialAuthRepository,
        invitationsRepository,
        itsaDeauthAndCleanupService,
        auditService,
        metrics
      )

      val auditData = new AuditData()
      val request = FakeRequest()

      ninoExists()
      partialAuthExistsInAcr(HMRCMTDITSUPP)

      val check = relationshipsService
        .checkForOldRelationshipAndCopy(arn, mtdItEnrolmentKey)(ec, hc, request, auditData)
      await(check) shouldBe NotFound

      verifyEsRecordNotCreated()
      verifyEtmpRecordNotCreated()
    }

    "return Upstream5xxResponse, if the mapping service is unavailable" in {
      val lockService = new FakeLockService
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      val relationshipsService = new CheckAndCopyRelationshipsService(
        es,
        ifConnector,
        des,
        mapping,
        ugs,
        aca,
        relationshipCopyRepository,
        new CreateRelationshipsService(
          es,
          relationshipConnector,
          relationshipCopyRepository,
          lockService,
          deleteRecordRepository,
          agentUserService,
          aucdConnector,
          metrics
        ),
        partialAuthRepository,
        invitationsRepository,
        itsaDeauthAndCleanupService,
        auditService,
        metrics
      )
      val auditData = new AuditData()
      val request = FakeRequest()

      ninoExists()
      partialAuthDoesNotExistInAca()
      mappingServiceUnavailable()

      val check = relationshipsService
        .checkForOldRelationshipAndCopy(arn, mtdItEnrolmentKey)(ec, hc, request, auditData)

      an[UpstreamErrorResponse] should be thrownBy await(check)
      verifyEsRecordNotCreated()
      verifyEtmpRecordNotCreated()
    }

    "not create ETMP or ES relationship if RelationshipCopyRecord exists with syncToETMPStatus = Success and syncToESStatus = Success" in {
      val record = defaultRecord.copy(syncToETMPStatus = Some(Success), syncToESStatus = Some(Success))
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      await(relationshipCopyRepository.create(record))
      val lockService = new FakeLockService
      val relationshipsService = new CheckAndCopyRelationshipsService(
        es,
        ifConnector,
        des,
        mapping,
        ugs,
        aca,
        relationshipCopyRepository,
        new CreateRelationshipsService(
          es,
          relationshipConnector,
          relationshipCopyRepository,
          lockService,
          deleteRecordRepository,
          agentUserService,
          aucdConnector,
          metrics
        ),
        partialAuthRepository,
        invitationsRepository,
        itsaDeauthAndCleanupService,
        auditService,
        metrics
      )

      val auditData = new AuditData()
      val request = FakeRequest()

      ninoExists()
      partialAuthDoesNotExistInAca()
      cesaRelationshipExists()
      relationshipWillBeCreated(mtdItEnrolmentKey)

      val check = relationshipsService
        .checkForOldRelationshipAndCopy(arn, mtdItEnrolmentKey)(ec, hc, request, auditData)

      val checkAndCopyResult = await(check)
      checkAndCopyResult shouldBe AlreadyCopiedDidNotCheck
      checkAndCopyResult.grantAccess shouldBe false

      verifyEsRecordNotCreated()
      verifyEtmpRecordNotCreated()
    }

    "not createES relationship if RelationshipCopyRecord exists syncToESStatus is In Progress because we can't tell " +
      "whether the ES copy happened successfully but the the status update failed e.g. due to a container kill and in " +
      "that case we would be re-copying a relationship that has been removed in ES. It is more important that we never " +
      "copy to ES twice than that the synch works!" in {
        val record = defaultRecord.copy(syncToETMPStatus = Some(Success), syncToESStatus = Some(InProgress))
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        await(relationshipCopyRepository.create(record))
        val lockService = new FakeLockService
        val relationshipsService = new CheckAndCopyRelationshipsService(
          es,
          ifConnector,
          des,
          mapping,
          ugs,
          aca,
          relationshipCopyRepository,
          new CreateRelationshipsService(
            es,
            relationshipConnector,
            relationshipCopyRepository,
            lockService,
            deleteRecordRepository,
            agentUserService,
            aucdConnector,
            metrics
          ),
          partialAuthRepository,
          invitationsRepository,
          itsaDeauthAndCleanupService,
          auditService,
          metrics
        )

        val auditData = new AuditData()
        val request = FakeRequest()

        ninoExists()
        partialAuthDoesNotExistInAca()
        cesaRelationshipExists()
        relationshipWillBeCreated(mtdItEnrolmentKey)

        val check = relationshipsService
          .checkForOldRelationshipAndCopy(arn, mtdItEnrolmentKey)(ec, hc, request, auditData)

        val checkAndCopyResult = await(check)
        checkAndCopyResult shouldBe AlreadyCopiedDidNotCheck
        checkAndCopyResult.grantAccess shouldBe false

        verifyEsRecordNotCreated()
        verifyEtmpRecordNotCreated()
      }

    "allow only a single request at a time to create a relationship for MTD-IT i.e. apply a lock on the first request before creating " +
      "a relationshipCopyRecord and return FoundButLockedCouldNotCopy to any request that may arrive while copy across is in progress" in {
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new CheckAndCopyRelationshipsService(
          es,
          ifConnector,
          des,
          mapping,
          ugs,
          aca,
          relationshipCopyRepository,
          new CreateRelationshipsService(
            es,
            relationshipConnector,
            relationshipCopyRepository,
            lockService,
            deleteRecordRepository,
            agentUserService,
            aucdConnector,
            metrics
          ),
          partialAuthRepository,
          invitationsRepository,
          itsaDeauthAndCleanupService,
          auditService,
          metrics
        )

        val auditData = new AuditData()
        val request = FakeRequest()

        ninoExists()
        partialAuthDoesNotExistInAca()
        cesaRelationshipExists()
        metricsStub()

        val check = await(lockService.tryLock(arn, mtdItEnrolmentKey) {
          relationshipsService
            .checkForOldRelationshipAndCopy(arn, mtdItEnrolmentKey)(ec, hc, request, auditData)
        })

        check.value shouldBe FoundButLockedCouldNotCopy

        verifyEtmpRecordNotCreated()
        verifyEsRecordNotCreated()

        await(relationshipCopyRepository.findBy(arn, mtdItEnrolmentKey)) shouldBe None
      }

    "allow only a single request at a time to create a relationship for MTD-VAT i.e. apply a lock on the first request before creating " +
      "a relationshipCopyRecord and return FoundButLockedCouldNotCopy to any request that may arrive while copy across is in progress" in {
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new CheckAndCopyRelationshipsService(
          es,
          ifConnector,
          des,
          mapping,
          ugs,
          aca,
          relationshipCopyRepository,
          new CreateRelationshipsService(
            es,
            relationshipConnector,
            relationshipCopyRepository,
            lockService,
            deleteRecordRepository,
            agentUserService,
            aucdConnector,
            metrics
          ),
          partialAuthRepository,
          invitationsRepository,
          itsaDeauthAndCleanupService,
          auditService,
          metrics
        )

        val auditData = new AuditData()
        val request = FakeRequest()

        oldESRelationshipExists()
        vrnIsKnownInETMP(vrn, true)
        metricsStub()
        auditStub()

        val check = await(lockService.tryLock(arn, vatEnrolmentKey) {
          relationshipsService
            .checkForOldRelationshipAndCopy(arn, vatEnrolmentKey)(ec, hc, request, auditData)
        })

        check.value shouldBe FoundButLockedCouldNotCopy

        verifyEtmpRecordNotCreated()
        verifyEsRecordNotCreated()

        await(relationshipCopyRepository.findBy(arn, vatEnrolmentKey)) shouldBe None
      }

    "create relationship when there is no legacy relationship in CESA but there is a alt-itsa authorisation in place." in {
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      val lockService = new FakeLockService
      val relationshipsService = new CheckAndCopyRelationshipsService(
        es,
        ifConnector,
        des,
        mapping,
        ugs,
        aca,
        relationshipCopyRepository,
        new CreateRelationshipsService(
          es,
          relationshipConnector,
          relationshipCopyRepository,
          lockService,
          deleteRecordRepository,
          agentUserService,
          aucdConnector,
          metrics
        ),
        partialAuthRepository,
        invitationsRepository,
        itsaDeauthAndCleanupService,
        auditService,
        metrics
      )

      val auditData = new AuditData()
      val request = FakeRequest()

      ninoExists()
      partialAuthExistsInAcr(HMRCMTDIT)
      adminUserExistsForArn()
      previousRelationshipWillBeRemoved(mtdItEnrolmentKey)
      relationshipWillBeCreated(mtdItEnrolmentKey)
      partialAuthDeleted(HMRCMTDIT)
      partialAuthStatusUpdatedToAcceptedInACR(HMRCMTDIT)
      metricsStub()
      auditStub()
      sendCreateRelationshipAuditEvent()
      deleteSameAgentOtherItsaService()

      val check = relationshipsService
        .checkForOldRelationshipAndCopy(arn, mtdItEnrolmentKey)(ec, hc, request, auditData)
      await(check) shouldBe AltItsaCreateRelationshipSuccess(HMRCMTDIT)
    }

    "create relationship when there is an alt-itsa authorisation in place for SUPP agent type. " in {
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      val lockService = new FakeLockService
      val relationshipsService = new CheckAndCopyRelationshipsService(
        es,
        ifConnector,
        des,
        mapping,
        ugs,
        aca,
        relationshipCopyRepository,
        new CreateRelationshipsService(
          es,
          relationshipConnector,
          relationshipCopyRepository,
          lockService,
          deleteRecordRepository,
          agentUserService,
          aucdConnector,
          metrics
        ),
        partialAuthRepository,
        invitationsRepository,
        itsaDeauthAndCleanupService,
        auditService,
        metrics
      )

      val auditData = new AuditData()
      val request = FakeRequest()

      ninoExists()
      partialAuthExistsInAcr(HMRCMTDITSUPP)
      adminUserExistsForArn()
      relationshipWillBeCreated(mtdItSuppEnrolmentKey)
      partialAuthDeleted(HMRCMTDITSUPP)
      partialAuthStatusUpdatedToAcceptedInACR(HMRCMTDITSUPP)
      metricsStub()
      auditStub()
      sendCreateRelationshipAuditEvent()
      deleteSameAgentOtherItsaService()

      val check = relationshipsService
        .checkForOldRelationshipAndCopy(arn, mtdItSuppEnrolmentKey)(ec, hc, request, auditData)
      await(check) shouldBe AltItsaCreateRelationshipSuccess(HMRCMTDITSUPP)
    }

    "create relationship when there is an alt-itsa MAIN authorisation in place from ACA " in {
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      val lockService = new FakeLockService
      val relationshipsService = new CheckAndCopyRelationshipsService(
        es,
        ifConnector,
        des,
        mapping,
        ugs,
        aca,
        relationshipCopyRepository,
        new CreateRelationshipsService(
          es,
          relationshipConnector,
          relationshipCopyRepository,
          lockService,
          deleteRecordRepository,
          agentUserService,
          aucdConnector,
          metrics
        ),
        partialAuthRepository,
        invitationsRepository,
        itsaDeauthAndCleanupService,
        auditService,
        metrics
      )

      val auditData = new AuditData()
      val request = FakeRequest()

      ninoExists()
      partialAuthExistsInAca(HMRCMTDIT)
      adminUserExistsForArn()
      relationshipWillBeCreated(mtdItEnrolmentKey)
      previousRelationshipWillBeRemoved(mtdItEnrolmentKey)
      partialAuthStatusUpdatedToAcceptedInACA(HMRCMTDIT)
      metricsStub()
      auditStub()
      sendCreateRelationshipAuditEvent()
      deleteSameAgentOtherItsaService()

      val check = relationshipsService
        .checkForOldRelationshipAndCopy(arn, mtdItEnrolmentKey)(ec, hc, request, auditData)
      await(check) shouldBe AltItsaCreateRelationshipSuccess(HMRCMTDIT)
    }
  }

  "checkESForOldRelationshipAndCopyForMtdVat" should {

    needsRetryStatuses.foreach { status =>
      s"create ETMP relationship and return FoundAndCopied if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToESStatus = None" in {
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new CheckAndCopyRelationshipsService(
          es,
          ifConnector,
          des,
          mapping,
          ugs,
          aca,
          relationshipCopyRepository,
          new CreateRelationshipsService(
            es,
            relationshipConnector,
            relationshipCopyRepository,
            lockService,
            deleteRecordRepository,
            agentUserService,
            aucdConnector,
            metrics
          ),
          partialAuthRepository,
          invitationsRepository,
          itsaDeauthAndCleanupService,
          auditService,
          metrics
        )

        val auditData = new AuditData()
        val request = FakeRequest()

        oldESRelationshipExists()
        vrnIsKnownInETMP(vrn, true)
        adminUserExistsForArn()
        relationshipWillBeCreated(vatEnrolmentKey)
        metricsStub()
        auditStub()

        val check = relationshipsService
          .checkForOldRelationshipAndCopy(arn, vatEnrolmentKey)(ec, hc, request, auditData)

        await(check) shouldBe FoundAndCopied

        verifyEtmpRecordCreatedForMtdVat()
        val auditDetails = verifyESAuditEventSent()
        auditDetails("etmpRelationshipCreated") shouldBe true
        auditDetails("vrnExistsInEtmp") shouldBe true
        await(relationshipCopyRepository.findBy(arn, vatEnrolmentKey)).value.syncToETMPStatus shouldBe Some(Success)
      }

      s"skip recovery of ETMP relationship and return FoundButLockedCouldNotCopy if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = $status and syncToESStatus = None " +
        s"and recovery of this relationship is already in progress" in {
          val record = defaultRecordForMtdVat.copy(syncToETMPStatus = status, syncToESStatus = None)
          val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
          await(relationshipCopyRepository.create(record))
          val lockService = new FakeLockService
          val relationshipsService = new CheckAndCopyRelationshipsService(
            es,
            ifConnector,
            des,
            mapping,
            ugs,
            aca,
            relationshipCopyRepository,
            new CreateRelationshipsService(
              es,
              relationshipConnector,
              relationshipCopyRepository,
              lockService,
              deleteRecordRepository,
              agentUserService,
              aucdConnector,
              metrics
            ),
            partialAuthRepository,
            invitationsRepository,
            itsaDeauthAndCleanupService,
            auditService,
            metrics
          )

          val auditData = new AuditData()
          val request = FakeRequest()

          oldESRelationshipExists()
          vrnIsKnownInETMP(vrn, true)
          relationshipWillBeCreated(vatEnrolmentKey)
          metricsStub()
          auditStub()

          val maybeCheck: Option[CheckAndCopyResult] = await(lockService.tryLock(arn, vatEnrolmentKey) {
            relationshipsService
              .checkForOldRelationshipAndCopy(arn, vatEnrolmentKey)(ec, hc, request, auditData)
          })

          maybeCheck.value shouldBe FoundButLockedCouldNotCopy

          verifyEtmpRecordNotCreatedForMtdVat()
          val auditDetails = verifyESAuditEventSent()
          auditDetails.get("etmpRelationshipCreated") shouldBe None
          await(relationshipCopyRepository.findBy(arn, vatEnrolmentKey)).value.syncToETMPStatus shouldBe status
        }
    }
    // We ignore the RelationshipCopyRecord if there is no relationship in ES as a failsafe in case we have made a logic error.
    // However we will probably need to change this when we implement recovery for relationships that were created explicitly (i.e. not copied from ES).
    needsRetryStatuses.foreach { status =>
      s"not create ETMP relationship if no relationship currently exists in HMCE-VATDEC-ORG " +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToESStatus = None" in {
          val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
          val lockService = new FakeLockService
          val relationshipsService = new CheckAndCopyRelationshipsService(
            es,
            ifConnector,
            des,
            mapping,
            ugs,
            aca,
            relationshipCopyRepository,
            new CreateRelationshipsService(
              es,
              relationshipConnector,
              relationshipCopyRepository,
              lockService,
              deleteRecordRepository,
              agentUserService,
              aucdConnector,
              metrics
            ),
            partialAuthRepository,
            invitationsRepository,
            itsaDeauthAndCleanupService,
            auditService,
            metrics
          )

          val auditData = new AuditData()
          val request = FakeRequest()

          oldESRelationshipDoesNotExist()
          auditStub()

          val check = relationshipsService
            .checkForOldRelationshipAndCopy(arn, vatEnrolmentKey)(ec, hc, request, auditData)
          await(check) shouldBe NotFound

          verifyEtmpRecordNotCreatedForMtdVat()
        }
    }

    s"return VrnNotFoundInEtmp when relationship currently exists in HMCE-VATDEC-ORG but Vrn is not known in ETMP " in {
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      val lockService = new FakeLockService
      val relationshipsService = new CheckAndCopyRelationshipsService(
        es,
        ifConnector,
        des,
        mapping,
        ugs,
        aca,
        relationshipCopyRepository,
        new CreateRelationshipsService(
          es,
          relationshipConnector,
          relationshipCopyRepository,
          lockService,
          deleteRecordRepository,
          agentUserService,
          aucdConnector,
          metrics
        ),
        partialAuthRepository,
        invitationsRepository,
        itsaDeauthAndCleanupService,
        auditService,
        metrics
      )

      val auditData = new AuditData()
      val request = FakeRequest()

      oldESRelationshipExists()
      vrnIsKnownInETMP(vrn, false)
      auditStub()

      val check = relationshipsService
        .checkForOldRelationshipAndCopy(arn, vatEnrolmentKey)(ec, hc, request, auditData)
      await(check) shouldBe VrnNotFoundInEtmp

      verifyEtmpRecordNotCreatedForMtdVat()
    }

    needsRetryStatuses.filterNot(s => s.contains(InProgress) || s.contains(InProgress)) foreach { status =>
      s"create ES relationship (only) and return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = Success and syncToESStatus = $status" in {
          val record = defaultRecordForMtdVat.copy(syncToETMPStatus = Some(Success), syncToESStatus = status)
          val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
          val lockService = new FakeLockService
          when(deleteRecordRepository.create(any[DeleteRecord])).thenReturn(Future.successful(1))
          when(deleteRecordRepository.remove(any[Arn], any[EnrolmentKey]))
            .thenReturn(Future.successful(1))
          when(
            agentUserService.getAgentAdminUserFor(any[Arn])(any[ExecutionContext], any[HeaderCarrier], any[AuditData])
          )
            .thenReturn(Future.successful(agentUserForAsAgent))

          val relationshipsService = new CheckAndCopyRelationshipsService(
            es,
            ifConnector,
            des,
            mapping,
            ugs,
            aca,
            relationshipCopyRepository,
            new CreateRelationshipsService(
              es,
              relationshipConnector,
              relationshipCopyRepository,
              lockService,
              deleteRecordRepository,
              agentUserService,
              aucdConnector,
              metrics
            ),
            partialAuthRepository,
            invitationsRepository,
            itsaDeauthAndCleanupService,
            auditService,
            metrics
          )
          await(relationshipCopyRepository.create(record))
          val auditData = new AuditData()
          val request = FakeRequest()

          arnExistsForGroupId()
          oldESRelationshipExists()
          vrnIsKnownInETMP(vrn, true)
          previousRelationshipWillBeRemoved(vatEnrolmentKey)
          relationshipWillBeCreated(vatEnrolmentKey)
          metricsStub()
          auditStub()

          val check = relationshipsService
            .checkForOldRelationshipAndCopy(arn, vatEnrolmentKey)(ec, hc, request, auditData)

          await(check) shouldBe FoundAndCopied

          verifyEsRecordCreatedForMtdVat()
          verifyEtmpRecordNotCreatedForMtdVat()
          val auditDetails = verifyESAuditEventSent()
          auditDetails.get("etmpRelationshipCreated") shouldBe None
          auditDetails("enrolmentDelegated") shouldBe true
          await(relationshipCopyRepository.findBy(arn, vatEnrolmentKey)).value.syncToESStatus shouldBe Some(Success)
        }

      s"skip recovery of ES relationship return FoundButLockedCouldNotCopy if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = $status and syncToESStatus = None " +
        s"and recovery of this relationship is already in progress" in {
          val record = defaultRecordForMtdVat.copy(syncToETMPStatus = Some(Success), syncToESStatus = status)
          val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
          val lockService = new FakeLockService
          val relationshipsService = new CheckAndCopyRelationshipsService(
            es,
            ifConnector,
            des,
            mapping,
            ugs,
            aca,
            relationshipCopyRepository,
            new CreateRelationshipsService(
              es,
              relationshipConnector,
              relationshipCopyRepository,
              lockService,
              deleteRecordRepository,
              agentUserService,
              aucdConnector,
              metrics
            ),
            partialAuthRepository,
            invitationsRepository,
            itsaDeauthAndCleanupService,
            auditService,
            metrics
          )
          await(relationshipCopyRepository.create(record))
          val auditData = new AuditData()
          val request = FakeRequest()

          oldESRelationshipExists()
          relationshipWillBeCreated(vatEnrolmentKey)
          vrnIsKnownInETMP(vrn, true)
          metricsStub()
          auditStub()

          val maybeCheck = await(lockService.tryLock(arn, vatEnrolmentKey) {
            relationshipsService
              .checkForOldRelationshipAndCopy(arn, vatEnrolmentKey)(ec, hc, request, auditData)
          })

          maybeCheck.value shouldBe FoundButLockedCouldNotCopy

          verifyEtmpRecordNotCreatedForMtdVat()
          verifyEsRecordNotCreated()
          val auditDetails = verifyESAuditEventSent()
          auditDetails.get("etmpRelationshipCreated") shouldBe None
          auditDetails.get("enrolmentDelegated") shouldBe None
          await(relationshipCopyRepository.findBy(arn, vatEnrolmentKey)).value.syncToESStatus shouldBe status
        }
    }

    needsRetryStatuses.foreach { status =>
      s"not create ES relationship if no relationship currently exists in HMCE-VATDEC-ORG " +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = Success and syncToESStatus = $status" in {
          val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
          val lockService = new FakeLockService
          val relationshipsService = new CheckAndCopyRelationshipsService(
            es,
            ifConnector,
            des,
            mapping,
            ugs,
            aca,
            relationshipCopyRepository,
            new CreateRelationshipsService(
              es,
              relationshipConnector,
              relationshipCopyRepository,
              lockService,
              deleteRecordRepository,
              agentUserService,
              aucdConnector,
              metrics
            ),
            partialAuthRepository,
            invitationsRepository,
            itsaDeauthAndCleanupService,
            auditService,
            metrics
          )

          val auditData = new AuditData()
          val request = FakeRequest()

          oldESRelationshipDoesNotExist()
          auditStub()

          val check = relationshipsService
            .checkForOldRelationshipAndCopy(arn, vatEnrolmentKey)(ec, hc, request, auditData)
          await(check) shouldBe NotFound

          verifyEsRecordNotCreated()
          verifyEtmpRecordNotCreatedForMtdVat()
        }
    }

    needsRetryStatuses.foreach { status =>
      s"create ETMP relationship (only) and return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = $status and syncToESStatus = Success " +
        s"even though we don't expect this to happen because we always create the ETMP record first" in {
          val record = defaultRecordForMtdVat.copy(syncToETMPStatus = status, syncToESStatus = Some(Success))
          val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
          await(relationshipCopyRepository.create(record))
          val lockService = new FakeLockService
          val relationshipsService = new CheckAndCopyRelationshipsService(
            es,
            ifConnector,
            des,
            mapping,
            ugs,
            aca,
            relationshipCopyRepository,
            new CreateRelationshipsService(
              es,
              relationshipConnector,
              relationshipCopyRepository,
              lockService,
              deleteRecordRepository,
              agentUserService,
              aucdConnector,
              metrics
            ),
            partialAuthRepository,
            invitationsRepository,
            itsaDeauthAndCleanupService,
            auditService,
            metrics
          )

          val auditData = new AuditData()
          val request = FakeRequest()

          metricsStub()
          auditStub()
          oldESRelationshipExists()
          vrnIsKnownInETMP(vrn, true)
          relationshipWillBeCreated(vatEnrolmentKey)

          val check = relationshipsService
            .checkForOldRelationshipAndCopy(arn, vatEnrolmentKey)(ec, hc, request, auditData)

          await(check) shouldBe FoundAndCopied

          verifyEsRecordNotCreatedMtdVat()
          verifyEtmpRecordCreatedForMtdVat()
          val auditDetails = verifyESAuditEventSent()
          auditDetails("etmpRelationshipCreated") shouldBe true
          auditDetails.get("enrolmentDelegated") shouldBe None
          await(relationshipCopyRepository.findBy(arn, vatEnrolmentKey)).value.syncToETMPStatus shouldBe Some(Success)
        }
    }

    // We ignore the RelationshipCopyRecord if there is no relationship in HMCE-VATDEC-ORG as a failsafe in case we have made a logic error.
    // However we will probably need to change this when we implement recovery for relationships that were created explicitly (i.e. not copied from HMCE-VATDEC-ORG).
    needsRetryStatuses.foreach { status =>
      s"not create ES relationship if no relationship currently exists in HMCE-VATDEC-ORG " +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToESStatus = Success" in {
          val record = defaultRecordForMtdVat.copy(syncToETMPStatus = status, syncToESStatus = Some(Success))
          val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
          await(relationshipCopyRepository.create(record))
          val lockService = new FakeLockService
          val relationshipsService = new CheckAndCopyRelationshipsService(
            es,
            ifConnector,
            des,
            mapping,
            ugs,
            aca,
            relationshipCopyRepository,
            new CreateRelationshipsService(
              es,
              relationshipConnector,
              relationshipCopyRepository,
              lockService,
              deleteRecordRepository,
              agentUserService,
              aucdConnector,
              metrics
            ),
            partialAuthRepository,
            invitationsRepository,
            itsaDeauthAndCleanupService,
            auditService,
            metrics
          )

          val auditData = new AuditData()
          val request = FakeRequest()

          oldESRelationshipDoesNotExist()
          auditStub()

          val check = relationshipsService
            .checkForOldRelationshipAndCopy(arn, vatEnrolmentKey)(ec, hc, request, auditData)
          await(check) shouldBe NotFound

          verifyEsRecordNotCreated()
          verifyEtmpRecordNotCreatedForMtdVat()
        }
    }

    "return Upstream5xxResponse, if the mapping service is unavailable" in {
      val lockService = new FakeLockService
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      val relationshipsService = new CheckAndCopyRelationshipsService(
        es,
        ifConnector,
        des,
        mapping,
        ugs,
        aca,
        relationshipCopyRepository,
        new CreateRelationshipsService(
          es,
          relationshipConnector,
          relationshipCopyRepository,
          lockService,
          deleteRecordRepository,
          agentUserService,
          aucdConnector,
          metrics
        ),
        partialAuthRepository,
        invitationsRepository,
        itsaDeauthAndCleanupService,
        auditService,
        metrics
      )
      val auditData = new AuditData()
      val request = FakeRequest()
      mappingServiceUnavailableForMtdVat()
      val check = relationshipsService
        .checkForOldRelationshipAndCopy(arn, vatEnrolmentKey)(ec, hc, request, auditData)

      an[UpstreamErrorResponse] should be thrownBy await(check)

      verifyEsRecordNotCreatedMtdVat()
      verifyEtmpRecordNotCreatedForMtdVat()
    }

    "not create ETMP or ES relationship if RelationshipCopyRecord exists with syncToETMPStatus = Success and syncToESStatus = Success" in {
      val record = defaultRecordForMtdVat.copy(syncToETMPStatus = Some(Success), syncToESStatus = Some(Success))
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      await(relationshipCopyRepository.create(record))
      val lockService = new FakeLockService
      val relationshipsService = new CheckAndCopyRelationshipsService(
        es,
        ifConnector,
        des,
        mapping,
        ugs,
        aca,
        relationshipCopyRepository,
        new CreateRelationshipsService(
          es,
          relationshipConnector,
          relationshipCopyRepository,
          lockService,
          deleteRecordRepository,
          agentUserService,
          aucdConnector,
          metrics
        ),
        partialAuthRepository,
        invitationsRepository,
        itsaDeauthAndCleanupService,
        auditService,
        metrics
      )

      val auditData = new AuditData()
      val request = FakeRequest()

      oldESRelationshipExists()
      relationshipWillBeCreated(vatEnrolmentKey)

      val check = relationshipsService
        .checkForOldRelationshipAndCopy(arn, vatEnrolmentKey)(ec, hc, request, auditData)

      val checkAndCopyResult = await(check)
      checkAndCopyResult shouldBe AlreadyCopiedDidNotCheck
      checkAndCopyResult.grantAccess shouldBe false

      verifyEsRecordNotCreated()
      verifyEtmpRecordNotCreatedForMtdVat()
    }

    "not create ES relationship if RelationshipCopyRecord exists syncToESStatus is In Progress because we can't tell " +
      "whether the ES copy happened successfully but the the status update failed e.g. due to a container kill and in " +
      "that case we would be re-copying a relationship that has been removed in ES. It is more important that we never " +
      "copy to ES twice than that the synch works!" in {
        val record = defaultRecordForMtdVat.copy(syncToETMPStatus = Some(Success), syncToESStatus = Some(InProgress))
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        await(relationshipCopyRepository.create(record))
        val lockService = new FakeLockService
        val relationshipsService = new CheckAndCopyRelationshipsService(
          es,
          ifConnector,
          des,
          mapping,
          ugs,
          aca,
          relationshipCopyRepository,
          new CreateRelationshipsService(
            es,
            relationshipConnector,
            relationshipCopyRepository,
            lockService,
            deleteRecordRepository,
            agentUserService,
            aucdConnector,
            metrics
          ),
          partialAuthRepository,
          invitationsRepository,
          itsaDeauthAndCleanupService,
          auditService,
          metrics
        )

        val auditData = new AuditData()
        val request = FakeRequest()

        oldESRelationshipExists()
        relationshipWillBeCreated(vatEnrolmentKey)

        val check = relationshipsService
          .checkForOldRelationshipAndCopy(arn, vatEnrolmentKey)(ec, hc, request, auditData)

        val checkAndCopyResult = await(check)
        checkAndCopyResult shouldBe AlreadyCopiedDidNotCheck
        checkAndCopyResult.grantAccess shouldBe false

        verifyEsRecordNotCreated()
        verifyEtmpRecordNotCreatedForMtdVat()
      }
  }

  "lookupCesaForOldRelationship" should {
    "return Upstream5xxResponse, if the mapping service is unavailable" in {
      val lockService = new FakeLockService
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      val relationshipsService = new CheckAndCopyRelationshipsService(
        es,
        ifConnector,
        des,
        mapping,
        ugs,
        aca,
        relationshipCopyRepository,
        new CreateRelationshipsService(
          es,
          relationshipConnector,
          relationshipCopyRepository,
          lockService,
          deleteRecordRepository,
          agentUserService,
          aucdConnector,
          metrics
        ),
        partialAuthRepository,
        invitationsRepository,
        itsaDeauthAndCleanupService,
        auditService,
        metrics
      )
      val auditData = new AuditData()
      val request = FakeRequest()

      mappingServiceUnavailable()
      val check = relationshipsService.lookupCesaForOldRelationship(arn, nino)(ec, hc, request, auditData)

      an[UpstreamErrorResponse] should be thrownBy await(check)
      verifyEsRecordNotCreated()
      verifyEtmpRecordNotCreated()
    }
  }

  "lookupESForOldRelationship" should {
    "return Upstream5xxResponse, if the mapping service is unavailable" in {
      val lockService = new FakeLockService
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      val relationshipsService = new CheckAndCopyRelationshipsService(
        es,
        ifConnector,
        des,
        mapping,
        ugs,
        aca,
        relationshipCopyRepository,
        new CreateRelationshipsService(
          es,
          relationshipConnector,
          relationshipCopyRepository,
          lockService,
          deleteRecordRepository,
          agentUserService,
          aucdConnector,
          metrics
        ),
        partialAuthRepository,
        invitationsRepository,
        itsaDeauthAndCleanupService,
        auditService,
        metrics
      )
      val auditData = new AuditData()
      val request = FakeRequest()

      mappingServiceUnavailableForMtdVat()
      val check = relationshipsService.lookupESForOldRelationship(arn, vrn)(ec, hc, request, auditData)

      an[UpstreamErrorResponse] should be thrownBy await(check)
      verifyEsRecordNotCreatedMtdVat()
      verifyEtmpRecordNotCreatedForMtdVat()
    }
  }

  "checkForOldRelationshipAndCopy with feature flags" should {

    when(servicesConfig.getBoolean(eqs("features.copy-relationship.mtd-it"))).thenReturn(false)
    when(servicesConfig.getBoolean(eqs("features.copy-relationship.mtd-vat"))).thenReturn(true)
    when(servicesConfig.getString(any[String])).thenReturn("")
    var appConfig: AppConfig = new AppConfig(configuration, servicesConfig)

    behave like copyHasBeenDisabled(mtdItEnrolmentKey, appConfig)

    when(servicesConfig.getBoolean(eqs("features.copy-relationship.mtd-it"))).thenReturn(true)
    when(servicesConfig.getBoolean(eqs("features.copy-relationship.mtd-vat"))).thenReturn(false)
    when(servicesConfig.getString(any[String])).thenReturn("")
    appConfig = new AppConfig(configuration, servicesConfig)

    behave like copyHasBeenDisabled(vatEnrolmentKey, appConfig)

    def copyHasBeenDisabled(enrolmentKey: EnrolmentKey, appConfig: AppConfig) =
      s"not attempt to copy relationship for ${enrolmentKey.service} " +
        s"and return CopyRelationshipNotEnabled if feature flag is disabled (set to false)" in {
          val relationshipCopyRepository = mock[RelationshipCopyRecordRepository]
          val lockService = mock[RecoveryLockService]
          val relationshipsService =
            new CheckAndCopyRelationshipsService(
              es,
              ifConnector,
              des,
              mapping,
              ugs,
              aca,
              relationshipCopyRepository,
              new CreateRelationshipsService(
                es,
                relationshipConnector,
                relationshipCopyRepository,
                lockService,
                deleteRecordRepository,
                agentUserService,
                aucdConnector,
                metrics
              ),
              partialAuthRepository,
              invitationsRepository,
              itsaDeauthAndCleanupService,
              auditService,
              metrics
            )(appConfig)

          val auditData = new AuditData()
          val request = FakeRequest()

          val check = relationshipsService
            .checkForOldRelationshipAndCopy(arn, enrolmentKey)(ec, hc, request, auditData)

          await(check) shouldBe CopyRelationshipNotEnabled

          verifyNoInteractions(des, mapping, relationshipCopyRepository, lockService, auditService, es)
        }
  }

  private def cesaRelationshipDoesNotExist(): OngoingStubbing[Future[Seq[SaAgentReference]]] = {
    when(ifConnector.getNinoFor(eqs(mtdItId))(eqs(hc), eqs(ec))).thenReturn(Future successful Some(nino))
    when(des.getClientSaAgentSaReferences(eqs(nino))(eqs(hc), eqs(ec))).thenReturn(Future successful Seq())
    when(mapping.getSaAgentReferencesFor(eqs(arn))(eqs(hc))).thenReturn(Future successful Seq())
  }

  private def oldESRelationshipDoesNotExist(): OngoingStubbing[Future[Seq[AgentCode]]] = {
    when(es.getDelegatedGroupIdsForHMCEVATDECORG(eqs(vrn))(eqs(hc)))
      .thenReturn(Future successful Set.empty[String])
    when(mapping.getAgentCodesFor(eqs(arn))(eqs(hc))).thenReturn(Future.successful(Seq.empty))
  }

  private def mappingServiceUnavailable(): OngoingStubbing[Future[Seq[SaAgentReference]]] = {
    when(ifConnector.getNinoFor(eqs(mtdItId))(eqs(hc), eqs(ec))).thenReturn(Future successful Some(nino))
    when(des.getClientSaAgentSaReferences(eqs(nino))(eqs(hc), eqs(ec))).thenReturn(Future successful Seq(saAgentRef))
    when(mapping.getSaAgentReferencesFor(eqs(arn))(eqs(hc)))
      .thenReturn(Future failed UpstreamErrorResponse("Error, no response", 502, 502))
  }

  private def mappingServiceUnavailableForMtdVat(): OngoingStubbing[Future[Seq[AgentCode]]] = {
    when(es.getDelegatedGroupIdsForHMCEVATDECORG(eqs(vrn))(eqs(hc)))
      .thenReturn(Future successful Set(agentGroupId))
    when(ugs.getGroupInfo(eqs(agentGroupId))(eqs(hc)))
      .thenReturn(Future successful Some(GroupInfo(agentGroupId, Some("Agent"), Some(agentCodeForVatDecAgent))))
    when(mapping.getAgentCodesFor(eqs(arn))(eqs(hc)))
      .thenReturn(Future failed UpstreamErrorResponse("Error, no response", 502, 502))
  }

  private def ninoExists(): OngoingStubbing[Future[Option[Nino]]] =
    when(ifConnector.getNinoFor(eqs(mtdItId))(eqs(hc), eqs(ec))).thenReturn(Future successful Some(nino))

  private def partialAuthExistsInAcr(service: String): OngoingStubbing[Future[Option[PartialAuthRelationship]]] =
    when(partialAuthRepository.findActive(eqs(nino), eqs(arn)))
      .thenReturn(
        Future.successful(
          Some(PartialAuthRelationship(Instant.now(), arn.value, service, nino.value, active = true, Instant.now()))
        )
      )

  private def partialAuthDeleted(service: String): OngoingStubbing[Future[Boolean]] =
    when(partialAuthRepository.deleteActivePartialAuth(eqs(service), eqs(nino), eqs(arn)))
      .thenReturn(Future.successful(true))

  private def partialAuthStatusUpdatedToAcceptedInACR(service: String): OngoingStubbing[Future[Boolean]] =
    when(invitationsRepository.updatePartialAuthToAcceptedStatus(eqs(arn), eqs(service), eqs(nino), eqs(mtdItId)))
      .thenReturn(Future.successful(true))

  private def partialAuthStatusUpdatedToAcceptedInACA(service: String): OngoingStubbing[Future[Boolean]] = {
    when(partialAuthRepository.deleteActivePartialAuth(eqs(service), eqs(nino), eqs(arn)))
      .thenReturn(Future.successful(false))
    when(aca.updateStatusToAccepted(eqs(nino), eqs(service))(eqs(hc), eqs(ec)))
      .thenReturn(Future.successful(true))
  }

  private def partialAuthExistsInAca(service: String): OngoingStubbing[Future[List[String]]] = {
    when(partialAuthRepository.findActive(eqs(nino), eqs(arn))).thenReturn(Future.successful(None))
    when(aca.getPartialAuth(eqs(nino), eqs(arn))(eqs(hc))).thenReturn(Future.successful(List(service)))
  }

  private def partialAuthDoesNotExistInAca(): OngoingStubbing[Future[List[String]]] = {
    when(partialAuthRepository.findActive(eqs(nino), eqs(arn))).thenReturn(Future.successful(None))
    when(aca.getPartialAuth(eqs(nino), eqs(arn))(eqs(hc))).thenReturn(Future.successful(List()))
  }

  private def cesaRelationshipExists(): OngoingStubbing[Future[Seq[SaAgentReference]]] = {
    when(ifConnector.getNinoFor(eqs(mtdItId))(eqs(hc), eqs(ec))).thenReturn(Future successful Some(nino))
    when(des.getClientSaAgentSaReferences(eqs(nino))(eqs(hc), eqs(ec))).thenReturn(Future successful Seq(saAgentRef))
    when(mapping.getSaAgentReferencesFor(eqs(arn))(eqs(hc)))
      .thenReturn(Future successful Seq(saAgentRef))
  }

  private def oldESRelationshipExists(): OngoingStubbing[Future[Seq[AgentCode]]] = {
    when(es.getDelegatedGroupIdsForHMCEVATDECORG(eqs(vrn))(eqs(hc)))
      .thenReturn(Future successful Set("test2", "foo", agentGroupId, "ABC-123"))
    when(ugs.getGroupInfo(eqs(agentGroupId))(eqs(hc)))
      .thenReturn(Future successful Some(GroupInfo(agentGroupId, Some("Agent"), Some(agentCodeForVatDecAgent))))
    when(ugs.getGroupInfo(eqs("foo"))(eqs(hc)))
      .thenReturn(Future successful Some(GroupInfo("foo", Some("Agent"), Some(AgentCode("foo")))))
    when(ugs.getGroupInfo(eqs("ABC-123"))(eqs(hc)))
      .thenReturn(Future successful Some(GroupInfo("ABC-123", Some("Agent"), Some(AgentCode("ABC-123")))))
    when(ugs.getGroupInfo(eqs("test2"))(eqs(hc)))
      .thenReturn(Future successful Some(GroupInfo("test2", Some("Agent"), None)))
    when(mapping.getAgentCodesFor(eqs(arn))(eqs(hc)))
      .thenReturn(Future.successful(Seq(agentCodeForVatDecAgent)))
  }

  private def adminUserExistsForArn(): OngoingStubbing[Future[Either[String, AgentUser]]] =
    when(agentUserService.getAgentAdminUserFor(eqs(arn))(any[ExecutionContext], any[HeaderCarrier], any[AuditData]))
      .thenReturn(Future.successful(agentUserForAsAgent))

  private def arnExistsForGroupId(): OngoingStubbing[Future[Option[Arn]]] = {
    when(es.getAgentReferenceNumberFor(eqs("foo"))(eqs(hc))).thenReturn(Future.successful(Some(Arn("fooArn"))))
    when(es.getAgentReferenceNumberFor(eqs("bar"))(eqs(hc))).thenReturn(Future.successful(Some(Arn("barArn"))))
  }

  private def previousRelationshipWillBeRemoved(enrolmentKey: EnrolmentKey): OngoingStubbing[Future[Unit]] = {
    when(es.getDelegatedGroupIdsFor(eqs(enrolmentKey))(any[HeaderCarrier]()))
      .thenReturn(Future.successful(Set("foo")))
    when(es.getAgentReferenceNumberFor(eqs("foo"))(eqs(hc))).thenReturn(Future.successful(Some(arn)))
    when(deleteRecordRepository.create(any[DeleteRecord])).thenReturn(Future.successful(1))
    when(es.deallocateEnrolmentFromAgent(eqs("foo"), eqs(enrolmentKey))(any[HeaderCarrier]()))
      .thenReturn(Future.successful(()))
  }

  private def vrnIsKnownInETMP(vrn: Vrn, isKnown: Boolean): OngoingStubbing[Future[Boolean]] =
    when(des.vrnIsKnownInEtmp(eqs(vrn))(eqs(hc), eqs(ec))).thenReturn(Future successful isKnown)

  private def relationshipWillBeCreated(enrolmentKey: EnrolmentKey): OngoingStubbing[Future[Unit]] = {
    when(relationshipConnector.createAgentRelationship(eqs(enrolmentKey), eqs(arn))(eqs(hc), eqs(ec)))
      .thenReturn(Future successful Some(RegistrationRelationshipResponse("processing date")))
    when(
      es.allocateEnrolmentToAgent(eqs(agentGroupId), eqs(agentUserId), eqs(enrolmentKey), eqs(agentCodeForAsAgent))(
        eqs(hc)
      )
    )
      .thenReturn(Future.successful(()))
    when(
      aucdConnector.cacheRefresh(eqs(arn))(eqs(hc), eqs(ec))
    ).thenReturn(Future successful (()))
  }

  private def metricsStub(): OngoingStubbing[MetricRegistry] =
    when(metrics.defaultRegistry).thenReturn(new MetricRegistry)

  private def auditStub(): OngoingStubbing[Future[Unit]] =
    when(
      auditService
        .sendCheckESAuditEvent(any[HeaderCarrier], any[Request[Any]], any[AuditData], any[ExecutionContext])
    )
      .thenReturn(Future.successful(()))

  private def sendCreateRelationshipAuditEvent(): OngoingStubbing[Future[Unit]] =
    when(
      auditService
        .sendCreateRelationshipAuditEvent(any[HeaderCarrier], any[Request[Any]], any[AuditData], any[ExecutionContext])
    )
      .thenReturn(Future.successful(()))

  private def deleteSameAgentOtherItsaService(): OngoingStubbing[Future[Boolean]] =
    when(
      itsaDeauthAndCleanupService.deleteSameAgentRelationship(
        any[String],
        eqs(arn.value),
        eqs(Some(mtdItId.value)),
        eqs(nino.value),
        any[Instant]
      )(any[HeaderCarrier], any[CurrentUser], any[Request[_]])
    )
      .thenReturn(Future.successful(true))

  def verifyEtmpRecordCreated(): Future[Option[RegistrationRelationshipResponse]] =
    verify(relationshipConnector).createAgentRelationship(eqs(mtdItEnrolmentKey), eqs(arn))(eqs(hc), eqs(ec))

  def verifyEtmpRecordNotCreated(): Future[Option[RegistrationRelationshipResponse]] =
    verify(relationshipConnector, never()).createAgentRelationship(eqs(mtdItEnrolmentKey), eqs(arn))(eqs(hc), eqs(ec))

  def verifyEtmpRecordCreatedForMtdVat(): Future[Option[RegistrationRelationshipResponse]] =
    verify(relationshipConnector).createAgentRelationship(eqs(vatEnrolmentKey), eqs(arn))(eqs(hc), eqs(ec))

  def verifyEtmpRecordNotCreatedForMtdVat(): Future[Option[RegistrationRelationshipResponse]] =
    verify(relationshipConnector, never()).createAgentRelationship(eqs(vatEnrolmentKey), eqs(arn))(eqs(hc), eqs(ec))

  def verifyEsRecordCreated(): Future[Unit] =
    verify(es).allocateEnrolmentToAgent(
      eqs(agentGroupId),
      eqs(agentUserId),
      eqs(mtdItEnrolmentKey),
      eqs(agentCodeForAsAgent)
    )(eqs(hc))

  def verifyEsRecordNotCreated(): Future[Unit] =
    verify(es, never()).allocateEnrolmentToAgent(
      eqs(agentUserId),
      eqs(agentGroupId),
      eqs(mtdItEnrolmentKey),
      eqs(agentCodeForAsAgent)
    )(eqs(hc))

  def verifyEsRecordCreatedForMtdVat(): Future[Unit] =
    verify(es).allocateEnrolmentToAgent(
      eqs(agentGroupId),
      eqs(agentUserId),
      eqs(vatEnrolmentKey),
      eqs(agentCodeForAsAgent)
    )(eqs(hc))

  def verifyEsRecordNotCreatedMtdVat(): Future[Unit] =
    verify(es, never()).allocateEnrolmentToAgent(
      eqs(agentUserId),
      eqs(agentGroupId),
      eqs(vatEnrolmentKey),
      eqs(agentCodeForAsAgent)
    )(eqs(hc))

  def verifyAuditEventSent(): Map[String, Any] = {
    val auditDataCaptor = ArgumentCaptor.forClass(classOf[AuditData])
    verify(auditService)
      .sendCheckCESAAndPartialAuthAuditEvent(
        any[HeaderCarrier],
        any[Request[Any]],
        auditDataCaptor.capture(),
        any[ExecutionContext]()
      )
    val auditData: AuditData = auditDataCaptor.getValue
    val auditDetails = auditData.getDetails
    auditDetails("saAgentRef") shouldBe saAgentRef.value
    auditDetails("CESARelationship") shouldBe true
    auditDetails("nino") shouldBe nino
    auditDetails
  }

  def verifyESAuditEventSent(): Map[String, Any] = {
    val auditDataCaptor = ArgumentCaptor.forClass(classOf[AuditData])
    verify(auditService)
      .sendCheckESAuditEvent(any[HeaderCarrier], any[Request[Any]], auditDataCaptor.capture(), any[ExecutionContext]())
    val auditData: AuditData = auditDataCaptor.getValue
    val auditDetails = auditData.getDetails
    auditDetails("vrn") shouldBe vrn
    auditDetails("ESRelationship") shouldBe true
    auditDetails
  }
}
