package uk.gov.hmrc.agentclientrelationships.repository

import org.mongodb.scala.MongoException
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus.{Failed, Success}
import uk.gov.hmrc.agentclientrelationships.support.{MongoApp, UnitSpec}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Service, Vrn}

import java.time.temporal.ChronoUnit.MILLIS
import java.time.{Instant, LocalDateTime, ZoneOffset}

class DeleteRecordRepositoryISpec extends UnitSpec with MongoApp with GuiceOneAppPerSuite {

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "features.recovery-enable" -> false
      )
      .configure(mongoConfiguration)

  override implicit lazy val app: Application = appBuilder.build()

  private lazy val repo = app.injector.instanceOf[MongoDeleteRecordRepository]

  def now: LocalDateTime = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime.truncatedTo(MILLIS)

  override def beforeEach(): Unit = {
    super.beforeEach()
    await(repo.ensureIndexes)
    ()
  }

  private val vatEnrolmentKey = EnrolmentKey(Service.Vat, Vrn("101747696"))

  "DeleteRecordRepository" should {
    "create, find and update and remove a record" in {
      val deleteRecord = DeleteRecord(
        "TARN0000001",
        Some(vatEnrolmentKey),
        syncToETMPStatus = Some(SyncStatus.Failed),
        syncToESStatus = Some(SyncStatus.Failed),
        numberOfAttempts = 3,
        lastRecoveryAttempt = None,
        headerCarrier = None
      )
      val createResult = await(repo.create(deleteRecord))
      createResult shouldBe 1

      val findResult = await(repo.findBy(Arn("TARN0000001"), vatEnrolmentKey))
      findResult shouldBe Some(deleteRecord)

      await(repo.updateEtmpSyncStatus(Arn("TARN0000001"), vatEnrolmentKey, SyncStatus.Success))
      val findResult2 = await(repo.findBy(Arn("TARN0000001"), vatEnrolmentKey))
      findResult2 shouldBe Some(deleteRecord.copy(syncToETMPStatus = Some(SyncStatus.Success)))

      await(repo.updateEsSyncStatus(Arn("TARN0000001"), vatEnrolmentKey, SyncStatus.Success))

      val findResult3 = await(repo.findBy(Arn("TARN0000001"), vatEnrolmentKey))
      findResult3 shouldBe Some(
        deleteRecord.copy(syncToETMPStatus = Some(SyncStatus.Success), syncToESStatus = Some(SyncStatus.Success)))

      val removeResult = await(repo.remove(Arn("TARN0000001"), vatEnrolmentKey))
      removeResult shouldBe 1
    }

    "create a new record when an old record with the same arn already exists" in {
      val deleteRecordOld = DeleteRecord(
        "TARN0000001",
        Some(vatEnrolmentKey),
        syncToETMPStatus = Some(SyncStatus.Failed),
        syncToESStatus = Some(SyncStatus.Failed),
        numberOfAttempts = 3,
        lastRecoveryAttempt = None,
        headerCarrier = None
      )
      val deleteRecordNew = DeleteRecord(
        "TARN0000001",
        Some(EnrolmentKey(Service.Vat, Vrn("101747697"))), // a different VRN
        syncToETMPStatus = Some(SyncStatus.Failed),
        syncToESStatus = Some(SyncStatus.Failed),
        numberOfAttempts = 3,
        lastRecoveryAttempt = None,
        headerCarrier = None
      )
      val createResultOld = await(repo.create(deleteRecordOld))
      createResultOld shouldBe 1
      val createResultNew = await(repo.create(deleteRecordNew))
      createResultNew shouldBe 1
    }

    "fail to create a  new record when an old record with the same arn, clientId and clientIdType already exists" in {
      val deleteRecordOld = DeleteRecord(
        "TARN0000001",
        Some(vatEnrolmentKey),
        syncToETMPStatus = Some(SyncStatus.Failed),
        syncToESStatus = Some(SyncStatus.Failed),
        numberOfAttempts = 3,
        lastRecoveryAttempt = None,
        headerCarrier = None
      )
      val deleteRecordNew = DeleteRecord(
        "TARN0000001",
        Some(vatEnrolmentKey),
        syncToETMPStatus = Some(SyncStatus.Success),
        syncToESStatus = Some(SyncStatus.Success),
        numberOfAttempts = 5,
        lastRecoveryAttempt = None,
        headerCarrier = None
      )
      val createResultOld = await(repo.create(deleteRecordOld))
      createResultOld shouldBe 1
      an[MongoException] shouldBe thrownBy {
        await(repo.create(deleteRecordNew))
      }
    }

    "select not attempted delete record first" in {
      val deleteRecord1 = DeleteRecord(
        "TARN0000001",
        Some(EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001"))),
        syncToETMPStatus = Some(Success),
        syncToESStatus = Some(Failed),
        lastRecoveryAttempt = Some(now.minusMinutes(1))
      )
      val deleteRecord2 = DeleteRecord(
        "TARN0000002",
        Some(EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000002"))),
        syncToETMPStatus = Some(Success),
        syncToESStatus = Some(Failed),
        lastRecoveryAttempt = None)
      val deleteRecord3 = DeleteRecord(
        "TARN0000003",
        Some(EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001"))),
        syncToETMPStatus = Some(Success),
        syncToESStatus = Some(Failed),
        lastRecoveryAttempt = Some(now.minusMinutes(5))
      )

      val createResult1 = await(repo.create(deleteRecord1))
      createResult1 shouldBe 1
      val createResult2 = await(repo.create(deleteRecord2))
      createResult2 shouldBe 1
      val createResult3 = await(repo.create(deleteRecord3))
      createResult3 shouldBe 1

      val result = await(repo.selectNextToRecover())
      result shouldBe Some(deleteRecord2)
    }

    "select the oldest attempted delete record first" in {
      val deleteRecord1 = DeleteRecord(
        "TARN0000001",
        Some(EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001"))),
        syncToETMPStatus = Some(Success),
        syncToESStatus = Some(Failed),
        lastRecoveryAttempt = Some(now.minusMinutes(1))
      )
      val deleteRecord2 = DeleteRecord(
        "TARN0000002",
        Some(EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000002"))),
        syncToETMPStatus = Some(Success),
        syncToESStatus = Some(Failed),
        lastRecoveryAttempt = Some(now.minusMinutes(13))
      )
      val deleteRecord3 = DeleteRecord(
        "TARN0000003",
        Some(EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001"))),
        syncToETMPStatus = Some(Success),
        syncToESStatus = Some(Failed),
        lastRecoveryAttempt = Some(now.minusMinutes(5))
      )

      val createResult1 = await(repo.create(deleteRecord1))
      createResult1 shouldBe 1
      val createResult2 = await(repo.create(deleteRecord2))
      createResult2 shouldBe 1
      val createResult3 = await(repo.create(deleteRecord3))
      createResult3 shouldBe 1

      val result = await(repo.selectNextToRecover())
      result shouldBe Some(deleteRecord2)
    }

  }

}
