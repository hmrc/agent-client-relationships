/*
 * Copyright 2017 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.services

import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

trait RecoveryLockServiceSpec extends UnitSpec {

  val lockService: RecoveryLockService

  val arn = Arn("TARN0000001")
  val mtdItId = MtdItId("ABCDEF1234567890")

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
