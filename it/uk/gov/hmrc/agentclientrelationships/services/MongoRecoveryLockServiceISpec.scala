package uk.gov.hmrc.agentclientrelationships.services

import org.scalatestplus.play.OneAppPerSuite
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.play.test.UnitSpec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class MongoRecoveryLockServiceISpec extends UnitSpec with OneAppPerSuite {
  val arn = Arn("TARN0000001")
  val mtdItId = MtdItId("ABCDEF1234567890")


  val lockService = app.injector.instanceOf[MongoRecoveryLockService]

  "tryLock" should {
    "call the body if a lock is not held for the (Arn, MtdItId) pair" in {
      await(lockService.tryLock(arn, mtdItId) {
        Future successful "hello world"
      }) shouldBe Some("hello world")
    }

    "not call the body if a lock is held for the (Arn, MtdItId) pair" in {
      await(lockService.tryLock(arn, mtdItId) {
        lockService.tryLock(arn, mtdItId) {
          fail("body should not be called")
        }
      }) shouldBe Some(None)
    }
  }
}
