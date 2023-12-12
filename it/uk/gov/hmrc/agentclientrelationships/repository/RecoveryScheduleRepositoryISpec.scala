package uk.gov.hmrc.agentclientrelationships.repository

import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.support.{MongoApp, UnitSpec}

import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.UUID

class RecoveryScheduleRepositoryISpec extends UnitSpec with MongoApp with GuiceOneServerPerSuite {

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "features.recovery-enable" -> false
      )
      .configure(mongoConfiguration)

  override implicit lazy val app: Application = appBuilder.build()

  private lazy val repo = app.injector.instanceOf[MongoRecoveryScheduleRepository]

  override def beforeEach(): Unit = {
    super.beforeEach()
    await(repo.ensureIndexes())
    ()
  }

  val uid: UUID = UUID.randomUUID()
  val newDate: LocalDateTime = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime

  "RecoveryRepository" should {
    "read and write" in {
      val recoveryRecord = RecoveryRecord("foo", LocalDateTime.parse("2017-10-31T23:22:50.971"))
      val newRecoveryRecord = RecoveryRecord("foo", LocalDateTime.parse("2019-10-31T23:22:50.971"))

      await(repo.collection.insertOne(recoveryRecord).toFuture())

      await(repo.read) shouldBe recoveryRecord

      await(repo.collection.drop().toFuture())

      await(repo.read)

      await(repo.collection.find().toFuture()).length shouldBe 1

      await(repo.write("foo", LocalDateTime.parse("2019-10-31T23:22:50.971")))

      await(repo.collection.find().toFuture()).head shouldBe newRecoveryRecord

      await(repo.collection.find().toFuture()).length shouldBe 1

    }
  }

}
