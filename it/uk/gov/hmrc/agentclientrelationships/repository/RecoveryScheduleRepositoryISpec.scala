package uk.gov.hmrc.agentclientrelationships.repository

import java.util.UUID

import org.joda.time.DateTime
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.support.MongoApp
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class RecoveryScheduleRepositoryISpec extends UnitSpec with MongoApp with GuiceOneServerPerSuite {

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "features.recovery-enable" -> false
      )
      .configure(mongoConfiguration)

  override implicit lazy val app: Application = appBuilder.build()

  private lazy val repo = app.injector.instanceOf[MongoRecoveryScheduleRepository]

  override def beforeEach() {
    super.beforeEach()
    await(repo.ensureIndexes)
    ()
  }

  val uid: UUID = UUID.randomUUID()
  val newDate: DateTime = DateTime.now()

  "RecoveryRepository" should {
    "read and write" in {
      val recoveryRecord = RecoveryRecord("foo", DateTime.parse("2017-10-31T23:22:50.971Z"))
      val newRecoveryRecord = RecoveryRecord("foo", DateTime.parse("2019-10-31T23:22:50.971Z"))

      await(repo.insert(recoveryRecord))

      await(repo.read) shouldBe recoveryRecord

      await(repo.removeAll())

      await(repo.read)

      await(repo.findAll()).length shouldBe 1

      await(repo.write("foo", DateTime.parse("2019-10-31T23:22:50.971Z")))

      await(repo.findAll()).head shouldBe newRecoveryRecord

      await(repo.findAll()).length shouldBe 1

    }
  }

}
