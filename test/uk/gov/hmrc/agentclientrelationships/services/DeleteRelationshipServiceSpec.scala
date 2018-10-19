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

import com.kenshoo.play.metrics.Metrics
import org.joda.time.{DateTime, LocalDate}
import org.mockito.ArgumentMatchers.{any, eq => equalsTo}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar.mock
import play.api.test.FakeRequest
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.repository.{DeleteRecord, FakeDeleteRecordRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.auth.core.retrieve.Credentials
import uk.gov.hmrc.domain._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class DeleteRelationshipServiceSpec extends UnitSpec {

  implicit val hc: HeaderCarrier = HeaderCarrier()
  implicit val testAuditData: AuditData = mock[AuditData]

  val arn = Arn("AARN0000002")
  val mtdItId = MtdItId("ABCDEF123456789")
  val agentUserId = "testUserId"
  val agentGroupId = "testGroupId"
  val agentCodeForAsAgent = AgentCode("ABC1234")
  val agentUser = AgentUser(agentUserId, agentGroupId, agentCodeForAsAgent, arn)

  "deleteRelationship" should {

    "delete relationship and keep no deleteRecord if success" in new TestFixture {
      givenRelationshipBetweenAgentAndClientExists
      givenAgentExists
      givenETMPDeAuthSucceeds
      givenESDeAllocationSucceeds

      implicit val request = FakeRequest()
      implicit val currentUser =
        CurrentUser(credentials = Credentials("GG-00001", "GovernmentGateway"), affinityGroup = None)
      await(underTest.deleteRelationship(arn, mtdItId))

      verifyESDeAllocateHasBeenPerformed
      verifyETMPDeAuthorisationHasBeenPerformed

      await(repo.findBy(arn, mtdItId)) shouldBe None
    }

    "save deleteRecord if ES de-allocation partially failed" in new TestFixture {
      givenRelationshipBetweenAgentAndClientExists
      givenAgentExists
      givenETMPDeAuthSucceeds
      givenESDeAllocationFails

      implicit val request = FakeRequest()
      implicit val currentUser =
        CurrentUser(credentials = Credentials("GG-00001", "GovernmentGateway"), affinityGroup = None)
      an[Exception] shouldBe thrownBy {
        await(underTest.deleteRelationship(arn, mtdItId))
      }

      verifyESDeAllocateHasBeenPerformed
      verifyETMPDeAuthorisationHasBeenPerformed

      await(repo.findBy(arn, mtdItId)) should matchPattern {
        case Some(DeleteRecord(arn.value, _, _, _, Some(Success), Some(Failed), None)) =>
      }
    }

    "save deleteRecord if ETMP de-authorisation partially failed" in new TestFixture {
      givenAgentExists
      givenRelationshipBetweenAgentAndClientExists
      givenETMPDeAuthFails

      implicit val request = FakeRequest()
      implicit val currentUser =
        CurrentUser(credentials = Credentials("GG-00001", "GovernmentGateway"), affinityGroup = None)
      an[Exception] shouldBe thrownBy {
        await(underTest.deleteRelationship(arn, mtdItId))
      }

      verifyESDeAllocateHasNOTBeenPerformed
      verifyETMPDeAuthorisationHasBeenPerformed

      await(repo.findBy(arn, mtdItId)) should matchPattern {
        case Some(DeleteRecord(arn.value, _, _, _, Some(Failed), None, None)) =>
      }
    }

    "resume failed ES de-allocation when matching deleteRecord found and remove record afterwards if recovery succeeds" in new TestFixture {
      val deleteRecord = DeleteRecord(arn.value, mtdItId.value, "MTDITID", DateTime.now, Some(Success), Some(Failed))
      repo.create(deleteRecord)
      givenAgentExists
      givenRelationshipBetweenAgentAndClientExists
      givenESDeAllocationSucceeds

      implicit val request = FakeRequest()
      implicit val currentUser =
        CurrentUser(credentials = Credentials("GG-00001", "GovernmentGateway"), affinityGroup = None)
      await(underTest.deleteRelationship(arn, mtdItId))

      verifyESDeAllocateHasBeenPerformed
      verifyETMPDeAuthorisationHasNOTBeenPerformed

      await(repo.findBy(arn, mtdItId)) shouldBe None
    }
  }

  "resumeRelationshipRemoval" should {

    // HAPPY PATHS :-)

    "Do nothing if ETMP and ES are in successful states" in new TestFixture {
      val deleteRecord = DeleteRecord(arn.value, mtdItId.value, "MTDITID", DateTime.now, Some(Success), Some(Success))
      repo.create(deleteRecord)

      val result =
        await(underTest.resumeRelationshipRemoval(deleteRecord))
      result shouldBe true

      verifyESDeAllocateHasNOTBeenPerformed
      verifyETMPDeAuthorisationHasNOTBeenPerformed
    }

    "Retry ETMP de-authorisation when only ETMP requires action" in new TestFixture {
      val deleteRecord = DeleteRecord(arn.value, mtdItId.value, "MTDITID", DateTime.now, Some(Failed), Some(Success))
      repo.create(deleteRecord)
      givenETMPDeAuthSucceeds

      val result =
        await(underTest.resumeRelationshipRemoval(deleteRecord))
      result shouldBe true

      verifyESDeAllocateHasNOTBeenPerformed
      verifyETMPDeAuthorisationHasBeenPerformed

      val record: Option[DeleteRecord] = await(repo.findBy(arn, mtdItId))
      record.flatMap(_.syncToETMPStatus) shouldBe Some(Success)
      record.flatMap(_.syncToESStatus) shouldBe Some(Success)
    }

    "Do not retry ETMP de-authorisation when ETMP state is InProgress" in new TestFixture {
      val deleteRecord =
        DeleteRecord(arn.value, mtdItId.value, "MTDITID", DateTime.now, Some(InProgress), Some(Success))
      repo.create(deleteRecord)
      givenETMPDeAuthSucceeds

      val result =
        await(underTest.resumeRelationshipRemoval(deleteRecord))
      result shouldBe true

      verifyESDeAllocateHasNOTBeenPerformed
      verifyETMPDeAuthorisationHasNOTBeenPerformed

      val record: Option[DeleteRecord] = await(repo.findBy(arn, mtdItId))
      record.flatMap(_.syncToETMPStatus) shouldBe Some(InProgress)
      record.flatMap(_.syncToESStatus) shouldBe Some(Success)
    }

    "Retry ES de-allocation when only ES requires action" in new TestFixture {
      val deleteRecord = DeleteRecord(arn.value, mtdItId.value, "MTDITID", DateTime.now, Some(Success), Some(Failed))
      repo.create(deleteRecord)
      givenAgentExists
      givenRelationshipBetweenAgentAndClientExists
      givenESDeAllocationSucceeds

      val result =
        await(underTest.resumeRelationshipRemoval(deleteRecord))
      result shouldBe true

      verifyESDeAllocateHasBeenPerformed
      verifyETMPDeAuthorisationHasNOTBeenPerformed

      val record: Option[DeleteRecord] = await(repo.findBy(arn, mtdItId))
      record.flatMap(_.syncToETMPStatus) shouldBe Some(Success)
      record.flatMap(_.syncToESStatus) shouldBe Some(Success)
    }

    "Do not retry ES de-allocation when ES state is InProgress" in new TestFixture {
      val deleteRecord =
        DeleteRecord(arn.value, mtdItId.value, "MTDITID", DateTime.now, Some(Success), Some(InProgress))
      repo.create(deleteRecord)
      givenAgentExists
      givenRelationshipBetweenAgentAndClientExists
      givenESDeAllocationSucceeds

      val result =
        await(underTest.resumeRelationshipRemoval(deleteRecord))
      result shouldBe true

      verifyESDeAllocateHasNOTBeenPerformed
      verifyETMPDeAuthorisationHasNOTBeenPerformed

      val record: Option[DeleteRecord] = await(repo.findBy(arn, mtdItId))
      record.flatMap(_.syncToETMPStatus) shouldBe Some(Success)
      record.flatMap(_.syncToESStatus) shouldBe Some(InProgress)
    }

    "Retry both ETMP and ES de-allocation when both require action" in new TestFixture {
      val deleteRecord = DeleteRecord(arn.value, mtdItId.value, "MTDITID", DateTime.now, Some(Failed), Some(Failed))
      repo.create(deleteRecord)
      givenAgentExists
      givenRelationshipBetweenAgentAndClientExists
      givenETMPDeAuthSucceeds
      givenESDeAllocationSucceeds

      val result =
        await(underTest.resumeRelationshipRemoval(deleteRecord))
      result shouldBe true

      verifyESDeAllocateHasBeenPerformed
      verifyETMPDeAuthorisationHasBeenPerformed

      val record: Option[DeleteRecord] = await(repo.findBy(arn, mtdItId))
      record.flatMap(_.syncToETMPStatus) shouldBe Some(Success)
      record.flatMap(_.syncToESStatus) shouldBe Some(Success)
    }

    // FAILURE SCENARIOS

    "When retry ETMP de-authorisation fails keep status Failed" in new TestFixture {
      val deleteRecord = DeleteRecord(arn.value, mtdItId.value, "MTDITID", DateTime.now, Some(Failed), Some(Success))
      repo.create(deleteRecord)
      givenETMPDeAuthFails

      an[Exception] shouldBe thrownBy {
        await(underTest.resumeRelationshipRemoval(deleteRecord))
      }

      verifyESDeAllocateHasNOTBeenPerformed
      verifyETMPDeAuthorisationHasBeenPerformed

      val record: Option[DeleteRecord] = await(repo.findBy(arn, mtdItId))
      record.flatMap(_.syncToETMPStatus) shouldBe Some(Failed)
      record.flatMap(_.syncToESStatus) shouldBe Some(Success)
    }

    "When retry ES de-allocation fails keep status failed" in new TestFixture {
      val deleteRecord = DeleteRecord(arn.value, mtdItId.value, "MTDITID", DateTime.now, Some(Success), Some(Failed))
      repo.create(deleteRecord)
      givenAgentExists
      givenRelationshipBetweenAgentAndClientExists
      when(es.deallocateEnrolmentFromAgent("group0001", mtdItId, AgentCode("")))
        .thenReturn(Future.failed(new Exception()))

      an[Exception] shouldBe thrownBy {
        await(underTest.resumeRelationshipRemoval(deleteRecord))
      }

      verifyESDeAllocateHasBeenPerformed
      verifyETMPDeAuthorisationHasNOTBeenPerformed

      val record: Option[DeleteRecord] = await(repo.findBy(arn, mtdItId))
      record.flatMap(_.syncToETMPStatus) shouldBe Some(Success)
      record.flatMap(_.syncToESStatus) shouldBe Some(Failed)
    }
  }

  "checkDeleteRecordAndEventuallyResume" should {
    "return true if there is no pending delete record" in new TestFixture {
      val result = await(underTest.checkDeleteRecordAndEventuallyResume(mtdItId, arn))

      result shouldBe true
    }
    "return true if delete record found and resumption succeeded" in new TestFixture {
      val deleteRecord = DeleteRecord(arn.value, mtdItId.value, "MTDITID", DateTime.now, Some(Success), Some(Failed))
      repo.create(deleteRecord)
      givenRelationshipBetweenAgentAndClientExists
      givenAgentExists
      givenESDeAllocationSucceeds

      val result = await(underTest.checkDeleteRecordAndEventuallyResume(mtdItId, arn))

      result shouldBe true
      await(repo.findBy(arn, mtdItId)) shouldBe None
    }
    "return false if delete record found but resumption failed" in new TestFixture {
      val deleteRecord = DeleteRecord(arn.value, mtdItId.value, "MTDITID", DateTime.now, Some(Success), Some(Failed))
      repo.create(deleteRecord)
      givenAgentExists
      givenRelationshipBetweenAgentAndClientExists
      givenESDeAllocationFails

      val result = await(underTest.checkDeleteRecordAndEventuallyResume(mtdItId, arn))

      result shouldBe false
      await(repo.findBy(arn, mtdItId)) should matchPattern {
        case Some(DeleteRecord(arn.value, _, _, _, Some(Success), Some(Failed), Some(_))) =>
      }
    }
  }

  trait TestFixture {

    val es = mock[EnrolmentStoreProxyConnector]
    val des = mock[DesConnector]
    val ugs = mock[UsersGroupsSearchConnector]
    val auditService = mock[AuditService]
    val checkService = mock[CheckRelationshipsService]
    val agentUserService = mock[AgentUserService]
    val metrics = mock[Metrics]

    val repo = new FakeDeleteRecordRepository
    val lockService = new FakeLockService

    val underTest = new DeleteRelationshipsService(
      es,
      des,
      ugs,
      repo,
      lockService,
      checkService,
      agentUserService,
      auditService,
      metrics)

    def givenAgentExists =
      when(
        agentUserService.getAgentUserFor(equalsTo[Arn](arn))(any[ExecutionContext], any[HeaderCarrier], any[AuditData]))
        .thenReturn(Future.successful(agentUser))

    def givenRelationshipBetweenAgentAndClientExists =
      when(
        checkService.checkForRelationship(equalsTo(mtdItId), equalsTo(agentUser))(
          any[ExecutionContext],
          any[HeaderCarrier],
          any[AuditData]))
        .thenReturn(Right(true))

    def givenETMPDeAuthSucceeds =
      when(des.deleteAgentRelationship(mtdItId, arn))
        .thenReturn(Future.successful(RegistrationRelationshipResponse(LocalDate.now.toString)))

    def givenETMPDeAuthFails =
      when(des.deleteAgentRelationship(mtdItId, arn))
        .thenReturn(Future.failed(new Exception))

    def givenESDeAllocationSucceeds =
      when(es.deallocateEnrolmentFromAgent(agentGroupId, mtdItId, agentCodeForAsAgent))
        .thenReturn(Future.successful(()))

    def givenESDeAllocationFails =
      when(es.deallocateEnrolmentFromAgent(agentGroupId, mtdItId, agentCodeForAsAgent))
        .thenReturn(Future.failed(new Exception))

    def verifyESDeAllocateHasBeenPerformed =
      verify(es, times(1)).deallocateEnrolmentFromAgent(any[String], any[TaxIdentifier], any[AgentCode])(
        any[HeaderCarrier],
        any[ExecutionContext])

    def verifyESDeAllocateHasNOTBeenPerformed =
      verify(es, never).deallocateEnrolmentFromAgent(any[String], any[TaxIdentifier], any[AgentCode])(
        any[HeaderCarrier],
        any[ExecutionContext])

    def verifyETMPDeAuthorisationHasBeenPerformed =
      verify(des, times(1))
        .deleteAgentRelationship(any[TaxIdentifier], any[Arn])(any[HeaderCarrier], any[ExecutionContext])

    def verifyETMPDeAuthorisationHasNOTBeenPerformed =
      verify(des, never)
        .deleteAgentRelationship(any[TaxIdentifier], any[Arn])(any[HeaderCarrier], any[ExecutionContext])

  }

}
