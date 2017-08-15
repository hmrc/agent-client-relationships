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
import uk.gov.hmrc.agentclientrelationships.repository.{FakeRelationshipCopyRecordRepository, RelationshipCopyRecord, SyncStatus}
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.{AgentCode, Generator, Nino, SaAgentReference}
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

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

  val gg =  resettingMock[GovernmentGatewayProxyConnector]
  val des = resettingMock[DesConnector]
  val mapping = resettingMock[MappingConnector]
  val auditService = resettingMock[AuditService]


  val hc = HeaderCarrier()

  "checkCesaForOldRelationshipAndCopy" should {
    "create ETMP relationship and return FoundAndCopied if RelationshipCopyRecord exists with syncToETMPStatus = None" in {
      val record = defaultRecord.copy(syncToETMPStatus = None)
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository(record)
      val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, auditService)

      val auditData = new AuditData()
      val request = FakeRequest()

      cesaRelationshipExists()

      val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(hc, request, auditData)

      await(check) shouldBe FoundAndCopied

      verifyEtmpRecordCreated()
      verifyAuditEventSent()
      await(relationshipCopyRepository.findBy(arn, mtdItId)).value.syncToETMPStatus shouldBe Some(SyncStatus.Success)
    }

    "not create ETMP relationship if no relationship currently exists in CESA even if RelationshipCopyRecord exists with syncToETMPStatus = None" in {
      val record = defaultRecord.copy(syncToETMPStatus = None)
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository(record)
      val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, auditService)

      val auditData = new AuditData()
      val request = FakeRequest()

      cesaRelationshipDoesNotExist()

      val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(hc, request, auditData)
      await(check) shouldBe NotFound

      verifyEtmpRecordNotCreated()
    }

    "not create ETMP relationship if RelationshipCopyRecord exists with syncToETMPStatus = Success" in {
      val record = defaultRecord.copy(syncToETMPStatus = Some(SyncStatus.Success))
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository(record)
      val relationshipsService = new RelationshipsService(gg, des, mapping, relationshipCopyRepository, auditService)

      val auditData = new AuditData()
      val request = FakeRequest()

      cesaRelationshipExists()

      val check = relationshipsService.checkCesaForOldRelationshipAndCopy(arn, mtdItId, eventualAgentCode)(hc, request, auditData)

      await(check) shouldBe AlreadyCopiedDidNotCheck

      verifyEtmpRecordNotCreated()
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
    when(des.createAgentRelationship(eqs(mtdItId), eqs(arn))(eqs(hc))).thenReturn(Future successful RegistrationRelationshipResponse("processing date"))
  }

  def verifyEtmpRecordCreated(): Unit = {
    verify(des).createAgentRelationship(eqs(mtdItId), eqs(arn))(eqs(hc))
  }

  def verifyEtmpRecordNotCreated(): Unit = {
    verify(des, never()).createAgentRelationship(eqs(mtdItId), eqs(arn))(eqs(hc))
  }

  def verifyAuditEventSent(): Unit = {
    val auditDataCaptor = ArgumentCaptor.forClass(classOf[AuditData])
    verify(auditService).sendCheckCESAAuditEvent(any[HeaderCarrier], any[Request[Any]], auditDataCaptor.capture())
    val auditData: AuditData = auditDataCaptor.getValue
    // controller sets arn and agentCode, not service, so since this test is unit testing the service we cannot test them here
//    auditData.get("agentCode") shouldBe agentCode
//    auditData.get("arn") shouldBe arn
    auditData.get("saAgentRef") shouldBe saAgentRef.value
    auditData.get("CESARelationship") shouldBe true
    auditData.get("nino") shouldBe nino
  }

  // remove implicit
  override def liftFuture[A](v: A): Future[A] = super.liftFuture(v)
}
