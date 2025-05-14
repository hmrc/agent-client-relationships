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

package uk.gov.hmrc.agentclientrelationships.repository

import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.support.MongoApp
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class RecoveryScheduleRepositoryISpec
extends UnitSpec
with MongoApp
with GuiceOneServerPerSuite {

  protected def appBuilder: GuiceApplicationBuilder = new GuiceApplicationBuilder()
    .configure("features.recovery-enable" -> false)
    .configure(mongoConfiguration)

  override implicit lazy val app: Application = appBuilder.build()

  private lazy val repo = app.injector.instanceOf[MongoRecoveryScheduleRepository]

  override def beforeEach() = {
    super.beforeEach()
    await(repo.ensureIndexes())
    ()
  }

  val uid: UUID = UUID.randomUUID()
  val newDate: LocalDateTime = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime

  "RecoveryRepository" should {
    "read and write" in {
      val recoveryRecord = RecoveryRecord("foo", LocalDateTime.parse("2017-10-31T23:22:50.971"))
      val newRecoveryRecord = RecoveryRecord("foo", LocalDateTime.parse("2019-10-31T23:22:50.971"))

      await(repo.collection.insertOne(recoveryRecord).toFuture())

      await(repo.read) shouldBe recoveryRecord

      await(repo.collection.drop().toFuture())

      await(repo.read)

      await(repo.collection.find().toFuture()).length shouldBe 1

      await(repo.write("foo", LocalDateTime.parse("2019-10-31T23:22:50.971")))

      await(repo.collection.find().toFuture()).head shouldBe newRecoveryRecord

      await(repo.collection.find().toFuture()).length shouldBe 1

    }
  }

}
