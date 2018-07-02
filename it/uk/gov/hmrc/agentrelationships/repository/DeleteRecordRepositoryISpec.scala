package uk.gov.hmrc.agentrelationships.repository

import org.joda.time.{DateTime, DateTimeZone}
import org.scalatestplus.play.OneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.repository._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Vrn}
import uk.gov.hmrc.agentrelationships.support.MongoApp
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class DeleteRecordRepositoryISpec extends UnitSpec with MongoApp with OneAppPerSuite {

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(mongoConfiguration)

  override implicit lazy val app: Application = appBuilder.build()

  private lazy val repo = app.injector.instanceOf[MongoDeleteRecordRepository]

  override def beforeEach() {
    super.beforeEach()
    await(repo.ensureIndexes)
  }

  "DeleteRecordRepository" should {
    "create, find and update and remove a record" in {
      val deleteRecord = DeleteRecord(
        "TARN0000001",
        "101747696",
        "VRN",
        DateTime.now(DateTimeZone.UTC),
        Some(SyncStatus.Failed),
        Some(SyncStatus.Failed))
      val createResult = await(repo.create(deleteRecord))
      createResult shouldBe 1
      val findResult = await(repo.findBy(Arn("TARN0000001"), Vrn("101747696")))
      findResult shouldBe Some(deleteRecord)
      val updatedEtmpResult = await(repo.updateEtmpSyncStatus(Arn("TARN0000001"), Vrn("101747696"), SyncStatus.Success))
      val findResult2 = await(repo.findBy(Arn("TARN0000001"), Vrn("101747696")))
      findResult2 shouldBe Some(deleteRecord.copy(syncToETMPStatus = Some(SyncStatus.Success)))
      val updatedEsResult = await(repo.updateEsSyncStatus(Arn("TARN0000001"), Vrn("101747696"), SyncStatus.Success))
      val findResult3 = await(repo.findBy(Arn("TARN0000001"), Vrn("101747696")))
      findResult3 shouldBe Some(
        deleteRecord.copy(syncToETMPStatus = Some(SyncStatus.Success), syncToESStatus = Some(SyncStatus.Success)))
      val removeResult = await(repo.remove(Arn("TARN0000001"), Vrn("101747696")))
      removeResult shouldBe 1
    }
  }

}
