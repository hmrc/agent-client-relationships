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

import org.mongodb.scala.bson.BsonArray
import org.mongodb.scala.bson.BsonNull
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.Filters
import org.mongodb.scala.model.Updates
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.AnyContentAsEmpty
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.MtdItId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Vrn
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipReference.SaRef
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus.Failed
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus.Success
import uk.gov.hmrc.agentclientrelationships.support.MongoApp
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.domain.SaAgentReference

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit.MILLIS

class RelationshipCopyRecordRepositoryISpec
extends UnitSpec
with MongoApp
with GuiceOneServerPerSuite {

  implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()

  protected def appBuilder: GuiceApplicationBuilder = new GuiceApplicationBuilder()
    .configure("features.recovery-enable" -> false)
    .configure(mongoConfiguration)

  override implicit lazy val app: Application = appBuilder.build()

  private lazy val repo = app.injector.instanceOf[RelationshipCopyRecordRepository]

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

  "The temporary cleanup code" should {

    val modernRecord1 = RelationshipCopyRecord(
      arn = "TARN0000001",
      enrolmentKey = Some(EnrolmentKey("HMRC-MTD-IT~MTDITID~ABCDEF0000000001")),
      clientIdentifier = None,
      clientIdentifierType = None,
      references = Some(Set(SaRef(SaAgentReference("foo"))))
    )

    val modernRecord2 = modernRecord1.copy(enrolmentKey = Some(EnrolmentKey("HMRC-MTD-IT~MTDITID~ABCDEF0000000002")))

    val deprecatedRecord1 = RelationshipCopyRecord(
      arn = "TARN0000001",
      enrolmentKey = None,
      clientIdentifier = Some("ABCDEF0000000003"),
      clientIdentifierType = Some("MTDITID"),
      references = Some(Set(SaRef(SaAgentReference("foo"))))
    )

    val deprecatedRecord2 = deprecatedRecord1.copy(clientIdentifier = Some("ABCDEF0000000004"))

    "count the number of records in the deprecated format (use of clientIdentifier and clientIdentifierType)" in {
      repo.collection.insertMany(Seq(
        modernRecord1,
        modernRecord2,
        deprecatedRecord1,
        deprecatedRecord2
      )).toFuture().futureValue

      repo.countDeprecatedRecords().futureValue shouldBe 2
    }

    "convert the records that are in the deprecated format to the new format (use of enrolmentKey)" in {
      repo.collection.insertMany(Seq(
        modernRecord1,
        modernRecord2,
        deprecatedRecord1,
        deprecatedRecord2
      )).toFuture().futureValue

      repo.convertDeprecatedRecords()

      eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
        repo.countDeprecatedRecords().futureValue shouldBe 0
        repo.collection.countDocuments().toFuture().futureValue shouldBe 4
      }

      val expectedRecords = Seq(
        modernRecord1,
        modernRecord2,
        deprecatedRecord1.copy(
          enrolmentKey = Some(EnrolmentKey("HMRC-MTD-IT~MTDITID~ABCDEF0000000003")),
          clientIdentifier = None,
          clientIdentifierType = None
        ),
        deprecatedRecord2.copy(
          enrolmentKey = Some(EnrolmentKey("HMRC-MTD-IT~MTDITID~ABCDEF0000000004")),
          clientIdentifier = None,
          clientIdentifierType = None
        )
      )

      val records = repo.collection.find().toFuture().futureValue

      records shouldBe expectedRecords
    }

    "convert deprecated records where the references has some null/invalid values, then delete any with no references" in {
      repo.collection.insertMany(Seq(
        modernRecord1,
        modernRecord2,
        deprecatedRecord1,
        deprecatedRecord1.copy(clientIdentifier = Some("ABCDEF0000000005")),
        deprecatedRecord2
      )).toFuture().futureValue

      repo.collection.updateOne(
        Filters.eq("clientIdentifier", "ABCDEF0000000003"),
        Updates.set("references", BsonArray(Document("unrecognisedField" -> "f")))
      ).toFuture().futureValue

      repo.collection.updateOne(
        Filters.eq("clientIdentifier", "ABCDEF0000000005"),
        Updates.set("references", BsonArray(Document("saAgentReference" -> BsonNull.apply())))
      ).toFuture().futureValue

      repo.convertDeprecatedRecords()

      eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
        repo.countDeprecatedRecords().futureValue shouldBe 0
        repo.collection.countDocuments().toFuture().futureValue shouldBe 3
      }

      val expectedRecords = Seq(
        modernRecord1,
        modernRecord2,
        deprecatedRecord2.copy(
          enrolmentKey = Some(EnrolmentKey("HMRC-MTD-IT~MTDITID~ABCDEF0000000004")),
          clientIdentifier = None,
          clientIdentifierType = None
        )
      )

      val records = repo.collection.find().toFuture().futureValue

      records shouldBe expectedRecords
    }
  }

}
