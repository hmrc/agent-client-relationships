package uk.gov.hmrc.agentrelationships.repository

import java.util.UUID

import org.scalatestplus.play.OneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.repository.{MongoRelationshipCopyRecordRepository, RelationshipCopyRecord}
import uk.gov.hmrc.agentclientrelationships.services.RelationshipCopyRecordRepositorySpec
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.agentrelationships.support.MongoApp
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class MongoRelationshipCopyRecordRepositoryISpec extends UnitSpec with MongoApp with OneAppPerSuite with RelationshipCopyRecordRepositorySpec{

  val arn1 = Arn("ARN00001")

  override implicit lazy val app: Application = appBuilder
    .build()

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(mongoConfiguration)

  override val repo = app.injector.instanceOf[MongoRelationshipCopyRecordRepository]

  override def beforeEach() {
    super.beforeEach()
    await(repo.ensureIndexes)
  }

}
