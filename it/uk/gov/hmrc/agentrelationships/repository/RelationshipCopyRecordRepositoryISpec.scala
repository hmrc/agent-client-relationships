package uk.gov.hmrc.agentrelationships.repository

import java.util.UUID

import uk.gov.hmrc.agentclientrelationships.repository.{RelationshipCopyRecord, RelationshipCopyRecordRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.agentrelationships.support.MongoApp
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class RelationshipCopyRecordRepositoryISpec extends UnitSpec with MongoApp {

  val arn1 = Arn("ARN00001")

  def repo: RelationshipCopyRecordRepository = app.injector.instanceOf[RelationshipCopyRecordRepository]

  override def beforeEach() {
    super.beforeEach()
    await(repo.ensureIndexes)
  }

  "createRelationshipCopyRecord" should {
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
  }

  private def relationshipCopyRecord(
    arn: Arn,
    clientIdentifier: String,
    clientIdentifierType: String) = RelationshipCopyRecord(arn.value, clientIdentifier, clientIdentifierType)
}
