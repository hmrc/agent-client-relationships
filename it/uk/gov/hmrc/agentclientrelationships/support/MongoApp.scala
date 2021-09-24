package uk.gov.hmrc.agentclientrelationships.support

import org.scalatest.{BeforeAndAfterEach, Suite}
import reactivemongo.api.FailoverStrategy
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport, Awaiting => MongoAwaiting}

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global

trait MongoApp extends MongoSpecSupport with ResetMongoBeforeTest {
  me: Suite =>

  protected def mongoConfiguration = Map("mongodb.uri" -> mongoUri)

  override implicit lazy val mongoConnectorForTest: MongoConnector =
    MongoConnector(mongoUri, Some(MongoApp.failoverStrategyForTest))
}

object MongoApp {

  import scala.concurrent.duration._
  val failoverStrategyForTest = FailoverStrategy(5000.millis, 75, _ * 1.618)

}

trait ResetMongoBeforeTest extends BeforeAndAfterEach {
  me: Suite with MongoSpecSupport =>

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    dropMongoDb()
  }

  def dropMongoDb()(implicit ec: ExecutionContext = global): Unit =
    Awaiting.await(mongo().drop())
}

object Awaiting extends MongoAwaiting
