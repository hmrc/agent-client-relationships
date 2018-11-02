package uk.gov.hmrc.agentrelationships.repository

import org.joda.time.DateTime
import org.scalatestplus.play.OneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.repository.{MongoRecoveryScheduleRepository, RecoveryRecord}
import uk.gov.hmrc.agentrelationships.support.MongoApp
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class RecoveryScheduleRepositoryISpec extends UnitSpec with MongoApp with OneAppPerSuite {

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(mongoConfiguration)

  override implicit lazy val app: Application = appBuilder.build()

  private lazy val repo = app.injector.instanceOf[MongoRecoveryScheduleRepository]

  override def beforeEach() {
    super.beforeEach()
    await(repo.ensureIndexes)
  }

  val newDate = DateTime.now().toString()

  "RecoveryRepository" should {
    "create, find and update" in {
      val recoveryRecord = RecoveryRecord("foo", "2017-10-31T23:22:50.971Z")

      val newRecoveryRecord = RecoveryRecord("boo", newDate)

      await(repo.create(recoveryRecord)) shouldBe 1

      await(repo.findBy("foo")) shouldBe Some(recoveryRecord)

      await(repo.update("foo", "boo", newDate))

      await(repo.findBy("foo")) shouldBe None

      await(repo.findBy("boo")) shouldBe Some(newRecoveryRecord)

    }
  }

}
