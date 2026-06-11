/*
 * Copyright 2024 HM Revenue & Customs
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

import org.apache.pekko.Done
import org.mongodb.scala.model.Filters
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.controllers.BaseISpec
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit.MILLIS

class RelationshipCopyRecordRepositoryISpec
extends BaseISpec {

  private lazy val repo = app.injector.instanceOf[RelationshipCopyRecordRepository]

  override def beforeEach(): Unit = {
    super.beforeEach()
    await(repo.ensureIndexes())
    ()
  }

  def now: LocalDateTime = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime.truncatedTo(MILLIS)

  "RelationshipCopyRecordRepository" should {
    "create, find and update and remove a record" in {
      val relationshipCopyRecord = RelationshipCopyRecord(
        "TARN0000001",
        vatEnrolmentKey,
        syncToETMPStatus = Some(SyncStatus.Failed),
        syncToESStatus = Some(SyncStatus.Failed)
      )
      val createResult = await(repo.create(relationshipCopyRecord))
      createResult shouldBe Done
      val findResult = await(repo.findBy(Arn("TARN0000001"), vatEnrolmentKey))
      findResult shouldBe Some(relationshipCopyRecord)

      await(
        repo.updateEtmpSyncStatus(
          Arn("TARN0000001"),
          vatEnrolmentKey,
          SyncStatus.Success
        )
      )
      val findResult2 = await(repo.findBy(Arn("TARN0000001"), vatEnrolmentKey))
      findResult2 shouldBe Some(relationshipCopyRecord.copy(syncToETMPStatus = Some(SyncStatus.Success)))

      await(
        repo.updateEsSyncStatus(
          Arn("TARN0000001"),
          vatEnrolmentKey,
          SyncStatus.Success
        )
      )
      val findResult3 = await(repo.findBy(Arn("TARN0000001"), vatEnrolmentKey))

      findResult3 shouldBe Some(
        relationshipCopyRecord
          .copy(syncToETMPStatus = Some(SyncStatus.Success), syncToESStatus = Some(SyncStatus.Success))
      )
      val removeResult = await(repo.remove(Arn("TARN0000001"), vatEnrolmentKey))
      removeResult shouldBe 1
    }

    "overwrite existing record when creating new relationship with the same arn and client data" in {
      val relationshipCopyRecord1 = RelationshipCopyRecord(
        "TARN0000001",
        vatEnrolmentKey,
        syncToETMPStatus = Some(SyncStatus.Failed),
        syncToESStatus = Some(SyncStatus.Failed)
      )
      val createResult1 = await(repo.create(relationshipCopyRecord1))
      createResult1 shouldBe Done
      val relationshipCopyRecord2 = RelationshipCopyRecord(
        "TARN0000001",
        vatEnrolmentKey,
        dateTime = now.plusDays(1),
        syncToETMPStatus = None,
        syncToESStatus = None
      )
      val createResult2 = await(repo.create(relationshipCopyRecord2))
      createResult2 shouldBe Done

      val result = await(
        repo.collection
          .find(
            Filters.and(
              Filters.equal("arn", relationshipCopyRecord1.arn),
              Filters.equal("enrolmentKey", relationshipCopyRecord1.enrolmentKey.tag)
            )
          )
          .toFuture()
      )
      result.length shouldBe 1
    }
  }

}
