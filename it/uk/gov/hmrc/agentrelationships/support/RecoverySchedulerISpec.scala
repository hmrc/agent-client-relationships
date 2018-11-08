package uk.gov.hmrc.agentrelationships.support

import org.joda.time.{DateTime, DateTimeZone}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time.{Seconds, Span}
import org.scalatestplus.play.OneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.repository._
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

  override implicit val patienceConfig = PatienceConfig(scaled(Span(30, Seconds)), scaled(Span(2, Seconds)))

  val arn = Arn("AARN0000002")
  val mtdItId = MtdItId("ABCDEF123456789")
  val nino = Nino("AB123456C")
  val mtdItIdType = "MTDITID"

  "Recovery Scheduler" should {
    "attempt to recover if DeleteRecord exists but RecoveryRecord not" in {
      givenPrincipalGroupIdExistsFor(arn, "foo")
      givenDelegatedGroupIdsExistForKey(s"HMRC-MTD-IT~MTDITID~${mtdItId.value}", Set("foo"))
      givenPrincipalUserIdExistFor(arn, "userId")
      givenEnrolmentDeallocationSucceeds("foo", mtdItId)
      givenGroupInfo("foo", "bar")
      givenAuditConnector()

      await(recoveryRepo.findAll()) shouldBe empty

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

    "attempt to recover if both DeleteRecord and RecoveryRecord exist and nextRunAt is in the future" in {
      givenPrincipalGroupIdExistsFor(arn, "foo")
      givenDelegatedGroupIdsExistForKey(s"HMRC-MTD-IT~MTDITID~${mtdItId.value}", Set("foo"))
      givenPrincipalUserIdExistFor(arn, "userId")
      givenEnrolmentDeallocationSucceeds("foo", mtdItId)
      givenGroupInfo("foo", "bar")
      givenAuditConnector()

      await(recoveryRepo.write("1", DateTime.now(DateTimeZone.UTC).plusSeconds(2)))
      await(recoveryRepo.findAll()).length shouldBe 1

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

    "attempt to recover if both DeleteRecord and RecoveryRecord exist and nextRunAt is in the past" in {
      givenPrincipalGroupIdExistsFor(arn, "foo")
      givenDelegatedGroupIdsExistForKey(s"HMRC-MTD-IT~MTDITID~${mtdItId.value}", Set("foo"))
      givenPrincipalUserIdExistFor(arn, "userId")
      givenEnrolmentDeallocationSucceeds("foo", mtdItId)
      givenGroupInfo("foo", "bar")
      givenAuditConnector()

      await(recoveryRepo.write("1", DateTime.now(DateTimeZone.UTC).minusDays(2)))
      await(recoveryRepo.findAll()).length shouldBe 1

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

    "attempt to recover multiple DeleteRecords" in {
      givenAuditConnector()
      givenGroupInfo("foo", "bar")
      givenPrincipalUserIdExistFor(arn, "userId")
      givenPrincipalGroupIdExistsFor(arn, "foo")

      await(recoveryRepo.write("1", DateTime.now(DateTimeZone.UTC).minusDays(2)))
      await(recoveryRepo.findAll()).length shouldBe 1

      (0 to 10) foreach { index =>
        val deleteRecord = DeleteRecord(
          arn.value,
          mtdItId.value + index,
          mtdItIdType,
          DateTime.parse("2017-10-31T23:22:50.971Z"),
          syncToESStatus = Some(SyncStatus.Failed),
          syncToETMPStatus = Some(SyncStatus.Success)
        )

        givenDelegatedGroupIdsExistForKey(s"HMRC-MTD-IT~MTDITID~${mtdItId.value + index}", Set("foo"))
        givenEnrolmentDeallocationSucceeds("foo", MtdItId(mtdItId.value + index))

        await(deleteRepo.create(deleteRecord))
      }

      await(deleteRepo.findAll()).length shouldBe 11

      eventually {
        await(deleteRepo.findAll()).length shouldBe 0
      }

    }

    "attempt to recover multiple DeleteRecords when one of them constantly fails" in {
      givenAuditConnector()
      givenGroupInfo("foo", "bar")
      givenPrincipalUserIdExistFor(arn, "userId")
      givenPrincipalGroupIdExistsFor(arn, "foo")

      await(recoveryRepo.removeAll())

      await(recoveryRepo.write("1", DateTime.now(DateTimeZone.UTC).minusDays(2)))
      await(recoveryRepo.findAll()).length shouldBe 1

      (0 to 10) foreach { index =>
        val deleteRecord = DeleteRecord(
          arn.value,
          mtdItId.value + index,
          mtdItIdType,
          DateTime.parse("2017-10-31T23:22:50.971Z"),
          syncToESStatus = Some(SyncStatus.Failed),
          syncToETMPStatus = Some(SyncStatus.Success)
        )

        givenDelegatedGroupIdsExistForKey(s"HMRC-MTD-IT~MTDITID~${mtdItId.value + index}", Set("foo"))
        if (index == 5)
          givenEnrolmentDeallocationFailsWith(502)(
            "foo",
            "HMRC-MTD-IT",
            deleteRecord.clientIdentifierType,
            deleteRecord.clientIdentifier)
        else
          givenEnrolmentDeallocationSucceeds("foo", MtdItId(mtdItId.value + index))

        await(deleteRepo.create(deleteRecord))
      }

      await(deleteRepo.findAll()).length shouldBe 11

      eventually {
        await(deleteRepo.findAll()).length shouldBe 1
      }

      Thread.sleep(3000)

      eventually {
        val deleteRecords = await(deleteRepo.findAll())
        deleteRecords.length shouldBe 1
        deleteRecords.head.numberOfAttempts should (be > 1)
      }

    }
  }

}
