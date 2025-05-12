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

import org.mongodb.scala.model.Filters
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus.Failed
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus.Success
import uk.gov.hmrc.agentclientrelationships.support.MongoApp
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentmtdidentifiers.model.MtdItId
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.agentmtdidentifiers.model.Vrn

import java.time.temporal.ChronoUnit.MILLIS
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class RelationshipCopyRecordRepositoryISpec
extends UnitSpec
with MongoApp
with GuiceOneServerPerSuite {

  protected def appBuilder: GuiceApplicationBuilder = new GuiceApplicationBuilder()
    .configure("features.recovery-enable" -> false)
    .configure(mongoConfiguration)

  override implicit lazy val app: Application = appBuilder.build()

  private lazy val repo = app.injector.instanceOf[MongoRelationshipCopyRecordRepository]

  override def beforeEach(): Unit = {
    super.beforeEach()
    await(repo.ensureIndexes())
    ()
  }

  def now: LocalDateTime = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime.truncatedTo(MILLIS)

  private val vatEnrolmentKey = EnrolmentKey(Service.Vat, Vrn("101747696"))

  "RelationshipCopyRecordRepository" should {
    "create, find and update and remove a record" in {
      val relationshipCopyRecord = RelationshipCopyRecord(
        "TARN0000001",
        Some(vatEnrolmentKey),
        syncToETMPStatus = Some(SyncStatus.Failed),
        syncToESStatus = Some(SyncStatus.Failed)
      )
      val createResult = await(repo.create(relationshipCopyRecord))
      createResult shouldBe 1
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
        Some(vatEnrolmentKey),
        syncToETMPStatus = Some(SyncStatus.Failed),
        syncToESStatus = Some(SyncStatus.Failed)
      )
      val createResult1 = await(repo.create(relationshipCopyRecord1))
      createResult1 shouldBe 1
      val relationshipCopyRecord2 = RelationshipCopyRecord(
        "TARN0000001",
        Some(vatEnrolmentKey),
        dateTime = now.plusDays(1),
        syncToETMPStatus = None,
        syncToESStatus = None
      )
      val createResult2 = await(repo.create(relationshipCopyRecord2))
      createResult2 shouldBe 1

      val result = await(
        repo.collection
          .find(
            Filters.and(
              Filters.equal("arn", relationshipCopyRecord1.arn),
              Filters.equal("enrolmentKey", relationshipCopyRecord1.enrolmentKey.get.tag)
            )
          )
          .toFuture()
      )
      result.length shouldBe 1
    }
  }

  "Legacy RelationshipCopyRecords (i.e. with id and idType instead of enrolment key)" should {

    val copyRecord1 = RelationshipCopyRecord(
      "TARN0000001",
      enrolmentKey = None,
      clientIdentifier = Some("ABCDEF0000000001"),
      clientIdentifierType = Some("MTDITID"),
      syncToETMPStatus = Some(Failed),
      syncToESStatus = Some(Failed)
    )
    val copyRecord2 = RelationshipCopyRecord(
      "TARN0000001",
      enrolmentKey = None,
      clientIdentifier = Some("123456789"),
      clientIdentifierType = Some("VRN"),
      syncToETMPStatus = Some(Failed),
      syncToESStatus = Some(Failed)
    )
    val copyRecord3 = RelationshipCopyRecord(
      "TARN0000002",
      enrolmentKey = None,
      clientIdentifier = Some("ABCDEF0000000001"),
      clientIdentifierType = Some("MTDITID"),
      syncToETMPStatus = Some(Failed),
      syncToESStatus = Some(Failed)
    )

    val mtdItEnrolmentKey = EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001"))
    val vatEnrolmentKey = EnrolmentKey(Service.Vat, Vrn("123456789"))

    "be correctly found and retrieved" in {
      await(repo.collection.insertOne(copyRecord1).toFuture())
      await(repo.collection.insertOne(copyRecord2).toFuture())
      await(repo.collection.insertOne(copyRecord3).toFuture())
      await(repo.findBy(Arn("TARN0000001"), mtdItEnrolmentKey)) shouldBe Some(copyRecord1)
      await(repo.findBy(Arn("TARN0000001"), vatEnrolmentKey)) shouldBe Some(copyRecord2)
      await(repo.findBy(Arn("TARN0000002"), mtdItEnrolmentKey)) shouldBe Some(copyRecord3)
      await(repo.findBy(Arn("TARN0000002"), vatEnrolmentKey)) shouldBe None // does not exist
    }

    "be correctly updated" in {
      await(repo.collection.insertOne(copyRecord1).toFuture())
      await(repo.collection.insertOne(copyRecord2).toFuture())
      await(repo.collection.insertOne(copyRecord3).toFuture())
      await(
        repo.updateEsSyncStatus(
          Arn("TARN0000001"),
          mtdItEnrolmentKey,
          Success
        )
      )
      await(
        repo.updateEtmpSyncStatus(
          Arn("TARN0000001"),
          vatEnrolmentKey,
          Success
        )
      )
      await(
        repo.updateEsSyncStatus(
          Arn("TARN0000002"),
          vatEnrolmentKey,
          Success
        )
      ) // This one should do nothing as the record does not exist
      await(repo.findBy(Arn("TARN0000001"), mtdItEnrolmentKey)) shouldBe Some(
        copyRecord1.copy(syncToESStatus = Some(Success))
      )
      await(repo.findBy(Arn("TARN0000001"), vatEnrolmentKey)) shouldBe Some(
        copyRecord2.copy(syncToETMPStatus = Some(Success))
      )
      await(repo.findBy(Arn("TARN0000002"), mtdItEnrolmentKey)) shouldBe Some(copyRecord3)
    }

  }

}
