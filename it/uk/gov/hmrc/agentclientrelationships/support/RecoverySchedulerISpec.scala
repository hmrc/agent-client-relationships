package uk.gov.hmrc.agentclientrelationships.support

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.{ActorSystem, Props}
import akka.testkit.TestKit
import org.scalatest.time.{Seconds, Span}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.repository._
import uk.gov.hmrc.agentclientrelationships.services.DeleteRelationshipsService
import uk.gov.hmrc.agentclientrelationships.stubs._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.mongo.test.CleanMongoCollectionSupport
import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class RecoverySchedulerISpec
    extends TestKit(ActorSystem("testSystem"))
    with UnitSpec
      with CleanMongoCollectionSupport
    with GuiceOneServerPerSuite
    with WireMockSupport
    with RelationshipStubs
    with DesStubs
    with DesStubsGet
    with MappingStubs
    with DataStreamStub
    with AuthStub
   {

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
                        "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
                        "microservice.services.tax-enrolments.port"               -> wireMockPort,
                        "microservice.services.users-groups-search.port"          -> wireMockPort,
                        "microservice.services.des.port"                          -> wireMockPort,
                        "microservice.services.auth.port"                         -> wireMockPort,
                        "microservice.services.agent-mapping.port"                -> wireMockPort,
                        "auditing.consumer.baseUri.host"                          -> wireMockHost,
                        "auditing.consumer.baseUri.port"                          -> wireMockPort,
                        "microservice.services.agent-user-client-details.port"    -> wireMockPort,
                        "features.copy-relationship.mtd-it"                       -> true,
                        "features.copy-relationship.mtd-vat"                      -> true,
        "features.recovery-enable" -> false,
        "auditing.enabled" -> false,
        "metrics.enabled" -> false,
        "mongodb.uri" -> mongoUri
      )

  override implicit lazy val app: Application = appBuilder.build()

  private lazy val recoveryRepo = app.injector.instanceOf[MongoRecoveryScheduleRepository]
  private lazy val deleteRepo = app.injector.instanceOf[MongoDeleteRecordRepository]
  private lazy val deleteRelationshipService = app.injector.instanceOf[DeleteRelationshipsService]

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(30, Seconds)), scaled(Span(2, Seconds)))

  val arn: Arn = Arn("AARN0000002")
  val mtdItId: MtdItId = MtdItId("ABCDEF123456789")
  val nino: Nino = Nino("AB123456C")
  val mtdItIdType = "MTDITID"

  val localDateTimeNow: LocalDateTime = LocalDateTime.now


  "Recovery Scheduler" should {

    "attempt to recover if DeleteRecord exists but RecoveryRecord not" in {

      val testKit = ActorTestKit()
      val actorRef = system.actorOf(
        Props(
          new TaskActor(recoveryRepo, 5,
            deleteRelationshipService.tryToResume(global, new AuditData()).map(_ => ()))))

      givenPrincipalGroupIdExistsFor(arn, "foo")
      givenDelegatedGroupIdsExistForKey(s"HMRC-MTD-IT~MTDITID~${mtdItId.value}", Set("foo"))
      givenPrincipalUserIdExistFor(arn, "userId")
      givenEnrolmentDeallocationSucceeds("foo", mtdItId)
      givenGroupInfo("foo", "bar")

      await(recoveryRepo.collection.find().toFuture()) shouldBe empty

      val deleteRecord = DeleteRecord(
        arn.value,
        mtdItId.value,
        mtdItIdType,
        LocalDateTime.parse("2022-10-31T23:22:50.971"),
        syncToESStatus = Some(SyncStatus.Failed),
        syncToETMPStatus = Some(SyncStatus.Success)
      )

      await(deleteRepo.collection.insertOne(deleteRecord).toFuture())
      await(deleteRepo.findBy(arn, mtdItId)) shouldBe Some(deleteRecord)

      testKit.scheduler.scheduleOnce(0.seconds, new Runnable {
        def run = {
          actorRef ! "uid"
        }
      })

      eventually {
        await(recoveryRepo.collection.find().toFuture()).length shouldBe 1
        await(deleteRepo.collection.find().toFuture()).length shouldBe 0
      }
      testKit.shutdownTestKit()
    }

    "attempt to recover if both DeleteRecord and RecoveryRecord exist and nextRunAt is in the future" in {
      val testKit = ActorTestKit()
      val actorRef = system.actorOf(
        Props(
          new TaskActor(recoveryRepo, 5,
            deleteRelationshipService.tryToResume(global, new AuditData()).map(_ => ()))))

      givenPrincipalGroupIdExistsFor(arn, "foo")
      givenDelegatedGroupIdsExistForKey(s"HMRC-MTD-IT~MTDITID~${mtdItId.value}", Set("foo"))
      givenPrincipalUserIdExistFor(arn, "userId")
      givenEnrolmentDeallocationSucceeds("foo", mtdItId)
      givenGroupInfo("foo", "bar")

      await(recoveryRepo.write("1", localDateTimeNow.plusSeconds(2)))
      await(recoveryRepo.collection.find().toFuture()).length shouldBe 1

      val deleteRecord = DeleteRecord(
        arn.value,
        mtdItId.value,
        mtdItIdType,
        LocalDateTime.parse("2022-10-31T23:22:50.971"),
        syncToESStatus = Some(SyncStatus.Failed),
        syncToETMPStatus = Some(SyncStatus.Success)
      )

      await(deleteRepo.create(deleteRecord))
      await(deleteRepo.findBy(arn, mtdItId)) shouldBe Some(deleteRecord)

      testKit.scheduler.scheduleOnce(0.seconds, new Runnable {
        def run = {
          actorRef ! "uid"
        }
      })

      eventually {
        await(recoveryRepo.collection.find().toFuture()).length shouldBe 1
        await(deleteRepo.collection.find().toFuture()).length shouldBe 0
      }

      testKit.shutdownTestKit()

    }

    "attempt to recover if both DeleteRecord and RecoveryRecord exist and nextRunAt is in the past" in {
      val testKit = ActorTestKit()
      val actorRef = system.actorOf(
        Props(
          new TaskActor(recoveryRepo, 5,
            deleteRelationshipService.tryToResume(global, new AuditData()).map(_ => ()))))

      givenPrincipalGroupIdExistsFor(arn, "foo")
      givenDelegatedGroupIdsExistForKey(s"HMRC-MTD-IT~MTDITID~${mtdItId.value}", Set("foo"))
      givenPrincipalUserIdExistFor(arn, "userId")
      givenEnrolmentDeallocationSucceeds("foo", mtdItId)
      givenGroupInfo("foo", "bar")

      await(recoveryRepo.write("1", localDateTimeNow.minusDays(2)))
      await(recoveryRepo.collection.find().toFuture()).length shouldBe 1

      val deleteRecord = DeleteRecord(
        arn.value,
        mtdItId.value,
        mtdItIdType,
        LocalDateTime.parse("2022-10-31T23:22:50.971"),
        syncToESStatus = Some(SyncStatus.Failed),
        syncToETMPStatus = Some(SyncStatus.Success)
      )
      await(deleteRepo.create(deleteRecord))
      await(deleteRepo.findBy(arn, mtdItId)) shouldBe Some(deleteRecord)

      testKit.scheduler.scheduleOnce(0.seconds, new Runnable {
        def run = {
          actorRef ! "uid"
        }
      })

      eventually {
        await(recoveryRepo.collection.find().toFuture()).length shouldBe 1
        await(deleteRepo.collection.find().toFuture()).length shouldBe 0
      }
      testKit.shutdownTestKit()
    }

    "attempt to recover multiple DeleteRecords" in {
      val testKit = ActorTestKit()
      val actorRef = system.actorOf(
        Props(
          new TaskActor(recoveryRepo, 1,
            deleteRelationshipService.tryToResume(global, new AuditData()).map(_ => ()))))

      givenGroupInfo("foo", "bar")
      givenPrincipalUserIdExistFor(arn, "userId")
      givenPrincipalGroupIdExistsFor(arn, "foo")

      await(recoveryRepo.write("1", localDateTimeNow.minusDays(2)))
      await(recoveryRepo.collection.find().toFuture()).length shouldBe 1

      (0 to 3) foreach { index =>
        val deleteRecord = DeleteRecord(
          arn.value,
          mtdItId.value + index,
          mtdItIdType,
          LocalDateTime.parse("2022-10-31T23:22:50.971"),
          syncToESStatus = Some(SyncStatus.Failed),
          syncToETMPStatus = Some(SyncStatus.Success)
        )

        givenDelegatedGroupIdsExistForKey(s"HMRC-MTD-IT~MTDITID~${mtdItId.value + index}", Set("foo"))
        givenEnrolmentDeallocationSucceeds("foo", MtdItId(mtdItId.value + index))

        await(deleteRepo.create(deleteRecord))
      }

      val res1 = await(deleteRepo.collection.find().toFuture()).length
      res1 shouldBe 4

      testKit.scheduler.scheduleOnce(500.millis, new Runnable {
        def run = {
          actorRef ! "uid"
        }
      })

      eventually {
        await(deleteRepo.collection.find().toFuture()).length shouldBe 0
      }
      testKit.shutdownTestKit()
    }

    "attempt to recover multiple DeleteRecords when one of them constantly fails and other fails because auth token has expired" in {

            givenGroupInfo("foo", "bar")
            givenPrincipalUserIdExistFor(arn, "userId")
            givenPrincipalGroupIdExistsFor(arn, "foo")
            givenAdminUser("foo", "any")


      val testKit = ActorTestKit()
      val actorRef = system.actorOf(
        Props(
          new TaskActor(recoveryRepo, 1,
            deleteRelationshipService.tryToResume(global, new AuditData()).map(_ => ()))))

      await(recoveryRepo.collection.drop().toFuture())

      await(recoveryRepo.write("1", localDateTimeNow.minusDays(2)))
      await(recoveryRepo.collection.find().toFuture()).length shouldBe 1

      (0 to 3) foreach { index =>
        val deleteRecord = DeleteRecord(
          arn.value,
          mtdItId.value + index,
          mtdItIdType,
          LocalDateTime.now.minusDays(index),
          syncToESStatus = Some(SyncStatus.Failed),
          syncToETMPStatus = Some(SyncStatus.Success)
        )

        givenDelegatedGroupIdsExistForKey(s"HMRC-MTD-IT~MTDITID~${mtdItId.value + index}", Set("foo"))
        if (index == 0)
          givenEnrolmentDeallocationFailsWith(503)(
            "foo",
            "HMRC-MTD-IT",
            deleteRecord.clientIdentifierType,
            deleteRecord.clientIdentifier)
         else
          givenEnrolmentDeallocationSucceeds("foo", MtdItId(mtdItId.value + index))

        await(deleteRepo.create(deleteRecord))
      }


      testKit.scheduler.scheduleOnce(500.millis, new Runnable {
        def run = {
          actorRef ! "uid"
        }
      })

      eventually {
        await(deleteRepo.collection.find().toFuture()).length shouldBe 1
      }

      eventually {
        val deleteRecords = await(deleteRepo.collection.find().toFuture())
        deleteRecords.length shouldBe 1
        deleteRecords.head.numberOfAttempts should (be > 1)
      }
      testKit.shutdownTestKit()
    }
  }
}
