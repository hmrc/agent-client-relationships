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

import org.mongodb.scala.MongoException
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus.Failed
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus.Success
import uk.gov.hmrc.agentclientrelationships.support.MongoApp
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.MtdItId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Vrn

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit.MILLIS

class DeleteRecordRepositoryISpec
extends UnitSpec
with MongoApp
with GuiceOneAppPerSuite
with IntegrationPatience {

  implicit val request = FakeRequest()

  protected def appBuilder: GuiceApplicationBuilder = new GuiceApplicationBuilder()
    .configure("features.recovery-enable" -> false)
    .configure(mongoConfiguration)

  override implicit lazy val app: Application = appBuilder.build()

  private lazy val repo = app.injector.instanceOf[DeleteRecordRepository]

  def now: LocalDateTime = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime.truncatedTo(MILLIS)

  override def beforeEach(): Unit = {
    super.beforeEach()
    await(repo.ensureIndexes())
    ()
  }

  private val vatEnrolmentKey = EnrolmentKey(Service.Vat, Vrn("101747696"))

  "DeleteRecordRepository" should {
    "create, find and update and remove a record" in {
      val deleteRecord = DeleteRecord(
        "TARN0000001",
        vatEnrolmentKey,
        syncToETMPStatus = Some(SyncStatus.Failed),
        syncToESStatus = Some(SyncStatus.Failed),
        numberOfAttempts = 3,
        lastRecoveryAttempt = None,
        headerCarrier = None
      )
      await(repo.create(deleteRecord))

      val findResult = await(repo.findBy(Arn("TARN0000001"), vatEnrolmentKey))
      findResult shouldBe Some(deleteRecord)

      await(
        repo.updateEtmpSyncStatus(
          Arn("TARN0000001"),
          vatEnrolmentKey,
          SyncStatus.Success
        )
      )
      val findResult2 = await(repo.findBy(Arn("TARN0000001"), vatEnrolmentKey))
      findResult2 shouldBe Some(deleteRecord.copy(syncToETMPStatus = Some(SyncStatus.Success)))

      await(
        repo.updateEsSyncStatus(
          Arn("TARN0000001"),
          vatEnrolmentKey,
          SyncStatus.Success
        )
      )

      val findResult3 = await(repo.findBy(Arn("TARN0000001"), vatEnrolmentKey))
      findResult3 shouldBe Some(
        deleteRecord.copy(syncToETMPStatus = Some(SyncStatus.Success), syncToESStatus = Some(SyncStatus.Success))
      )

      val removeResult = await(repo.remove(Arn("TARN0000001"), vatEnrolmentKey))
      removeResult shouldBe 1
    }

    "create a new record when an old record with the same arn already exists" in {
      val deleteRecordOld = DeleteRecord(
        "TARN0000001",
        vatEnrolmentKey,
        syncToETMPStatus = Some(SyncStatus.Failed),
        syncToESStatus = Some(SyncStatus.Failed),
        numberOfAttempts = 3,
        lastRecoveryAttempt = None,
        headerCarrier = None
      )
      val deleteRecordNew = DeleteRecord(
        "TARN0000001",
        EnrolmentKey(Service.Vat, Vrn("101747697")), // a different VRN
        syncToETMPStatus = Some(SyncStatus.Failed),
        syncToESStatus = Some(SyncStatus.Failed),
        numberOfAttempts = 3,
        lastRecoveryAttempt = None,
        headerCarrier = None
      )
      await(repo.create(deleteRecordOld))
      await(repo.create(deleteRecordNew))
    }

    "fail to create a  new record when an old record with the same arn, clientId and clientIdType already exists" in {
      val deleteRecordOld = DeleteRecord(
        "TARN0000001",
        vatEnrolmentKey,
        syncToETMPStatus = Some(SyncStatus.Failed),
        syncToESStatus = Some(SyncStatus.Failed),
        numberOfAttempts = 3,
        lastRecoveryAttempt = None,
        headerCarrier = None
      )
      val deleteRecordNew = DeleteRecord(
        "TARN0000001",
        vatEnrolmentKey,
        syncToETMPStatus = Some(SyncStatus.Success),
        syncToESStatus = Some(SyncStatus.Success),
        numberOfAttempts = 5,
        lastRecoveryAttempt = None,
        headerCarrier = None
      )
      await(repo.create(deleteRecordOld))
      an[MongoException] shouldBe thrownBy {
        await(repo.create(deleteRecordNew))
      }
    }

    "select not attempted delete record first" in {
      val deleteRecord1 = DeleteRecord(
        "TARN0000001",
        EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001")),
        syncToETMPStatus = Some(Success),
        syncToESStatus = Some(Failed),
        lastRecoveryAttempt = Some(now.minusMinutes(1)),
        dateTime = now.minusSeconds(60)
      )
      val deleteRecord2 = DeleteRecord(
        "TARN0000002",
        EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000002")),
        syncToETMPStatus = Some(Success),
        syncToESStatus = Some(Failed),
        lastRecoveryAttempt = None,
        dateTime = now.minusSeconds(60)
      )
      val deleteRecord3 = DeleteRecord(
        "TARN0000003",
        EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001")),
        syncToETMPStatus = Some(Success),
        syncToESStatus = Some(Failed),
        lastRecoveryAttempt = Some(now.minusMinutes(5)),
        dateTime = now.minusSeconds(60)
      )

      await(repo.create(deleteRecord1))
      await(repo.create(deleteRecord2))
      await(repo.create(deleteRecord3))

      val result = await(repo.selectNextToRecover())
      result shouldBe Some(deleteRecord2)
    }

    "select the oldest attempted delete record first" in {
      val deleteRecord1 = DeleteRecord(
        "TARN0000001",
        EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001")),
        syncToETMPStatus = Some(Success),
        syncToESStatus = Some(Failed),
        lastRecoveryAttempt = Some(now.minusMinutes(1)),
        dateTime = now.minusSeconds(60)
      )
      val deleteRecord2 = DeleteRecord(
        "TARN0000002",
        EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000002")),
        syncToETMPStatus = Some(Success),
        syncToESStatus = Some(Failed),
        lastRecoveryAttempt = Some(now.minusMinutes(13)),
        dateTime = now.minusSeconds(60)
      )
      val deleteRecord3 = DeleteRecord(
        "TARN0000003",
        EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001")),
        syncToETMPStatus = Some(Success),
        syncToESStatus = Some(Failed),
        lastRecoveryAttempt = Some(now.minusMinutes(5)),
        dateTime = now.minusSeconds(60)
      )

      await(repo.create(deleteRecord1))
      await(repo.create(deleteRecord2))
      await(repo.create(deleteRecord3))

      val result = await(repo.selectNextToRecover())
      result shouldBe Some(deleteRecord2)
    }

    "not select delete records created less than a minute ago" in {
      val deleteRecord1 = DeleteRecord(
        "TARN0000001",
        EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001")),
        syncToETMPStatus = Some(Success),
        syncToESStatus = Some(Failed),
        lastRecoveryAttempt = None
      )

      await(repo.create(deleteRecord1))

      val result = await(repo.selectNextToRecover())
      result shouldBe None
    }
  }

}
