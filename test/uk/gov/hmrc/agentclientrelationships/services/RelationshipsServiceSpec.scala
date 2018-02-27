/*
 * Copyright 2018 HM Revenue & Customs
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

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq => eqs}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import play.api.mvc.Request
import play.api.test.FakeRequest
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipReference.{SaRef, VatRef}
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.repository.{FakeRelationshipCopyRecordRepository, RelationshipCopyRecord, RelationshipCopyRecordRepository}
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.domain._
import uk.gov.hmrc.http.{HeaderCarrier, Upstream5xxResponse}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class RelationshipsServiceSpec extends UnitSpec
  with BeforeAndAfterEach with ResettingMockitoSugar {

  val testDataGenerator = new Generator()
  val arn = Arn("AARN0000002")
  val saAgentRef = SaAgentReference("T1113T")
  val mtdItId = MtdItId("ABCDEF123456789")
  val vrn = Vrn("101747641")
  val agentUserId = "testUserId"
  val agentGroupId = "testGroupId"
  val agentCodeForVatDecAgent = AgentCode("oldAgentCode")
  val agentCodeForAsAgent = AgentCode("ABC1234")
  val eventualAgentUserForAsAgent = Future successful AgentUser(agentUserId,agentGroupId,agentCodeForAsAgent)
  val nino: Nino = testDataGenerator.nextNino
  val defaultRecord = RelationshipCopyRecord(arn.value, mtdItId.value, "MTDITID", Some(Set(SaRef(saAgentRef))), syncToETMPStatus = None, syncToGGStatus = None)
  val defaultRecordForMtdVat = RelationshipCopyRecord(arn.value, vrn.value, "MTDVATID", Some(Set(VatRef(agentCodeForVatDecAgent))),
    syncToETMPStatus = None, syncToGGStatus = None)

  val es = resettingMock[EnrolmentStoreProxyConnector]
  val des = resettingMock[DesConnector]
  val mapping = resettingMock[MappingConnector]
  val ugs = resettingMock[UsersGroupsSearchConnector]
  val auditService = resettingMock[AuditService]

  val needsRetryStatuses = Seq[Option[SyncStatus]](None, Some(InProgress), Some(IncompleteInputParams), Some(Failed))

  val hc = HeaderCarrier()
  val ec = implicitly[ExecutionContext]

  "checkCesaForOldRelationshipAndCopyForMtdIt" should {

    needsRetryStatuses.foreach { status =>
      s"create ETMP relationship and return FoundAndCopied if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToGGStatus = None" in {
        val record = defaultRecord.copy(syncToETMPStatus = status, syncToGGStatus = None)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipExists()
        relationshipWillBeCreated(mtdItId)

        val check = relationshipsService.checkForOldRelationshipAndCopy(arn, mtdItId, eventualAgentUserForAsAgent)(ec, hc, request, auditData)

        await(check) shouldBe FoundAndCopied

        verifyEtmpRecordCreated()
        val auditDetails = verifyAuditEventSent()
        auditDetails("etmpRelationshipCreated") shouldBe true
        await(relationshipCopyRepository.findBy(arn, mtdItId)).value.syncToETMPStatus shouldBe Some(Success)
      }

      s"skip recovery of ETMP relationship but still return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = $status and syncToGGStatus = None " +
        s"and recovery of this relationship is already in progress" in {
        val record = defaultRecord.copy(syncToETMPStatus = status, syncToGGStatus = None)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        await(relationshipCopyRepository.create(record))
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(es, des, mapping, ugs,
          relationshipCopyRepository, lockService, auditService, true, true)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipExists()
        relationshipWillBeCreated(mtdItId)

        val maybeCheck: Option[CheckAndCopyResult] = await(lockService.tryLock(arn, mtdItId) {
          relationshipsService.checkForOldRelationshipAndCopy(arn, mtdItId, eventualAgentUserForAsAgent)(ec, hc, request, auditData)
        })

        maybeCheck.value shouldBe FoundAndCopied

        verifyEtmpRecordNotCreated()
        val auditDetails = verifyAuditEventSent()
        auditDetails.get("etmpRelationshipCreated") shouldBe None
        await(relationshipCopyRepository.findBy(arn, mtdItId)).value.syncToETMPStatus shouldBe status
      }
    }
    // We ignore the RelationshipCopyRecord if there is no relationship in CESA as a failsafe in case we have made a logic error.
    // However we will probably need to change this when we implement recovery for relationships that were created explicitly (i.e. not copied from CESA).
    needsRetryStatuses.foreach { status =>
      s"not create ETMP relationship if no relationship currently exists in CESA " +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToGGStatus = None" in {
        val record = defaultRecord.copy(syncToETMPStatus = status, syncToGGStatus = None)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipDoesNotExist()

        val check = relationshipsService.checkForOldRelationshipAndCopy(arn, mtdItId, eventualAgentUserForAsAgent)(ec, hc, request, auditData)
        await(check) shouldBe NotFound

        verifyEtmpRecordNotCreated()
      }
    }

    needsRetryStatuses.filterNot(s => s.contains(InProgress) || s.contains(InProgress)) foreach { status =>
      s"create ES relationship (only) and return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = Success and syncToGGStatus = $status" in {
        val record = defaultRecord.copy(syncToETMPStatus = Some(Success), syncToGGStatus = status)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)
        await(relationshipCopyRepository.create(record))
        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipExists()
        relationshipWillBeCreated(mtdItId)

        val check = relationshipsService.checkForOldRelationshipAndCopy(arn, mtdItId, eventualAgentUserForAsAgent)(ec, hc, request, auditData)

        await(check) shouldBe FoundAndCopied

        verifyGgRecordCreated()
        verifyEtmpRecordNotCreated()
        val auditDetails = verifyAuditEventSent()
        auditDetails.get("etmpRelationshipCreated") shouldBe None
        auditDetails("enrolmentDelegated") shouldBe true
        await(relationshipCopyRepository.findBy(arn, mtdItId)).value.syncToGGStatus shouldBe Some(Success)
      }


      s"skip recovery of ES relationship but still return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = $status and syncToGGStatus = None " +
        s"and recovery of this relationship is already in progress" in {
        val record = defaultRecord.copy(syncToETMPStatus = Some(Success), syncToGGStatus = status)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)
        await(relationshipCopyRepository.create(record))
        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipExists()
        relationshipWillBeCreated(mtdItId)

        val maybeCheck = await(lockService.tryLock(arn, mtdItId) {
          relationshipsService.checkForOldRelationshipAndCopy(arn, mtdItId, eventualAgentUserForAsAgent)(ec, hc, request, auditData)
        })

        maybeCheck.value shouldBe FoundAndCopied

        verifyEtmpRecordNotCreated()
        verifyGgRecordNotCreated()
        val auditDetails = verifyAuditEventSent()
        auditDetails.get("etmpRelationshipCreated") shouldBe None
        auditDetails.get("enrolmentDelegated") shouldBe None
        await(relationshipCopyRepository.findBy(arn, mtdItId)).value.syncToGGStatus shouldBe status
      }
    }

    needsRetryStatuses.foreach { status =>
      s"not create ES relationship if no relationship currently exists in CESA " +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = Success and syncToGGStatus = $status" in {
        val record = defaultRecord.copy(syncToETMPStatus = Some(Success), syncToGGStatus = status)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipDoesNotExist()

        val check = relationshipsService.checkForOldRelationshipAndCopy(arn, mtdItId, eventualAgentUserForAsAgent)(ec, hc, request, auditData)
        await(check) shouldBe NotFound

        verifyGgRecordNotCreated()
        verifyEtmpRecordNotCreated()
      }
    }

    needsRetryStatuses.foreach { status =>
      s"create ETMP relationship (only) and return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = $status and syncToGGStatus = Success " +
        s"even though we don't expect this to happen because we always create the ETMP record first" in {
        val record = defaultRecord.copy(syncToETMPStatus = status, syncToGGStatus = Some(Success))
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        await(relationshipCopyRepository.create(record))
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipExists()
        relationshipWillBeCreated(mtdItId)

        val check = relationshipsService.checkForOldRelationshipAndCopy(arn, mtdItId, eventualAgentUserForAsAgent)(ec, hc, request, auditData)

        await(check) shouldBe FoundAndCopied

        verifyGgRecordNotCreated()
        verifyEtmpRecordCreated()
        val auditDetails = verifyAuditEventSent()
        auditDetails("etmpRelationshipCreated") shouldBe true
        auditDetails.get("enrolmentDelegated") shouldBe None
        await(relationshipCopyRepository.findBy(arn, mtdItId)).value.syncToETMPStatus shouldBe Some(Success)
      }
    }

    // We ignore the RelationshipCopyRecord if there is no relationship in CESA as a failsafe in case we have made a logic error.
    // However we will probably need to change this when we implement recovery for relationships that were created explicitly (i.e. not copied from CESA).
    needsRetryStatuses.foreach { status =>
      s"not create ES relationship if no relationship currently exists in CESA " +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToGGStatus = Success" in {
        val record = defaultRecord.copy(syncToETMPStatus = status, syncToGGStatus = Some(Success))
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        await(relationshipCopyRepository.create(record))
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipDoesNotExist()

        val check = relationshipsService.checkForOldRelationshipAndCopy(arn, mtdItId, eventualAgentUserForAsAgent)(ec, hc, request, auditData)
        await(check) shouldBe NotFound

        verifyGgRecordNotCreated()
        verifyEtmpRecordNotCreated()
      }
    }

    "return Upstream5xxResponse, if the mapping service is unavailable" in {
      val lockService = new FakeLockService
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)
      val auditData = new AuditData()
      val request = FakeRequest()

      mappingServiceUnavailable()
      val check = relationshipsService.checkForOldRelationshipAndCopy(arn, mtdItId, eventualAgentUserForAsAgent)(ec, hc, request, auditData)

      an[Upstream5xxResponse] should be thrownBy await(check)
      verifyGgRecordNotCreated()
      verifyEtmpRecordNotCreated()
    }

    "not create ETMP or ES relationship if RelationshipCopyRecord exists with syncToETMPStatus = Success and syncToGGStatus = Success" in {
      val record = defaultRecord.copy(syncToETMPStatus = Some(Success), syncToGGStatus = Some(Success))
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      await(relationshipCopyRepository.create(record))
      val lockService = new FakeLockService
      val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)

      val auditData = new AuditData()
      val request = FakeRequest()

      cesaRelationshipExists()
      relationshipWillBeCreated(mtdItId)

      val check = relationshipsService.checkForOldRelationshipAndCopy(arn, mtdItId, eventualAgentUserForAsAgent)(ec, hc, request, auditData)

      val checkAndCopyResult = await(check)
      checkAndCopyResult shouldBe AlreadyCopiedDidNotCheck
      checkAndCopyResult.grantAccess shouldBe false

      verifyGgRecordNotCreated()
      verifyEtmpRecordNotCreated()
    }

    "not createGG relationship if RelationshipCopyRecord exists syncToGGStatus is In Progress because we can't tell " +
      "whether the ES copy happened successfully but the the status update failed e.g. due to a container kill and in " +
      "that case we would be re-copying a relationship that has been removed in ES. It is more important that we never " +
      "copy to ES twice than that the synch works!" in {
      val record = defaultRecord.copy(syncToETMPStatus = Some(Success), syncToGGStatus = Some(InProgress))
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      await(relationshipCopyRepository.create(record))
      val lockService = new FakeLockService
      val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)

      val auditData = new AuditData()
      val request = FakeRequest()

      cesaRelationshipExists()
      relationshipWillBeCreated(mtdItId)

      val check = relationshipsService.checkForOldRelationshipAndCopy(arn, mtdItId, eventualAgentUserForAsAgent)(ec, hc, request, auditData)

      val checkAndCopyResult = await(check)
      checkAndCopyResult shouldBe AlreadyCopiedDidNotCheck
      checkAndCopyResult.grantAccess shouldBe false

      verifyGgRecordNotCreated()
      verifyEtmpRecordNotCreated()
    }
  }

  "checkGGForOldRelationshipAndCopyForMtdVat" should {

    needsRetryStatuses.foreach { status =>
      s"create ETMP relationship and return FoundAndCopied if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToGGStatus = None" in {
        val record = defaultRecordForMtdVat.copy(syncToETMPStatus = status, syncToGGStatus = None)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)

        val auditData = new AuditData()
        val request = FakeRequest()

        oldGGRelationshipExists()
        relationshipWillBeCreated(vrn)

        val check = relationshipsService.checkForOldRelationshipAndCopy(arn, vrn, eventualAgentUserForAsAgent)(ec, hc, request, auditData)

        await(check) shouldBe FoundAndCopied

        verifyEtmpRecordCreatedForMtdVat()
        val auditDetails = verifyGGAuditEventSent()
        auditDetails("etmpRelationshipCreated") shouldBe true
        await(relationshipCopyRepository.findBy(arn, vrn)).value.syncToETMPStatus shouldBe Some(Success)
      }

      s"skip recovery of ETMP relationship but still return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = $status and syncToGGStatus = None " +
        s"and recovery of this relationship is already in progress" in {
        val record = defaultRecordForMtdVat.copy(syncToETMPStatus = status, syncToGGStatus = None)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        await(relationshipCopyRepository.create(record))
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(es, des, mapping, ugs,
          relationshipCopyRepository, lockService, auditService, true, true)

        val auditData = new AuditData()
        val request = FakeRequest()

        oldGGRelationshipExists()
        relationshipWillBeCreated(vrn)

        val maybeCheck: Option[CheckAndCopyResult] = await(lockService.tryLock(arn, vrn) {
          relationshipsService.checkForOldRelationshipAndCopy(arn, vrn, eventualAgentUserForAsAgent)(ec, hc, request, auditData)
        })

        maybeCheck.value shouldBe FoundAndCopied

        verifyEtmpRecordNotCreatedForMtdVat()
        val auditDetails = verifyGGAuditEventSent()
        auditDetails.get("etmpRelationshipCreated") shouldBe None
        await(relationshipCopyRepository.findBy(arn, vrn)).value.syncToETMPStatus shouldBe status
      }
    }
    // We ignore the RelationshipCopyRecord if there is no relationship in ES as a failsafe in case we have made a logic error.
    // However we will probably need to change this when we implement recovery for relationships that were created explicitly (i.e. not copied from ES).
    needsRetryStatuses.foreach { status =>
      s"not create ETMP relationship if no relationship currently exists in HMCE-VATDEC-ORG " +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToGGStatus = None" in {
        val record = defaultRecordForMtdVat.copy(syncToETMPStatus = status, syncToGGStatus = None)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)

        val auditData = new AuditData()
        val request = FakeRequest()

        oldGGRelationshipDoesNotExist()

        val check = relationshipsService.checkForOldRelationshipAndCopy(arn, vrn, eventualAgentUserForAsAgent)(ec, hc, request, auditData)
        await(check) shouldBe NotFound

        verifyEtmpRecordNotCreatedForMtdVat()
      }
    }

    needsRetryStatuses.filterNot(s => s.contains(InProgress) || s.contains(InProgress)) foreach { status =>
      s"create ES relationship (only) and return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = Success and syncToGGStatus = $status" in {
        val record = defaultRecordForMtdVat.copy(syncToETMPStatus = Some(Success), syncToGGStatus = status)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)
        await(relationshipCopyRepository.create(record))
        val auditData = new AuditData()
        val request = FakeRequest()

        oldGGRelationshipExists()
        relationshipWillBeCreated(vrn)

        val check = relationshipsService.checkForOldRelationshipAndCopy(arn, vrn, eventualAgentUserForAsAgent)(ec, hc, request, auditData)

        await(check) shouldBe FoundAndCopied

        verifyGgRecordCreatedForMtdVat()
        verifyEtmpRecordNotCreatedForMtdVat()
        val auditDetails = verifyGGAuditEventSent()
        auditDetails.get("etmpRelationshipCreated") shouldBe None
        auditDetails("enrolmentDelegated") shouldBe true
        await(relationshipCopyRepository.findBy(arn, vrn)).value.syncToGGStatus shouldBe Some(Success)
      }


      s"skip recovery of ES relationship but still return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = $status and syncToGGStatus = None " +
        s"and recovery of this relationship is already in progress" in {
        val record = defaultRecordForMtdVat.copy(syncToETMPStatus = Some(Success), syncToGGStatus = status)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)
        await(relationshipCopyRepository.create(record))
        val auditData = new AuditData()
        val request = FakeRequest()

        oldGGRelationshipExists()
        relationshipWillBeCreated(vrn)

        val maybeCheck = await(lockService.tryLock(arn, vrn) {
          relationshipsService.checkForOldRelationshipAndCopy(arn, vrn, eventualAgentUserForAsAgent)(ec, hc, request, auditData)
        })

        maybeCheck.value shouldBe FoundAndCopied

        verifyEtmpRecordNotCreatedForMtdVat()
        verifyGgRecordNotCreated()
        val auditDetails = verifyGGAuditEventSent()
        auditDetails.get("etmpRelationshipCreated") shouldBe None
        auditDetails.get("enrolmentDelegated") shouldBe None
        await(relationshipCopyRepository.findBy(arn, vrn)).value.syncToGGStatus shouldBe status
      }
    }

    needsRetryStatuses.foreach { status =>
      s"not create ES relationship if no relationship currently exists in HMCE-VATDEC-ORG " +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = Success and syncToGGStatus = $status" in {
        val record = defaultRecordForMtdVat.copy(syncToETMPStatus = Some(Success), syncToGGStatus = status)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)

        val auditData = new AuditData()
        val request = FakeRequest()

        oldGGRelationshipDoesNotExist()

        val check = relationshipsService.checkForOldRelationshipAndCopy(arn, vrn, eventualAgentUserForAsAgent)(ec, hc, request, auditData)
        await(check) shouldBe NotFound

        verifyGgRecordNotCreated()
        verifyEtmpRecordNotCreatedForMtdVat()
      }
    }

    needsRetryStatuses.foreach { status =>
      s"create ETMP relationship (only) and return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = $status and syncToGGStatus = Success " +
        s"even though we don't expect this to happen because we always create the ETMP record first" in {
        val record = defaultRecordForMtdVat.copy(syncToETMPStatus = status, syncToGGStatus = Some(Success))
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        await(relationshipCopyRepository.create(record))
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)

        val auditData = new AuditData()
        val request = FakeRequest()

        oldGGRelationshipExists()
        relationshipWillBeCreated(vrn)

        val check = relationshipsService.checkForOldRelationshipAndCopy(arn, vrn, eventualAgentUserForAsAgent)(ec, hc, request, auditData)

        await(check) shouldBe FoundAndCopied

        verifyGgRecordNotCreatedMtdVat()
        verifyEtmpRecordCreatedForMtdVat()
        val auditDetails = verifyGGAuditEventSent()
        auditDetails("etmpRelationshipCreated") shouldBe true
        auditDetails.get("enrolmentDelegated") shouldBe None
        await(relationshipCopyRepository.findBy(arn, vrn)).value.syncToETMPStatus shouldBe Some(Success)
      }
    }

    // We ignore the RelationshipCopyRecord if there is no relationship in HMCE-VATDEC-ORG as a failsafe in case we have made a logic error.
    // However we will probably need to change this when we implement recovery for relationships that were created explicitly (i.e. not copied from HMCE-VATDEC-ORG).
    needsRetryStatuses.foreach { status =>
      s"not create ES relationship if no relationship currently exists in HMCE-VATDEC-ORG " +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToGGStatus = Success" in {
        val record = defaultRecordForMtdVat.copy(syncToETMPStatus = status, syncToGGStatus = Some(Success))
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        await(relationshipCopyRepository.create(record))
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)

        val auditData = new AuditData()
        val request = FakeRequest()

        oldGGRelationshipDoesNotExist()

        val check = relationshipsService.checkForOldRelationshipAndCopy(arn, vrn, eventualAgentUserForAsAgent)(ec, hc, request, auditData)
        await(check) shouldBe NotFound

        verifyGgRecordNotCreated()
        verifyEtmpRecordNotCreatedForMtdVat()
      }
    }

    "return Upstream5xxResponse, if the mapping service is unavailable" in {
      val lockService = new FakeLockService
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)
      val auditData = new AuditData()
      val request = FakeRequest()
      mappingServiceUnavailableForMtdVat()
      val check = relationshipsService.checkForOldRelationshipAndCopy(arn, vrn, eventualAgentUserForAsAgent)(ec, hc, request, auditData)

      an[Upstream5xxResponse] should be thrownBy await(check)

      verifyGgRecordNotCreatedMtdVat()
      verifyEtmpRecordNotCreatedForMtdVat()
    }

    "not create ETMP or ES relationship if RelationshipCopyRecord exists with syncToETMPStatus = Success and syncToGGStatus = Success" in {
      val record = defaultRecordForMtdVat.copy(syncToETMPStatus = Some(Success), syncToGGStatus = Some(Success))
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      await(relationshipCopyRepository.create(record))
      val lockService = new FakeLockService
      val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)

      val auditData = new AuditData()
      val request = FakeRequest()

      oldGGRelationshipExists()
      relationshipWillBeCreated(vrn)

      val check = relationshipsService.checkForOldRelationshipAndCopy(arn, vrn, eventualAgentUserForAsAgent)(ec, hc, request, auditData)

      val checkAndCopyResult = await(check)
      checkAndCopyResult shouldBe AlreadyCopiedDidNotCheck
      checkAndCopyResult.grantAccess shouldBe false

      verifyGgRecordNotCreated()
      verifyEtmpRecordNotCreatedForMtdVat()
    }

    "not create ES relationship if RelationshipCopyRecord exists syncToGGStatus is In Progress because we can't tell " +
      "whether the ES copy happened successfully but the the status update failed e.g. due to a container kill and in " +
      "that case we would be re-copying a relationship that has been removed in ES. It is more important that we never " +
      "copy to ES twice than that the synch works!" in {
      val record = defaultRecordForMtdVat.copy(syncToETMPStatus = Some(Success), syncToGGStatus = Some(InProgress))
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      await(relationshipCopyRepository.create(record))
      val lockService = new FakeLockService
      val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)

      val auditData = new AuditData()
      val request = FakeRequest()

      oldGGRelationshipExists()
      relationshipWillBeCreated(vrn)

      val check = relationshipsService.checkForOldRelationshipAndCopy(arn, vrn, eventualAgentUserForAsAgent)(ec, hc, request, auditData)

      val checkAndCopyResult = await(check)
      checkAndCopyResult shouldBe AlreadyCopiedDidNotCheck
      checkAndCopyResult.grantAccess shouldBe false

      verifyGgRecordNotCreated()
      verifyEtmpRecordNotCreatedForMtdVat()
    }
  }

  "lookupCesaForOldRelationship" should {
    "return Upstream5xxResponse, if the mapping service is unavailable" in {
      val lockService = new FakeLockService
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)
      val auditData = new AuditData()
      val request = FakeRequest()

      mappingServiceUnavailable()
      val check = relationshipsService.lookupCesaForOldRelationship(arn, nino)(ec, hc, request, auditData)

      an[Upstream5xxResponse] should be thrownBy await(check)
      verifyGgRecordNotCreated()
      verifyEtmpRecordNotCreated()
    }
  }

  "lookupGGForOldRelationship" should {
    "return Upstream5xxResponse, if the mapping service is unavailable" in {
      val lockService = new FakeLockService
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      val relationshipsService = new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService, auditService, true, true)
      val auditData = new AuditData()
      val request = FakeRequest()

      mappingServiceUnavailableForMtdVat()
      val check = relationshipsService.lookupGGForOldRelationship(arn, vrn)(ec, hc, request, auditData)

      an[Upstream5xxResponse] should be thrownBy await(check)
      verifyGgRecordNotCreatedMtdVat()
      verifyEtmpRecordNotCreatedForMtdVat()
    }
  }

  "checkForOldRelationshipAndCopy with feature flags" should {

    behave like copyHasBeenDisabled(mtdItId, false, true)
    behave like copyHasBeenDisabled(vrn, true, false)

    def copyHasBeenDisabled(identifier: TaxIdentifier, copyMtdItRelationshipFlag: Boolean, copyMtdVatRelationshipFlag: Boolean) = {
      s"not attempt to copy relationship for ${identifier.getClass.getSimpleName} " +
        s"and return CopyRelationshipNotAllowed if feature flag is disabled (set to false)" in {
        val relationshipCopyRepository = mock[RelationshipCopyRecordRepository]
        val lockService = mock[RecoveryLockService]
        val relationshipsService =
          new RelationshipsService(es, des, mapping, ugs, relationshipCopyRepository, lockService,
            auditService, copyMtdItRelationshipFlag, copyMtdVatRelationshipFlag)

        val auditData = new AuditData()
        val request = FakeRequest()

        val check = relationshipsService.checkForOldRelationshipAndCopy(arn, identifier, eventualAgentUserForAsAgent)(ec, hc, request, auditData)

        await(check) shouldBe CopyRelationshipNotEnabled

        verifyZeroInteractions(des, mapping, relationshipCopyRepository, lockService, auditService, es)
      }
    }
  }

  private def cesaRelationshipDoesNotExist(): Unit = {
    when(des.getNinoFor(eqs(mtdItId))(eqs(hc), eqs(ec))).thenReturn(Future successful nino)
    when(des.getClientSaAgentSaReferences(eqs(nino))(eqs(hc), eqs(ec))).thenReturn(Future successful Seq())
    when(mapping.getSaAgentReferencesFor(eqs(arn))(eqs(hc))).thenReturn(Future successful Seq())
  }

  private def oldGGRelationshipDoesNotExist(): Unit = {
    when(es.getDelegatedGroupIdsFor(eqs(s"HMCE-VATDEC-ORG~VATRegNo~${vrn.value}"))(eqs(hc), eqs(ec)))
      .thenReturn(Future successful Set.empty[String])
    when(mapping.getAgentCodesFor(eqs(arn))(eqs(hc))).thenReturn(Future.successful(Seq.empty))
  }

  private def mappingServiceUnavailable(): Unit = {
    when(des.getNinoFor(eqs(mtdItId))(eqs(hc), eqs(ec))).thenReturn(Future successful nino)
    when(des.getClientSaAgentSaReferences(eqs(nino))(eqs(hc), eqs(ec))).thenReturn(Future successful Seq(saAgentRef))
    when(mapping.getSaAgentReferencesFor(eqs(arn))(eqs(hc))).thenReturn(Future failed Upstream5xxResponse("Error, no response", 502, 502))
  }

  private def mappingServiceUnavailableForMtdVat(): Unit = {
    when(es.getDelegatedGroupIdsFor(eqs(s"HMCE-VATDEC-ORG~VATRegNo~${vrn.value}"))(eqs(hc), eqs(ec)))
      .thenReturn(Future successful Set(agentGroupId))
    when(ugs.getGroupInfo(eqs(agentGroupId))(eqs(hc), eqs(ec)))
      .thenReturn(Future successful GroupInfo(agentGroupId,Some("Agent"),Some(agentCodeForVatDecAgent)))
    when(mapping.getAgentCodesFor(eqs(arn))(eqs(hc))).thenReturn(Future failed Upstream5xxResponse("Error, no response", 502, 502))
  }

  private def cesaRelationshipExists(): Unit = {
    when(des.getNinoFor(eqs(mtdItId))(eqs(hc), eqs(ec))).thenReturn(Future successful nino)
    when(des.getClientSaAgentSaReferences(eqs(nino))(eqs(hc), eqs(ec))).thenReturn(Future successful Seq(saAgentRef))
    when(mapping.getSaAgentReferencesFor(eqs(arn))(eqs(hc))).thenReturn(Future successful Seq(saAgentRef))
  }

  private def oldGGRelationshipExists(): Unit = {
    when(es.getDelegatedGroupIdsFor(eqs(s"HMCE-VATDEC-ORG~VATRegNo~${vrn.value}"))(eqs(hc), eqs(ec)))
      .thenReturn(Future successful Set("test2", "foo", agentGroupId, "ABC-123"))
    when(ugs.getGroupInfo(eqs(agentGroupId))(eqs(hc), eqs(ec)))
      .thenReturn(Future successful GroupInfo(agentGroupId,Some("Agent"),Some(agentCodeForVatDecAgent)))
    when(ugs.getGroupInfo(eqs("foo"))(eqs(hc), eqs(ec))).thenReturn(Future successful GroupInfo("foo",Some("Agent"),Some(AgentCode("foo"))))
    when(ugs.getGroupInfo(eqs("ABC-123"))(eqs(hc), eqs(ec))).thenReturn(Future successful GroupInfo("ABC-123",Some("Agent"),Some(AgentCode("ABC-123"))))
    when(ugs.getGroupInfo(eqs("test2"))(eqs(hc), eqs(ec))).thenReturn(Future successful GroupInfo("test2",Some("Agent"),None))
    when(mapping.getAgentCodesFor(eqs(arn))(eqs(hc))).thenReturn(Future.successful(Seq(agentCodeForVatDecAgent)))
  }

  private def relationshipWillBeCreated(identifier: TaxIdentifier): Unit = {
    when(des.createAgentRelationship(eqs(identifier), eqs(arn))(eqs(hc), eqs(ec)))
      .thenReturn(Future successful RegistrationRelationshipResponse("processing date"))
    when(es.allocateEnrolmentToAgent(eqs(agentGroupId), eqs(agentUserId), eqs(identifier), eqs(agentCodeForAsAgent))(eqs(hc),eqs(ec)))
      .thenReturn(Future.successful(()))
  }

  def verifyEtmpRecordCreated(): Unit = {
    verify(des).createAgentRelationship(eqs(mtdItId), eqs(arn))(eqs(hc), eqs(ec))
  }

  def verifyEtmpRecordNotCreated(): Unit = {
    verify(des, never()).createAgentRelationship(eqs(mtdItId), eqs(arn))(eqs(hc), eqs(ec))
  }

  def verifyEtmpRecordCreatedForMtdVat(): Unit = {
    verify(des).createAgentRelationship(eqs(vrn), eqs(arn))(eqs(hc), eqs(ec))
  }

  def verifyEtmpRecordNotCreatedForMtdVat(): Unit = {
    verify(des, never()).createAgentRelationship(eqs(vrn), eqs(arn))(eqs(hc), eqs(ec))
  }

  def verifyGgRecordCreated(): Unit = {
    verify(es).allocateEnrolmentToAgent(eqs(agentGroupId), eqs(agentUserId), eqs(mtdItId),eqs(agentCodeForAsAgent))(eqs(hc),eqs(ec))
  }

  def verifyGgRecordNotCreated(): Unit = {
    verify(es, never()).allocateEnrolmentToAgent(eqs(agentUserId), eqs(agentGroupId), eqs(mtdItId),eqs(agentCodeForAsAgent))(eqs(hc),eqs(ec))
  }

  def verifyGgRecordCreatedForMtdVat(): Unit = {
    verify(es).allocateEnrolmentToAgent(eqs(agentGroupId), eqs(agentUserId), eqs(vrn),eqs(agentCodeForAsAgent))(eqs(hc),eqs(ec))
  }

  def verifyGgRecordNotCreatedMtdVat(): Unit = {
    verify(es, never()).allocateEnrolmentToAgent(eqs(agentUserId), eqs(agentGroupId), eqs(vrn),eqs(agentCodeForAsAgent))(eqs(hc),eqs(ec))
  }

  def verifyAuditEventSent(): Map[String, Any] = {
    val auditDataCaptor = ArgumentCaptor.forClass(classOf[AuditData])
    verify(auditService).sendCheckCESAAuditEvent(any[HeaderCarrier], any[Request[Any]], auditDataCaptor.capture())
    val auditData: AuditData = auditDataCaptor.getValue
    // controller sets arn and agentCode, not service, so since this test is unit testing the service we cannot test them here
    //    auditData.get("agentCode") shouldBe agentCode
    //    auditData.get("arn") shouldBe arn
    val auditDetails = auditData.getDetails
    auditDetails("saAgentRef") shouldBe saAgentRef.value
    auditDetails("CESARelationship") shouldBe true
    auditDetails("nino") shouldBe nino
    auditDetails
  }

  def verifyGGAuditEventSent(): Map[String, Any] = {
    val auditDataCaptor = ArgumentCaptor.forClass(classOf[AuditData])
    verify(auditService).sendCheckGGAuditEvent(any[HeaderCarrier], any[Request[Any]], auditDataCaptor.capture())
    val auditData: AuditData = auditDataCaptor.getValue
    // controller sets arn and agentCode, not service, so since this test is unit testing the service we cannot test them here
    //    auditData.get("agentCode") shouldBe agentCode
    //    auditData.get("arn") shouldBe arn
    val auditDetails = auditData.getDetails
    auditDetails("vrn") shouldBe vrn
    auditDetails("GGRelationship") shouldBe true
    auditDetails
  }
  // remove implicit
  override def liftFuture[A](v: A): Future[A] = super.liftFuture(v)
}
