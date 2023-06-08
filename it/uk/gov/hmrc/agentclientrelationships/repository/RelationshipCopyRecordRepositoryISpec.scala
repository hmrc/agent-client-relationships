package uk.gov.hmrc.agentclientrelationships.repository

import org.mongodb.scala.model.Filters
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.support.{MongoApp, UnitSpec}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Service, Vrn}

import java.time.temporal.ChronoUnit.MILLIS
import java.time.{Instant, LocalDateTime, ZoneOffset}

class RelationshipCopyRecordRepositoryISpec extends UnitSpec with MongoApp with GuiceOneServerPerSuite {

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "features.recovery-enable" -> false
      )
      .configure(mongoConfiguration)

  override implicit lazy val app: Application = appBuilder.build()

  private lazy val repo = app.injector.instanceOf[MongoRelationshipCopyRecordRepository]

  override def beforeEach(): Unit = {
    super.beforeEach()
    await(repo.ensureIndexes)
    ()
  }

  def now: LocalDateTime = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime.truncatedTo(MILLIS)

  "RelationshipCopyRecordRepository" should {
    "create, find and update and remove a record" in {
      val relationshipCopyRecord = RelationshipCopyRecord(
        "TARN0000001",
        Some(Service.Vat.id),
        "101747696",
        "VRN",
        None,
        now,
        Some(SyncStatus.Failed),
        Some(SyncStatus.Failed))
      val createResult = await(repo.create(relationshipCopyRecord))
      createResult shouldBe 1
      val findResult = await(repo.findBy(Arn("TARN0000001"), Vrn("101747696")))
      findResult shouldBe Some(relationshipCopyRecord)

      await(repo.updateEtmpSyncStatus(Arn("TARN0000001"), Vrn("101747696"), SyncStatus.Success))
      val findResult2 = await(repo.findBy(Arn("TARN0000001"), Vrn("101747696")))
      findResult2 shouldBe Some(relationshipCopyRecord.copy(syncToETMPStatus = Some(SyncStatus.Success)))

      await(repo.updateEsSyncStatus(Arn("TARN0000001"), Vrn("101747696"), SyncStatus.Success))
      val findResult3 = await(repo.findBy(Arn("TARN0000001"), Vrn("101747696")))

      findResult3 shouldBe Some(
        relationshipCopyRecord
          .copy(syncToETMPStatus = Some(SyncStatus.Success), syncToESStatus = Some(SyncStatus.Success)))
      val removeResult = await(repo.remove(Arn("TARN0000001"), Vrn("101747696")))
      removeResult shouldBe 1
    }

    "overwrite existing record when creating new relationship with the same arn and client data" in {
      val relationshipCopyRecord1 = RelationshipCopyRecord(
        "TARN0000001",
        Some(Service.Vat.id),
        "101747696",
        "VRN",
        None,
        now,
        Some(SyncStatus.Failed),
        Some(SyncStatus.Failed))
      val createResult1 = await(repo.create(relationshipCopyRecord1))
      createResult1 shouldBe 1
      val relationshipCopyRecord2 = RelationshipCopyRecord(
        "TARN0000001",
        Some(Service.Vat.id),
        "101747696",
        "VRN",
        None,
        now.plusDays(1),
        None,
        None)
      val createResult2 = await(repo.create(relationshipCopyRecord2))
      createResult2 shouldBe 1

      val result = await(
        repo.collection.find(Filters.and(
          Filters.equal("arn"                  , relationshipCopyRecord1.arn),
          Filters.equal("clientIdentifier"     , relationshipCopyRecord1.clientIdentifier),
          Filters.equal("clientIdentifierType" , relationshipCopyRecord1.clientIdentifierType)
        )).toFuture())
      result.length shouldBe 1
    }
  }

}
