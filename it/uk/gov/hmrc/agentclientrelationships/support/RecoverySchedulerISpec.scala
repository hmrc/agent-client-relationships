package uk.gov.hmrc.agentclientrelationships.support

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.{ActorSystem, Props}
import akka.testkit.TestKit
import org.scalatest.BeforeAndAfterEach
import org.scalatest.time.{Seconds, Span}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository._
import uk.gov.hmrc.agentclientrelationships.services.DeleteRelationshipsService
import uk.gov.hmrc.agentclientrelationships.stubs._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Identifier, MtdItId, Service}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.mongo.test.MongoSupport

import java.time.{LocalDateTime, ZoneOffset}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class RecoverySchedulerISpec
    extends TestKit(ActorSystem("testSystem"))
    with UnitSpec
      with MongoSupport
    with GuiceOneServerPerSuite
    with WireMockSupport
    with RelationshipStubs
    with DataStreamStub
      with IFStubs
      with ACAStubs
      with BeforeAndAfterEach
   {

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
                        "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
                        "microservice.services.tax-enrolments.port"               -> wireMockPort,
                        "microservice.services.users-groups-search.port"          -> wireMockPort,
                        "microservice.services.if.port"                          -> wireMockPort,
                        "auditing.consumer.baseUri.host"                          -> wireMockHost,
                        "auditing.consumer.baseUri.port"                          -> wireMockPort,
                        "features.copy-relationship.mtd-it"                       -> true,
                        "features.copy-relationship.mtd-vat"                      -> true,
                         "microservice.services.agent-client-authorisation.port"  -> wireMockPort,
        "features.recovery-enable" -> false,
        "auditing.enabled" -> true,
        "metrics.enabled" -> true,
        "mongodb.uri" -> mongoUri
      )

  override implicit lazy val app: Application = appBuilder.build()

  private lazy val recoveryRepo = app.injector.instanceOf[MongoRecoveryScheduleRepository]
  private lazy val deleteRepo = app.injector.instanceOf[MongoDeleteRecordRepository]
  private lazy val deleteRelationshipService = app.injector.instanceOf[DeleteRelationshipsService]

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(30, Seconds)), scaled(Span(2, Seconds)))

  val arn: Arn = Arn("AARN0000002")
  val mtdItId: MtdItId = MtdItId("ABCDEF123456789")
  private val mtdItEnrolmentKey = EnrolmentKey(Service.MtdIt, mtdItId)
  val nino: Nino = Nino("AB123456C")
  val mtdItIdType = "MTDITID"

     override def beforeEach(): Unit = {
       super.beforeEach()
       deleteRepo.collection.drop().toFuture().futureValue
       ()
     }

     override def afterAll() = {
       super.afterAll()
       TestKit.shutdownActorSystem(system)
     }

     val testKit = ActorTestKit()
     val actorRef = system.actorOf(
       Props(
         new TaskActor(recoveryRepo, 2,
           deleteRelationshipService.tryToResume(global, new AuditData()).map(_ => ()))))

     testKit.scheduler.scheduleOnce(1.second, new Runnable {
       def run = {
         actorRef ! "uid"
       }
     })


  "Recovery Scheduler" should {

    "attempt to recover if a DeleteRecord exists and it requires only ETMP (because ES had already succeeded)" in {

      givenAgentCanBeDeallocatedInIF(mtdItId, arn)
      givenSetRelationshipEnded(mtdItId, arn)
      givenAuditConnector()

      val deleteRecord = DeleteRecord(
        arn.value,
        Some(Service.MtdIt.id),
        mtdItId.value,
        mtdItIdType,
        LocalDateTime.now().atZone(ZoneOffset.UTC).toLocalDateTime.minusSeconds(10),
        syncToESStatus = Some(SyncStatus.Success),
        syncToETMPStatus = Some(SyncStatus.Failed)
      )

      await(deleteRepo.collection.insertOne(deleteRecord).toFuture())
      await(deleteRepo.findBy(arn, mtdItId)).isDefined shouldBe true

      eventually {
        await(recoveryRepo.collection.find().toFuture()).length shouldBe 1
        await(deleteRepo.collection.find().toFuture()).length shouldBe 0
      }
    }

    "attempt to recover if a DeleteRecord and a RecoveryRecord exists and both ES and ETMP are required" in {

      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
      givenDelegatedGroupIdsExistFor(EnrolmentKey(s"HMRC-MTD-IT~MTDITID~${mtdItId.value}"), Set("foo"))
      givenPrincipalUserIdExistFor(agentEnrolmentKey(arn), "userId")
      givenEnrolmentDeallocationSucceeds("foo", mtdItEnrolmentKey)
      givenGroupInfo("foo", "bar")
      givenAdminUser("foo", "userId")
      givenAgentCanBeDeallocatedInIF(mtdItId, arn)
      givenSetRelationshipEnded(mtdItId, arn)
      givenAuditConnector()

      val deleteRecord = DeleteRecord(
        arn.value,
        Some(Service.MtdIt.id),
        mtdItId.value,
        mtdItIdType,
        LocalDateTime.now().atZone(ZoneOffset.UTC).toLocalDateTime.minusSeconds(10),
        syncToESStatus = Some(SyncStatus.Failed),
        syncToETMPStatus = Some(SyncStatus.Failed)
      )

      await(deleteRepo.create(deleteRecord))
      await(deleteRepo.findBy(arn, mtdItId)).isDefined shouldBe true

      eventually {
        await(recoveryRepo.collection.find().toFuture()).length shouldBe 1
        await(deleteRepo.collection.find().toFuture()).length shouldBe 0
      }
    }

    "attempt to recover if both DeleteRecord and RecoveryRecord exist and nextRunAt is in the past" in {

      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
      givenDelegatedGroupIdsExistFor(EnrolmentKey(s"HMRC-MTD-IT~MTDITID~${mtdItId.value}"), Set("foo"))
      givenPrincipalUserIdExistFor(agentEnrolmentKey(arn), "userId")
      givenEnrolmentDeallocationSucceeds("foo", mtdItEnrolmentKey)
      givenGroupInfo("foo", "bar")
      givenAdminUser("foo", "userId")
      givenAgentCanBeDeallocatedInIF(mtdItId, arn)
      givenSetRelationshipEnded(mtdItId, arn)
      givenAuditConnector()

      val deleteRecord = DeleteRecord(
        arn.value,
        Some(Service.MtdIt.id),
        mtdItId.value,
        mtdItIdType,
        LocalDateTime.now().atZone(ZoneOffset.UTC).toLocalDateTime.minusSeconds(10),
        syncToESStatus = Some(SyncStatus.Failed),
        syncToETMPStatus = Some(SyncStatus.Failed)
      )
      await(deleteRepo.create(deleteRecord))
      await(deleteRepo.findBy(arn, mtdItId)).isDefined shouldBe true

      eventually {
        await(recoveryRepo.collection.find().toFuture()).length shouldBe 1
        await(deleteRepo.collection.find().toFuture()).length shouldBe 0
      }
    }

    "attempt to recover multiple DeleteRecords" in {

      givenGroupInfo("foo", "bar")
      givenPrincipalUserIdExistFor(agentEnrolmentKey(arn), "userId")
      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")

      (0 to 3) foreach { index =>
        val deleteRecord = DeleteRecord(
          arn.value,
          Some(Service.MtdIt.id),
          mtdItId.value + index,
          mtdItIdType,
          LocalDateTime.now().atZone(ZoneOffset.UTC).toLocalDateTime.minusSeconds(index),
          syncToESStatus = Some(SyncStatus.Success),
          syncToETMPStatus = Some(SyncStatus.Failed)
        )

        givenAgentCanBeDeallocatedInIF(MtdItId(mtdItId.value + index)  , arn)
        givenSetRelationshipEnded(MtdItId(mtdItId.value + index), arn)
        givenAuditConnector()

        await(deleteRepo.create(deleteRecord))
      }

      val res1 = await(deleteRepo.collection.find().toFuture()).length
      res1 shouldBe 4

      eventually {
        await(recoveryRepo.collection.find().toFuture()).length shouldBe 1
        await(deleteRepo.collection.find().toFuture()).length shouldBe 0
      }
    }

    "attempt to recover multiple DeleteRecords and one of them repeatedly fails" in {

      (0 to 2) foreach { index =>
        val deleteRecord = DeleteRecord(
          arn.value,
          Some(Service.MtdIt.id),
          mtdItId.value + index,
          mtdItIdType,
          LocalDateTime.now().atZone(ZoneOffset.UTC).toLocalDateTime.minusSeconds(index),
          syncToESStatus = Some(SyncStatus.Failed),
          syncToETMPStatus = Some(SyncStatus.Failed)
        )

        givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
        givenPrincipalUserIdExistFor(agentEnrolmentKey(arn), "userId")
        givenGroupInfo("foo", "bar")
        givenAdminUser("foo", "userId")
        givenAuditConnector()
        givenDelegatedGroupIdsExistFor(EnrolmentKey(s"HMRC-MTD-IT~MTDITID~${mtdItId.value + index}"), Set("foo"))

        if (index == 0)
          givenEnrolmentDeallocationFailsWith(503)(
            "foo",
            EnrolmentKey("HMRC-MTD-IT", Seq(Identifier(deleteRecord.clientIdentifierType, deleteRecord.clientIdentifier))))
        else {
          givenAgentCanBeDeallocatedInIF(MtdItId(mtdItId.value + index), arn)
          givenSetRelationshipEnded(MtdItId(mtdItId.value + index), arn)
          givenEnrolmentDeallocationSucceeds("foo", EnrolmentKey(Service.MtdIt, MtdItId(mtdItId.value + index)))
        }

        await(deleteRepo.create(deleteRecord))
      }

      eventually {
        val deleteRecords = await(deleteRepo.collection.find().toFuture())
        deleteRecords.length shouldBe 1
        deleteRecords.head.numberOfAttempts should (be > 1)
      }
    }
  }
}
