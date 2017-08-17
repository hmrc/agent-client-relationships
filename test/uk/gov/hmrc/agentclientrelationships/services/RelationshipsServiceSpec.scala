/*
 * Copyright 2017 HM Revenue & Customs
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
import play.api.mvc.Request
import play.api.test.FakeRequest
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.connectors.{DesConnector, GovernmentGatewayProxyConnector, MappingConnector, RegistrationRelationshipResponse}
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus.SyncStatus
import uk.gov.hmrc.agentclientrelationships.repository.{FakeRelationshipCopyRecordRepository, RelationshipCopyRecord, SyncStatus}
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.{AgentCode, Generator, Nino, SaAgentReference}
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class RelationshipsServiceSpec extends UnitSpec
  with ResettingMockitoSugar {

  val testDataGenerator = new Generator()
  val arn = Arn("AARN0000002")
  val saAgentRef = SaAgentReference("T1113T")
  val mtdItId = MtdItId("ABCDEF123456789")
  val agentCode = AgentCode("ABC1234")
  val eventualAgentCode = Future successful agentCode
  val nino: Nino = testDataGenerator.nextNino
  val defaultRecord = RelationshipCopyRecord(arn.value, mtdItId.value, "MTDITID", Some(Set(saAgentRef)), syncToETMPStatus = None, syncToGGStatus = None)

  val gg = resettingMock[GovernmentGatewayProxyConnector]
  val des = resettingMock[DesConnector]
  val mapping = resettingMock[MappingConnector]
  val auditService = resettingMock[AuditService]

  val noLockHeldLockService = new FakeLockService(Set.empty)
  val lockHeldLockService = new FakeLockService(Set((arn, mtdItId)))

  val needsRetryStatuses = Seq[Option[SyncStatus]](None, Some(SyncStatus.InProgress), Some(SyncStatus.IncompleteInputParams), Some(SyncStatus.Failed))

  val hc = HeaderCarrier()
  val ec = implicitly[ExecutionContext]

  "checkCesaForOldRelationshipAndCopy" should {

    needsRetryStatuses.foreach { status =>
      s"create ETMP relationship and return FoundAndCopied if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToGGStatus = None" in {
        val record = defaultRecord.copy(syncToETMPStatus = status, syncToGGStatus = None)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository(record)
        val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, noLockHeldLockService, auditService)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipExists()
        relationshipWillBeCreated()

        val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(ec, hc, request, auditData)

        await(check) shouldBe FoundAndCopied

        verifyEtmpRecordCreated()
        val auditDetails = verifyAuditEventSent()
        auditDetails("etmpRelationshipCreated") shouldBe true
        await(relationshipCopyRepository.findBy(arn, mtdItId)).value.syncToETMPStatus shouldBe Some(SyncStatus.Success)
        verifyRecordNotRecreated(relationshipCopyRepository, record)
      }

      s"skip recovery of ETMP relationship but still return FoundAndCopied if RelationshipCopyRecord exists " +
      s"with syncToETMPStatus = $status and syncToGGStatus = None " +
      s"and recovery of this relationship is already in progress" in {
        val record = defaultRecord.copy(syncToETMPStatus = status, syncToGGStatus = None)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository(record)
        val relationshipsService = new RelationshipsService(gg, des, mapping,
          relationshipCopyRepository, lockHeldLockService, auditService)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipExists()
        relationshipWillBeCreated()

        val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(ec, hc, request, auditData)

        await(check) shouldBe FoundAndCopied

        verifyEtmpRecordNotCreated()
        val auditDetails = verifyAuditEventSent()
        auditDetails.get("etmpRelationshipCreated") shouldBe None
        await(relationshipCopyRepository.findBy(arn, mtdItId)).value.syncToETMPStatus shouldBe status
        verifyRecordNotRecreated(relationshipCopyRepository, record)

      }
    }

    // TODO do we want this behaviour?
    // TODO if so should we update syncToETMPStatus to Success to avoid continually redoing recovery?
    needsRetryStatuses.foreach { status =>
      s"not create ETMP relationship if no relationship currently exists in CESA " +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToGGStatus = None" in {
        val record = defaultRecord.copy(syncToETMPStatus = status, syncToGGStatus = None)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository(record)
        val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, noLockHeldLockService, auditService)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipDoesNotExist()

        val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(ec, hc, request, auditData)
        await(check) shouldBe NotFound

        verifyEtmpRecordNotCreated()
        verifyRecordNotRecreated(relationshipCopyRepository, record)
      }
    }

    needsRetryStatuses.foreach { status =>
      s"create GG relationship (only) and return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = Success and syncToGGStatus = $status" in {
        val record = defaultRecord.copy(syncToETMPStatus = Some(SyncStatus.Success), syncToGGStatus = status)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository(record)
        val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, noLockHeldLockService, auditService)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipExists()
        relationshipWillBeCreated()

        val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(ec, hc, request, auditData)

        await(check) shouldBe FoundAndCopied

        verifyGgRecordCreated()
        verifyEtmpRecordNotCreated()
        val auditDetails = verifyAuditEventSent()
        auditDetails.get("etmpRelationshipCreated") shouldBe None
        auditDetails("enrolmentDelegated") shouldBe true
        await(relationshipCopyRepository.findBy(arn, mtdItId)).value.syncToGGStatus shouldBe Some(SyncStatus.Success)
        verifyRecordNotRecreated(relationshipCopyRepository, record)
      }

      s"skip recovery of GG relationship but still return FoundAndCopied if RelationshipCopyRecord exists " +
      s"with syncToETMPStatus = $status and syncToGGStatus = None " +
      s"and recovery of this relationship is already in progress" in {
        val record = defaultRecord.copy(syncToETMPStatus = Some(SyncStatus.Success), syncToGGStatus = status)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository(record)
        val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, lockHeldLockService, auditService)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipExists()
        relationshipWillBeCreated()

        val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(ec, hc, request, auditData)

        await(check) shouldBe FoundAndCopied

        verifyEtmpRecordNotCreated()
        verifyGgRecordNotCreated()
        val auditDetails = verifyAuditEventSent()
        auditDetails.get("etmpRelationshipCreated") shouldBe None
        auditDetails.get("enrolmentDelegated") shouldBe None
        await(relationshipCopyRepository.findBy(arn, mtdItId)).value.syncToGGStatus shouldBe status
        verifyRecordNotRecreated(relationshipCopyRepository, record)
      }
    }

    needsRetryStatuses.foreach { status =>
      s"not create GG relationship if no relationship currently exists in CESA " +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = Success and syncToGGStatus = $status" in {
        val record = defaultRecord.copy(syncToETMPStatus = Some(SyncStatus.Success), syncToGGStatus = status)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository(record)
        val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, noLockHeldLockService, auditService)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipDoesNotExist()

        val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(ec, hc, request, auditData)
        await(check) shouldBe NotFound

        verifyGgRecordNotCreated()
        verifyEtmpRecordNotCreated()
        verifyRecordNotRecreated(relationshipCopyRepository, record)
      }
    }

    needsRetryStatuses.foreach { status =>
      s"create ETMP relationship (only) and return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = $status and syncToGGStatus = Success " +
        s"even though we don't expect this to happen because we always create the ETMP record first" in {
        val record = defaultRecord.copy(syncToETMPStatus = status, syncToGGStatus = Some(SyncStatus.Success))
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository(record)
        val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, noLockHeldLockService, auditService)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipExists()
        relationshipWillBeCreated()

        val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(ec, hc, request, auditData)

        await(check) shouldBe FoundAndCopied

        verifyGgRecordNotCreated()
        verifyEtmpRecordCreated()
        val auditDetails = verifyAuditEventSent()
        auditDetails("etmpRelationshipCreated") shouldBe true
        auditDetails.get("enrolmentDelegated") shouldBe None
        await(relationshipCopyRepository.findBy(arn, mtdItId)).value.syncToETMPStatus shouldBe Some(SyncStatus.Success)
        verifyRecordNotRecreated(relationshipCopyRepository, record)
      }
    }

    // TODO do we want this behaviour?
    // TODO if so should we update syncToGGStatus to Success to avoid continually redoing recovery?
    needsRetryStatuses.foreach { status =>
      s"not create GG relationship if no relationship currently exists in CESA " +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToGGStatus = Success" in {
        val record = defaultRecord.copy(syncToETMPStatus = status, syncToGGStatus = Some(SyncStatus.Success))
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository(record)
        val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, noLockHeldLockService, auditService)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipDoesNotExist()

        val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(ec, hc, request, auditData)
        await(check) shouldBe NotFound

        verifyGgRecordNotCreated()
        verifyEtmpRecordNotCreated()
        verifyRecordNotRecreated(relationshipCopyRepository, record)
      }
    }

    "not create ETMP or GG relationship if RelationshipCopyRecord exists with syncToETMPStatus = Success and syncToGGStatus = Success" in {
      val record = defaultRecord.copy(syncToETMPStatus = Some(SyncStatus.Success), syncToGGStatus = Some(SyncStatus.Success))
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository(record)
      val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, noLockHeldLockService, auditService)

      val auditData = new AuditData()
      val request = FakeRequest()

      cesaRelationshipExists()
      relationshipWillBeCreated()

      val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(ec, hc, request, auditData)

      val checkAndCopyResult = await(check)
      checkAndCopyResult shouldBe AlreadyCopiedDidNotCheck
      checkAndCopyResult.grantAccess shouldBe false

      verifyGgRecordNotCreated()
      verifyEtmpRecordNotCreated()
      verifyRecordNotRecreated(relationshipCopyRepository, record)
    }
  }

  private def cesaRelationshipDoesNotExist(): Unit = {
    when(des.getNinoFor(eqs(mtdItId))(eqs(hc))).thenReturn(Future successful nino)
    when(des.getClientSaAgentSaReferences(eqs(nino))(eqs(hc))).thenReturn(Future successful Seq())
    when(mapping.getSaAgentReferencesFor(eqs(arn))(eqs(hc))).thenReturn(Future successful Seq())
  }

  private def cesaRelationshipExists(): Unit = {
    when(des.getNinoFor(eqs(mtdItId))(eqs(hc))).thenReturn(Future successful nino)
    when(des.getClientSaAgentSaReferences(eqs(nino))(eqs(hc))).thenReturn(Future successful Seq(saAgentRef))
    when(mapping.getSaAgentReferencesFor(eqs(arn))(eqs(hc))).thenReturn(Future successful Seq(saAgentRef))
  }

  private def relationshipWillBeCreated(): Unit = {
    when(des.createAgentRelationship(eqs(mtdItId), eqs(arn))(eqs(hc))).thenReturn(Future successful RegistrationRelationshipResponse("processing date"))
    when(gg.allocateAgent(eqs(agentCode), eqs(mtdItId))(eqs(hc))).thenReturn(Future successful true)
  }

  def verifyEtmpRecordCreated(): Unit = {
    verify(des).createAgentRelationship(eqs(mtdItId), eqs(arn))(eqs(hc))
  }

  def verifyEtmpRecordNotCreated(): Unit = {
    verify(des, never()).createAgentRelationship(eqs(mtdItId), eqs(arn))(eqs(hc))
  }

  def verifyGgRecordCreated(): Unit = {
    verify(gg).allocateAgent(eqs(agentCode), eqs(mtdItId))(eqs(hc))
  }

  def verifyGgRecordNotCreated(): Unit = {
    verify(gg, never()).allocateAgent(eqs(agentCode), eqs(mtdItId))(eqs(hc))
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

  def verifyRecordNotRecreated(repository: FakeRelationshipCopyRecordRepository, record: RelationshipCopyRecord): Unit = {
    repository.recordCreated shouldBe false
  }


  // remove implicit
  override def liftFuture[A](v: A): Future[A] = super.liftFuture(v)
}
