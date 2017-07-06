package uk.gov.hmrc.agentrelationships.repository

import java.util.UUID

import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.repository.{RelationshipCopyRecord, RelationshipCopyRecordRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.agentrelationships.support.MongoApp
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class RelationshipCopyRecordRepositoryISpec extends UnitSpec with MongoApp {

  val arn1 = Arn("ARN00001")

  override implicit lazy val app: Application = appBuilder
    .build()

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(mongoConfiguration)

  def repo: RelationshipCopyRecordRepository = app.injector.instanceOf[RelationshipCopyRecordRepository]

  override def beforeEach() {
    super.beforeEach()
    await(repo.ensureIndexes)
  }

  "RelationshipCopyRecordRepository" should {

    "create a createRelationshipCopyRecord" in {
      val uuid = UUID.randomUUID().toString.substring(0, 8)
      val mtdItId = s"client-$uuid"
      await(repo.create(relationshipCopyRecord(arn1, mtdItId, "MTDITID")))

      val resultByClientId = await(repo.find("clientIdentifier" -> mtdItId, "clientIdentifierType" -> "MTDITID"))
      resultByClientId.size shouldBe 1
      resultByClientId.head.arn shouldBe arn1.value
      resultByClientId.head.clientIdentifier shouldBe mtdItId
      resultByClientId.head.clientIdentifierType shouldBe "MTDITID"

      val resultByArn = await(repo.find("arn" -> arn1.value))
      resultByArn should not be empty
    }

    "find a particular RelationshipCopyRecord" in {
      val uuid = UUID.randomUUID().toString.substring(0, 8)
      val mtdItId = MtdItId(s"client-$uuid")
      await(repo.insert(relationshipCopyRecord(arn1, mtdItId.value, "MTDITID")))

      val result =  await(repo.findBy(arn1, mtdItId))

      result should not be empty
      val record = result.get
      record.arn shouldBe arn1.value
      record.clientIdentifier shouldBe mtdItId.value
      record.clientIdentifierType shouldBe "MTDITID"
    }

    "return empty result when relationshipCopyRecord does not exist" in {
      val mtdItId = MtdItId("")
      val result =  await(repo.findBy(arn1, mtdItId))

      result shouldBe empty
    }

    "clean copy status record" in {
      val uuid = UUID.randomUUID().toString.substring(0, 8)
      val mtdItId1 = s"client1-$uuid"
      await(repo.create(relationshipCopyRecord(arn1, mtdItId1, "MTDITID")))
      val mtdItId2 = s"client2-$uuid"
      await(repo.create(relationshipCopyRecord(arn1, mtdItId2, "MTDITID")))

      val resultBefore = await(repo.find("arn" -> arn1.value, "clientIdentifier" -> mtdItId1, "clientIdentifierType" -> "MTDITID"))
      resultBefore.size shouldBe 1

      val result = await(repo.remove(arn1,MtdItId(mtdItId1)))
      result shouldBe 1

      val result1After = await(repo.find("arn" -> arn1.value, "clientIdentifier" -> mtdItId1, "clientIdentifierType" -> "MTDITID"))
      result1After.size shouldBe 0

      val result2After = await(repo.find("arn" -> arn1.value, "clientIdentifier" -> mtdItId2, "clientIdentifierType" -> "MTDITID"))
      result2After.size shouldBe 1
    }

  }

  private def relationshipCopyRecord(
    arn: Arn,
    clientIdentifier: String,
    clientIdentifierType: String) = RelationshipCopyRecord(arn.value, clientIdentifier, clientIdentifierType)
}
