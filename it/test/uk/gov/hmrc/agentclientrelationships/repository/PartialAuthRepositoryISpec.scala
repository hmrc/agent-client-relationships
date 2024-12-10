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
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthModel
import uk.gov.hmrc.agentclientrelationships.support.MongoApp
import uk.gov.hmrc.agentmtdidentifiers.model.Service.Vat

import java.time.Instant
import scala.concurrent.ExecutionContext

class PartialAuthRepositoryISpec extends AnyWordSpec with Matchers with MongoApp with GuiceOneAppPerSuite {

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val repository: PartialAuthRepository = new PartialAuthRepository(mongoComponent)

  val partialAuth: PartialAuthModel = PartialAuthModel(
    Instant.parse("2020-02-02T00:00:00.000Z"),
    "XARN1234567",
    "HMRC-MTD-VAT",
    "123456789"
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
          "XARN1234567",
          Vat,
          "123456789"
        )
      )
      await(repository.collection.countDocuments().toFuture()) shouldBe 1
    }

    "retrieve partial auth which matches service, nino and arn" in {
      val nonMatchingEvent1 = partialAuth.copy(arn = "ARN1234567")
      val nonMatchingEvent2 = partialAuth.copy(service = "HMRC-MTD-IT", nino = "XAIT0000111122")
      val listOfPartialAuths = Seq(partialAuth, nonMatchingEvent1, nonMatchingEvent2)
      await(repository.collection.insertMany(listOfPartialAuths).toFuture())

      await(repository.find("HMRC-MTD-VAT", "123456789", "XARN1234567")) shouldBe Some(partialAuth)
    }

    "fail to retrieve partial auths when no partial auths match the given service" in {
      val unrelatedEvent = partialAuth.copy(service = "HMRC-MTD-IT")
      await(repository.collection.insertOne(unrelatedEvent).toFuture())
      await(repository.find("HMRC-MTD-VAT", "123456789", "XARN1234567")) shouldBe None
    }

    "fail to retrieve partial auths when no partial auths match the given nino" in {
      val unrelatedEvent = partialAuth.copy(nino = "234567890")
      await(repository.collection.insertOne(unrelatedEvent).toFuture())
      await(repository.find("HMRC-MTD-VAT", "123456789", "XARN1234567")) shouldBe None
    }

    "fail to retrieve partial auths when no partial auths match the given arn" in {
      val unrelatedEvent = partialAuth.copy(arn = "XARN7654321")
      await(repository.collection.insertOne(unrelatedEvent).toFuture())
      await(repository.find("HMRC-MTD-VAT", "123456789", "XARN1234567")) shouldBe None
    }
  }
}
