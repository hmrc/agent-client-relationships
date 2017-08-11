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

package uk.gov.hmrc.agentclientrelationships.repository

import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.SaAgentReference
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FakeRelationshipCopyRecordRepositorySpec extends UnitSpec {
  val arn = Arn("AARN0000002")
  val otherArn = Arn("TARN0000001")
  val mtdItId = MtdItId("ABCDEF123456789")
  val otherMtdItId = MtdItId("BBBBBB222222222")
  val relationshipCopyRecord = RelationshipCopyRecord(
    arn = arn.value,
    clientIdentifier = mtdItId.value,
    clientIdentifierType = "MTDITID",
    references = Some(Set((SaAgentReference("T1113T")))),
    syncToETMPStatus = Some(SyncStatus.InProgress), syncToGGStatus = None)

  "findBy" should {
    "return the RelationshipCopyRecord passed on creation" in {
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository(relationshipCopyRecord)

      await(relationshipCopyRepository.findBy(arn, mtdItId)) shouldBe Some(relationshipCopyRecord)
      await(relationshipCopyRepository.findBy(otherArn, mtdItId)) shouldBe None
      await(relationshipCopyRepository.findBy(arn, otherMtdItId)) shouldBe None
    }
  }

  "updateEtmpSyncStatus" should {
    "update the record" in {
      val relationshipCopyRepository = new FakeRelationshipCopyRecordRepository(relationshipCopyRecord)
      await(relationshipCopyRepository.findBy(arn, mtdItId)).value.syncToETMPStatus shouldBe Some(SyncStatus.InProgress)

      await(relationshipCopyRepository.updateEtmpSyncStatus(arn, mtdItId, SyncStatus.Success))
      await(relationshipCopyRepository.findBy(arn, mtdItId)).value.syncToETMPStatus shouldBe Some(SyncStatus.Success)
    }
  }

  // remove implicit
  override def liftFuture[A](v: A): Future[A] = super.liftFuture(v)
}
