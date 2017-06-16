package uk.gov.hmrc.agentrelationships.repository

import java.util.UUID

import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecordRepository
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
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
      val uuid = UUID.randomUUID().toString
      val clientId = "client-"+uuid.substring(0,8)
      val service = "service-"+uuid.substring(8,16)
      await(repo.createRelationshipCopyRecord(arn1, service, clientId))

      val resultByClientId = await(repo.find("clientId" -> clientId))
      resultByClientId.size shouldBe 1
      resultByClientId.head.arn shouldBe arn1.value
      resultByClientId.head.service shouldBe service
      resultByClientId.head.clientId shouldBe clientId

      val resultByService = await(repo.find("service" -> service))
      resultByService.size shouldBe 1
      resultByService.head.arn shouldBe arn1.value
      resultByService.head.service shouldBe service
      resultByService.head.clientId shouldBe clientId

      val resultByArn = await(repo.find("arn" -> arn1.value))
      resultByArn should not be empty
    }
  }
}
