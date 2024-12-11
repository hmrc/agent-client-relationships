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

import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Indexes
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthInvitation
import uk.gov.hmrc.agentclientrelationships.support.MongoApp
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{MtdIt, Vat}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Service}
import uk.gov.hmrc.domain.Nino

import java.time.Instant
import scala.concurrent.ExecutionContext

class PartialAuthRepositoryISpec extends AnyWordSpec with Matchers with MongoApp with GuiceOneAppPerSuite {

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val repository: PartialAuthRepository = new PartialAuthRepository(mongoComponent)
  val nino = Nino("AB123456C")
  val notMatchingNino = Nino("AB654321C")
  val arn: Arn = Arn("ABCDE123456")
  val notMatchingArn: Arn = Arn("ABCDE654321")

  val partialAuth: PartialAuthInvitation = PartialAuthInvitation(
    Instant.parse("2020-02-02T00:00:00.000Z"),
    arn.value,
    Vat.id,
    nino.value
  )

  "partialAuthRepository" should {

    "have a custom compound index for the service and clientId fields" in {
      val customIndex = repository.indexes.find(_.getKeys.asInstanceOf[BsonDocument].containsKey("service")).get

      customIndex.getKeys shouldBe Indexes.ascending("service", "nino", "arn")
      customIndex.getOptions.getName shouldBe "clientQueryIndex"
      customIndex.getOptions.isUnique shouldBe true
    }

    "create a new partial auth record" in {
      await(
        repository.create(
          Instant.parse("2020-01-01T00:00:00.000Z"),
          arn,
          Vat,
          nino
        )
      )
      await(repository.collection.countDocuments().toFuture()) shouldBe 1
    }

    "retrieve partial auth which matches service, nino and arn" in {
      val nonMatchingEvent1 = partialAuth.copy(arn = notMatchingArn.value)
      val nonMatchingEvent2 = partialAuth.copy(service = MtdIt.id, nino = notMatchingNino.value)
      val listOfPartialAuths = Seq(partialAuth, nonMatchingEvent1, nonMatchingEvent2)
      await(repository.collection.insertMany(listOfPartialAuths).toFuture())

      await(repository.find(Vat, nino, arn)) shouldBe Some(partialAuth)
    }

    "fail to retrieve partial auths when no partial auths match the given service" in {
      val unrelatedEvent = partialAuth.copy(service = MtdIt.id)
      await(repository.collection.insertOne(unrelatedEvent).toFuture())
      await(repository.find(Vat, nino, arn)) shouldBe None
    }

    "fail to retrieve partial auths when no partial auths match the given nino" in {
      val unrelatedEvent = partialAuth.copy(nino = notMatchingNino.value)
      await(repository.collection.insertOne(unrelatedEvent).toFuture())
      await(repository.find(Vat, nino, arn)) shouldBe None
    }

    "fail to retrieve partial auths when no partial auths match the given arn" in {
      val unrelatedEvent = partialAuth.copy(arn = notMatchingArn.value)
      await(repository.collection.insertOne(unrelatedEvent).toFuture())
      await(repository.find(MtdIt, nino, arn)) shouldBe None
    }
  }

  "delete PartialAuth invitation success" in {
    await(
      repository.create(
        Instant.parse("2020-01-01T00:00:00.000Z"),
        arn,
        Vat,
        nino
      )
    )
    await(repository.collection.countDocuments().toFuture()) shouldBe 1
    await(repository.deletePartialAuth(Vat, nino, arn))
    await(repository.collection.countDocuments().toFuture()) shouldBe 1
  }

  "delete PartialAuth invitation return success even when initation do not exists" in {
    await(repository.collection.countDocuments().toFuture()) shouldBe 0
    await(repository.deletePartialAuth(Vat, nino, arn))
    await(repository.collection.countDocuments().toFuture()) shouldBe 0
  }

}
