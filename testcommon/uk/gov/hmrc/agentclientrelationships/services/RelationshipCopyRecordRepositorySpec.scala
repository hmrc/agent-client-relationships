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

import java.util.UUID

import org.scalatest.{BeforeAndAfter, BeforeAndAfterEach}
import reactivemongo.core.errors.DatabaseException
import uk.gov.hmrc.agentclientrelationships.repository.{RelationshipCopyRecord, RelationshipCopyRecordRepository, SyncStatus}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.SaAgentReference
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

trait RelationshipCopyRecordRepositorySpec extends UnitSpec {
  val repo: RelationshipCopyRecordRepository
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

  "RelationshipCopyRecordRepository" should {

    "findBy should return the correct record " in {
      await(repo.create(relationshipCopyRecord))
      val resultFindBy = await(repo.findBy(arn, mtdItId))

      resultFindBy.get.arn shouldBe arn.value
      resultFindBy.get.clientIdentifier shouldBe mtdItId.value
      resultFindBy shouldBe scala.Some(relationshipCopyRecord)
      resultFindBy.get.clientIdentifierType shouldBe "MTDITID"
    }
    "findBy should return None if the record is not in the db" in {
      await(repo.findBy(otherArn, mtdItId)) shouldBe None
      await(repo.findBy(arn, otherMtdItId)) shouldBe empty
    }

    "create should  return the  number 1 if the record is created in the db" in {
      val createResult: Int = await(repo.create(relationshipCopyRecord))
      createResult shouldBe 1
      val resultFindBy = await(repo.findBy(arn, mtdItId)).get
      resultFindBy.arn shouldBe arn.value
      resultFindBy.clientIdentifier shouldBe mtdItId.value
      resultFindBy.clientIdentifierType shouldBe "MTDITID"
    }

    "create should throw an exception if record already exists" in {
      await(repo.create(relationshipCopyRecord))
      intercept[DatabaseException] {
        await(repo.create(relationshipCopyRecord))
      }.getMessage.contains("duplicate key error collection") shouldBe true
    }

    "remove should remove the record" in {
      await(repo.create(relationshipCopyRecord))
      await(repo.create(relationshipCopyRecord.copy(clientIdentifier = otherMtdItId.value)))

      val resultFindBy1 = await(repo.findBy(arn, mtdItId)).get
      resultFindBy1 shouldBe relationshipCopyRecord

      val resultFindBy2 = await(repo.findBy(arn, otherMtdItId)).get
      resultFindBy2 shouldBe relationshipCopyRecord.copy(clientIdentifier = otherMtdItId.value)

      val result = await(repo.remove(arn, mtdItId))
      result shouldBe 1

      val result1After = await(repo.findBy(arn, mtdItId))
      result1After shouldBe None

      val result2After = await(repo.findBy(arn, otherMtdItId)).get
      result2After shouldBe relationshipCopyRecord.copy(clientIdentifier = otherMtdItId.value)
    }
    "updateEtmpSyncStatus" should {
      "update the record" in {
        await(repo.create(relationshipCopyRecord))
        await(repo.findBy(arn, mtdItId)).get.syncToETMPStatus shouldBe Some(SyncStatus.InProgress)

        await(repo.updateEtmpSyncStatus(arn, mtdItId, SyncStatus.Success))
        await(repo.findBy(arn, mtdItId)).get.syncToETMPStatus shouldBe Some(SyncStatus.Success)
        await(repo.remove(arn, mtdItId))
      }
    }

    "updateGgSyncStatus" should {
      "update the record" in {
        await(repo.create(relationshipCopyRecord))
        await(repo.findBy(arn, mtdItId)).get.syncToGGStatus shouldBe None

        await(repo.updateGgSyncStatus(arn, mtdItId, SyncStatus.Success))
        await(repo.findBy(arn, mtdItId)).get.syncToGGStatus shouldBe Some(SyncStatus.Success)
        await(repo.remove(arn, mtdItId))
      }
    }
  }
}
