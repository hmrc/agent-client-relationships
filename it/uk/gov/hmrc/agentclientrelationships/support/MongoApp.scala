package uk.gov.hmrc.agentclientrelationships.support

import org.scalatest.{BeforeAndAfterEach, Suite}
import uk.gov.hmrc.mongo.test.MongoSupport

trait MongoApp extends MongoSupport with ResetMongoBeforeTest {
  me: Suite =>

  protected def mongoConfiguration = Map("mongodb.uri" -> mongoUri)

}

trait ResetMongoBeforeTest extends BeforeAndAfterEach {
  me: Suite with MongoSupport =>

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    prepareDatabase()
  }
}
