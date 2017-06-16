package uk.gov.hmrc.agentrelationships.repository

import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecordRepository
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentrelationships.support.MongoApp
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class RelationshipCopyRecordRepositoryISpec extends UnitSpec with MongoApp{

  val arn1 = Arn("ARN00001")

  def repo: RelationshipCopyRecordRepository = app.injector.instanceOf[RelationshipCopyRecordRepository]

  override def beforeEach() {
    super.beforeEach()
    await(repo.ensureIndexes)
  }

  "createRelationshipCopyRecord" should {
    "create a createRelationshipCopyRecord" in {
      await(repo.createRelationshipCopyRecord(arn1, "service1", "client1"))

      val result = await(repo.find())

      result.size shouldBe 1
      result.head.arn shouldBe arn1.value
      result.head.service shouldBe "service1"
      result.head.clientId shouldBe "client1"
    }
  }
}
