package uk.gov.hmrc.agentrelationships.support

import org.joda.time.DateTime
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.play.OneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.repository.{DeleteRecord, MongoDeleteRecordRepository, MongoRecoveryScheduleRepository, SyncStatus}
import uk.gov.hmrc.agentclientrelationships.support.RecoveryScheduler
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.agentrelationships.stubs._
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class RecoverySchedulerISpec
    extends UnitSpec
    with MongoApp
    with OneServerPerSuite
    with WireMockSupport
    with RelationshipStubs
    with DesStubs
    with DesStubsGet
    with MappingStubs
    with DataStreamStub
    with AuthStub
    with MockitoSugar {

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
        "microservice.services.tax-enrolments.port"        -> wireMockPort,
        "microservice.services.users-groups-search.port"   -> wireMockPort,
        "microservice.services.des.port"                   -> wireMockPort,
        "microservice.services.auth.port"                  -> wireMockPort,
        "microservice.services.agent-mapping.port"         -> wireMockPort,
        "auditing.consumer.baseUri.host"                   -> wireMockHost,
        "auditing.consumer.baseUri.port"                   -> wireMockPort,
        "features.copy-relationship.mtd-it"                -> true,
        "features.copy-relationship.mtd-vat"               -> true,
        "features.recovery-enable"                         -> true,
        "recovery-interval"                                -> 1
      )
      .configure(mongoConfiguration)

  override implicit lazy val app: Application = appBuilder.build()

  private lazy val recoveryRepo = app.injector.instanceOf[MongoRecoveryScheduleRepository]
  private lazy val deleteRepo = app.injector.instanceOf[MongoDeleteRecordRepository]

  override implicit val patienceConfig = PatienceConfig(scaled(Span(10, Seconds)), scaled(Span(500, Millis)))

  val arn = Arn("AARN0000002")
  val mtdItId = MtdItId("ABCDEF123456789")
  val nino = Nino("AB123456C")
  val mtdItIdType = "MTDITID"

  "Recovery Scheduler" should {
    "Create a Recovery Record if no record exists" in {
      givenPrincipalGroupIdExistsFor(arn, "foo")
      givenDelegatedGroupIdsExistForKey(s"HMRC-MTD-IT~MTDITID~${mtdItId.value}", Set("foo"))
      givenPrincipalUserIdExistFor(arn, "userId")
      givenEnrolmentDeallocationSucceeds("foo", mtdItId)
      givenGroupInfo("foo", "bar")
      givenAuditConnector()

      val deleteRecord = DeleteRecord(
        arn.value,
        mtdItId.value,
        mtdItIdType,
        DateTime.parse("2017-10-31T23:22:50.971Z"),
        syncToESStatus = Some(SyncStatus.Failed),
        syncToETMPStatus = Some(SyncStatus.Success)
      )
      await(deleteRepo.create(deleteRecord))

      await(deleteRepo.findBy(arn, mtdItId)) shouldBe Some(deleteRecord)

      eventually {
        await(recoveryRepo.findAll()).length shouldBe 1

        await(deleteRepo.findAll()).length shouldBe 0
      }

    }
  }

}
