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
import org.scalatest.BeforeAndAfterEach
import play.api.mvc.Request
import play.api.test.FakeRequest
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.connectors.{DesConnector, GovernmentGatewayProxyConnector, MappingConnector, RegistrationRelationshipResponse}
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.repository.{FakeRelationshipCopyRecordRepository, RelationshipCopyRecord}
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.{AgentCode, Generator, Nino, SaAgentReference}
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
  val agentCode = AgentCode("ABC1234")
  val eventualAgentCode = Future successful agentCode
  val nino: Nino = testDataGenerator.nextNino
  val defaultRecord = RelationshipCopyRecord(arn.value, mtdItId.value, "MTDITID", Some(Set(saAgentRef)), syncToETMPStatus = None, syncToGGStatus = None)

  val gg = resettingMock[GovernmentGatewayProxyConnector]
  val des = resettingMock[DesConnector]
  val mapping = resettingMock[MappingConnector]
  val auditService = resettingMock[AuditService]

  val needsRetryStatuses = Seq[Option[SyncStatus]](None, Some(InProgress), Some(IncompleteInputParams), Some(Failed))

  val hc = HeaderCarrier()
  val ec = implicitly[ExecutionContext]

  "checkCesaForOldRelationshipAndCopy" should {

    needsRetryStatuses.foreach { status =>
      s"create ETMP relationship and return FoundAndCopied if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToGGStatus = None" in {
        val record = defaultRecord.copy(syncToETMPStatus = status, syncToGGStatus = None)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, lockService, auditService)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipExists()
        relationshipWillBeCreated()

        val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(ec, hc, request, auditData)

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
        val relationshipsService = new RelationshipsService(gg, des, mapping,
          relationshipCopyRepository, lockService, auditService)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipExists()
        relationshipWillBeCreated()

        val maybeCheck: Option[CesaCheckAndCopyResult] = await(lockService.tryLock(arn, mtdItId) {
          relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(ec, hc, request, auditData)
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
        val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, lockService, auditService)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipDoesNotExist()

        val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(ec, hc, request, auditData)
        await(check) shouldBe NotFound

        verifyEtmpRecordNotCreated()
      }
    }

    needsRetryStatuses.filterNot(s => s.contains(InProgress) || s.contains(InProgress)) foreach { status =>
      s"create GG relationship (only) and return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = Success and syncToGGStatus = $status" in {
        val record = defaultRecord.copy(syncToETMPStatus = Some(Success), syncToGGStatus = status)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, lockService, auditService)
        await(relationshipCopyRepository.create(record))
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
        await(relationshipCopyRepository.findBy(arn, mtdItId)).value.syncToGGStatus shouldBe Some(Success)
      }


      s"skip recovery of GG relationship but still return FoundAndCopied if RelationshipCopyRecord exists " +
        s"with syncToETMPStatus = $status and syncToGGStatus = None " +
        s"and recovery of this relationship is already in progress" in {
        val record = defaultRecord.copy(syncToETMPStatus = Some(Success), syncToGGStatus = status)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, lockService, auditService)
        await(relationshipCopyRepository.create(record))
        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipExists()
        relationshipWillBeCreated()

        val maybeCheck = await(lockService.tryLock(arn, mtdItId) {
          relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(ec, hc, request, auditData)
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
      s"not create GG relationship if no relationship currently exists in CESA " +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = Success and syncToGGStatus = $status" in {
        val record = defaultRecord.copy(syncToETMPStatus = Some(Success), syncToGGStatus = status)
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, lockService, auditService)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipDoesNotExist()

        val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(ec, hc, request, auditData)
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
        val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, lockService, auditService)

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
        await(relationshipCopyRepository.findBy(arn, mtdItId)).value.syncToETMPStatus shouldBe Some(Success)
      }
    }

    // We ignore the RelationshipCopyRecord if there is no relationship in CESA as a failsafe in case we have made a logic error.
    // However we will probably need to change this when we implement recovery for relationships that were created explicitly (i.e. not copied from CESA).
    needsRetryStatuses.foreach { status =>
      s"not create GG relationship if no relationship currently exists in CESA " +
        s"even if RelationshipCopyRecord exists with syncToETMPStatus = $status and syncToGGStatus = Success" in {
        val record = defaultRecord.copy(syncToETMPStatus = status, syncToGGStatus = Some(Success))
        val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
        await(relationshipCopyRepository.create(record))
        val lockService = new FakeLockService
        val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, lockService, auditService)

        val auditData = new AuditData()
        val request = FakeRequest()

        cesaRelationshipDoesNotExist()

        val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(ec, hc, request, auditData)
        await(check) shouldBe NotFound

        verifyGgRecordNotCreated()
        verifyEtmpRecordNotCreated()
      }
    }

    "return Upstream5xxResponse, if the mapping service is unavailable" in {
      val lockService = new FakeLockService
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, lockService, auditService)
      val auditData = new AuditData()
      val request = FakeRequest()

      mappingServiceUnavailable()
      val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(ec, hc, request, auditData)

      an[Upstream5xxResponse] should be thrownBy await(check)
      verifyGgRecordNotCreated()
      verifyEtmpRecordNotCreated()
    }

    "not create ETMP or GG relationship if RelationshipCopyRecord exists with syncToETMPStatus = Success and syncToGGStatus = Success" in {
      val record = defaultRecord.copy(syncToETMPStatus = Some(Success), syncToGGStatus = Some(Success))
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      await(relationshipCopyRepository.create(record))
      val lockService = new FakeLockService
      val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, lockService, auditService)

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
    }

    "not createGG relationship if RelationshipCopyRecord exists syncToGGStatus is In Progress because we can't tell " +
      "whether the GG copy happened successfully but the the status update failed e.g. due to a container kill and in " +
      "that case we would be re-copying a relationship that has been removed in GG. It is more important that we never " +
      "copy to GG twice than that the synch works!" in {
      val record = defaultRecord.copy(syncToETMPStatus = Some(Success), syncToGGStatus = Some(InProgress))
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      await(relationshipCopyRepository.create(record))
      val lockService = new FakeLockService
      val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, lockService, auditService)

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
    }
  }

  "checkCesaForrOldRelationship" should {
    "return Upstream5xxResponse, if the mapping service is unavailable" in {
      val lockService = new FakeLockService
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository
      val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, lockService, auditService)
      val auditData = new AuditData()
      val request = FakeRequest()

      mappingServiceUnavailable()
      val check = relationshipsService.lookupCesaForOldRelationship(arn, nino)(ec, hc, request, auditData)

      an[Upstream5xxResponse] should be thrownBy await(check)
      verifyGgRecordNotCreated()
      verifyEtmpRecordNotCreated()
    }
  }

  private def cesaRelationshipDoesNotExist(): Unit = {
    when(des.getNinoFor(eqs(mtdItId))(eqs(hc), eqs(ec))).thenReturn(Future successful nino)
    when(des.getClientSaAgentSaReferences(eqs(nino))(eqs(hc), eqs(ec))).thenReturn(Future successful Seq())
    when(mapping.getSaAgentReferencesFor(eqs(arn))(eqs(hc))).thenReturn(Future successful Seq())
  }

  private def mappingServiceUnavailable(): Unit = {
    when(des.getNinoFor(eqs(mtdItId))(eqs(hc), eqs(ec))).thenReturn(Future successful nino)
    when(des.getClientSaAgentSaReferences(eqs(nino))(eqs(hc), eqs(ec))).thenReturn(Future successful Seq(saAgentRef))
    when(mapping.getSaAgentReferencesFor(eqs(arn))(eqs(hc))).thenReturn(Future failed Upstream5xxResponse("Error, no response", 502, 502))
  }

  private def cesaRelationshipExists(): Unit = {
    when(des.getNinoFor(eqs(mtdItId))(eqs(hc), eqs(ec))).thenReturn(Future successful nino)
    when(des.getClientSaAgentSaReferences(eqs(nino))(eqs(hc), eqs(ec))).thenReturn(Future successful Seq(saAgentRef))
    when(mapping.getSaAgentReferencesFor(eqs(arn))(eqs(hc))).thenReturn(Future successful Seq(saAgentRef))
  }

  private def relationshipWillBeCreated(): Unit = {
    when(des.createAgentRelationship(eqs(mtdItId), eqs(arn))(eqs(hc), eqs(ec))).thenReturn(Future successful RegistrationRelationshipResponse("processing date"))
    when(gg.allocateAgent(eqs(agentCode), eqs(mtdItId))(eqs(hc))).thenReturn(Future successful true)
  }

  def verifyEtmpRecordCreated(): Unit = {
    verify(des).createAgentRelationship(eqs(mtdItId), eqs(arn))(eqs(hc), eqs(ec))
  }

  def verifyEtmpRecordNotCreated(): Unit = {
    verify(des, never()).createAgentRelationship(eqs(mtdItId), eqs(arn))(eqs(hc), eqs(ec))
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
  // remove implicit
  override def liftFuture[A](v: A): Future[A] = super.liftFuture(v)
}
