/*
 * Copyright 2024 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.agentclientrelationships.support

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterEach
import org.scalatest.time.{Seconds, Span}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository._
import uk.gov.hmrc.agentclientrelationships.services.DeleteRelationshipsServiceWithAca
import uk.gov.hmrc.agentclientrelationships.stubs._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Service}
import uk.gov.hmrc.mongo.test.MongoSupport

import java.time.{LocalDateTime, ZoneOffset}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class RecoverySchedulerISpec(implicit val ec: ExecutionContext)
    extends TestKit(ActorSystem("testSystem"))
    with UnitSpec
    with MongoSupport
    with GuiceOneServerPerSuite
    with WireMockSupport
    with RelationshipStubs
    with DataStreamStub
    with IFStubs
    with IFAgentClientRelationshipStub
    with ACAStubs
    with AUCDStubs
    with BeforeAndAfterEach {

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.enrolment-store-proxy.port"      -> wireMockPort,
        "microservice.services.tax-enrolments.port"             -> wireMockPort,
        "microservice.services.users-groups-search.port"        -> wireMockPort,
        "microservice.services.if.port"                         -> wireMockPort,
        "auditing.consumer.baseUri.host"                        -> wireMockHost,
        "auditing.consumer.baseUri.port"                        -> wireMockPort,
        "features.copy-relationship.mtd-it"                     -> true,
        "features.copy-relationship.mtd-vat"                    -> true,
        "microservice.services.agent-client-authorisation.port" -> wireMockPort,
        "microservice.services.agent-user-client-details.port"  -> wireMockPort,
        "features.recovery-enable"                              -> false,
        "auditing.enabled"                                      -> true,
        "metrics.enabled"                                       -> true,
        "mongodb.uri"                                           -> mongoUri
      )

  override implicit lazy val app: Application = appBuilder.build()

  private lazy val recoveryRepo = app.injector.instanceOf[MongoRecoveryScheduleRepository]
  private lazy val deleteRepo = app.injector.instanceOf[MongoDeleteRecordRepository]
  private lazy val deleteRelationshipService = app.injector.instanceOf[DeleteRelationshipsServiceWithAca]

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(scaled(Span(30, Seconds)), scaled(Span(2, Seconds)))

  private val arn: Arn = Arn("AARN0000002")
  private val mtdItId: MtdItId = MtdItId("ABCDEF123456789")
  private val mtdItEnrolmentKey = EnrolmentKey(Service.MtdIt, mtdItId)

  override def beforeEach(): Unit = {
    super.beforeEach()
    deleteRepo.collection.drop().toFuture().futureValue
    ()
  }

  override def afterAll() = {
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }

  val testKit: ActorTestKit = ActorTestKit()
  val actorRef: ActorRef = system.actorOf(
    Props(
      new TaskActor(recoveryRepo, 2, deleteRelationshipService.tryToResume(ec, new AuditData()).map(_ => ())(ec))(ec)
    )
  )

  testKit.scheduler.scheduleOnce(
    1.second,
    new Runnable {
      def run =
        actorRef ! "uid"
    }
  )(ec)

  "Recovery Scheduler" should {

    "attempt to recover if a DeleteRecord exists and it requires only ETMP (because ES had already succeeded)" in {

      givenAgentCanBeDeallocated(mtdItId, arn)
      givenSetRelationshipEnded(mtdItId, arn)
      givenAuditConnector()

      val deleteRecord = DeleteRecord(
        arn.value,
        Some(mtdItEnrolmentKey),
        dateTime = LocalDateTime.now().atZone(ZoneOffset.UTC).toLocalDateTime.minusSeconds(10),
        syncToESStatus = Some(SyncStatus.Success),
        syncToETMPStatus = Some(SyncStatus.Failed)
      )

      await(deleteRepo.collection.insertOne(deleteRecord).toFuture())
      await(deleteRepo.findBy(arn, mtdItEnrolmentKey)).isDefined shouldBe true

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
      givenAgentCanBeDeallocated(mtdItId, arn)
      givenSetRelationshipEnded(mtdItId, arn)
      givenAuditConnector()
      givenCacheRefresh(arn)

      val deleteRecord = DeleteRecord(
        arn.value,
        Some(mtdItEnrolmentKey),
        dateTime = LocalDateTime.now().atZone(ZoneOffset.UTC).toLocalDateTime.minusSeconds(10),
        syncToESStatus = Some(SyncStatus.Failed),
        syncToETMPStatus = Some(SyncStatus.Failed)
      )

      await(deleteRepo.create(deleteRecord))
      await(deleteRepo.findBy(arn, mtdItEnrolmentKey)).isDefined shouldBe true

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
      givenAgentCanBeDeallocated(mtdItId, arn)
      givenSetRelationshipEnded(mtdItId, arn)
      givenAuditConnector()
      givenCacheRefresh(arn)

      val deleteRecord = DeleteRecord(
        arn.value,
        Some(mtdItEnrolmentKey),
        dateTime = LocalDateTime.now().atZone(ZoneOffset.UTC).toLocalDateTime.minusSeconds(10),
        syncToESStatus = Some(SyncStatus.Failed),
        syncToETMPStatus = Some(SyncStatus.Failed)
      )
      await(deleteRepo.create(deleteRecord))
      await(deleteRepo.findBy(arn, mtdItEnrolmentKey)).isDefined shouldBe true

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
        val iMtdItId = MtdItId("ABCDEF12345678" + index)

        val deleteRecord = DeleteRecord(
          arn.value,
          Some(EnrolmentKey(Service.MtdIt, iMtdItId)),
          dateTime = LocalDateTime.now().atZone(ZoneOffset.UTC).toLocalDateTime.minusSeconds(index),
          syncToESStatus = Some(SyncStatus.Success),
          syncToETMPStatus = Some(SyncStatus.Failed)
        )

        givenAgentCanBeDeallocated(iMtdItId, arn)
        givenSetRelationshipEnded(iMtdItId, arn)
        givenAuditConnector()
        givenCacheRefresh(arn)

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
        val iMtdItId = MtdItId("ABCDEF12345678" + index)

        val deleteRecord = DeleteRecord(
          arn.value,
          Some(EnrolmentKey(Service.MtdIt, iMtdItId)),
          dateTime = LocalDateTime.now().atZone(ZoneOffset.UTC).toLocalDateTime.minusSeconds(index),
          syncToESStatus = Some(SyncStatus.Failed),
          syncToETMPStatus = Some(SyncStatus.Failed)
        )

        givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
        givenPrincipalUserIdExistFor(agentEnrolmentKey(arn), "userId")
        givenGroupInfo("foo", "bar")
        givenAdminUser("foo", "userId")
        givenAuditConnector()
        givenDelegatedGroupIdsExistFor(EnrolmentKey(Service.MtdIt, iMtdItId), Set("foo"))

        if (index == 0)
          givenEnrolmentDeallocationFailsWith(503)("foo", deleteRecord.enrolmentKey.get)
        else {
          givenAgentCanBeDeallocated(iMtdItId, arn)
          givenSetRelationshipEnded(iMtdItId, arn)
          givenEnrolmentDeallocationSucceeds("foo", EnrolmentKey(Service.MtdIt, iMtdItId))
          givenCacheRefresh(arn)
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
