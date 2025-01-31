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

import org.mongodb.scala.MongoWriteException
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Indexes
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthRelationship
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.Instant
import scala.concurrent.ExecutionContext

class PartialAuthRepositoryISpec
    extends AnyWordSpec
    with Matchers
    with GuiceOneAppPerSuite
    with DefaultPlayMongoRepositorySupport[PartialAuthRelationship] {

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  override protected lazy val repository: PlayMongoRepository[PartialAuthRelationship] = new PartialAuthRepository(
    mongoComponent
  )

  val repo: PartialAuthRepository = repository.asInstanceOf[PartialAuthRepository]

  val partialAuth: PartialAuthRelationship = PartialAuthRelationship(
    Instant.parse("2020-02-02T00:00:00.000Z"),
    "XARN1234567",
    "HMRC-MTD-IT",
    "SX579189D",
    active = true,
    Instant.parse("2020-02-02T00:00:00.000Z")
  )

  "partialAuthRepository.create" should {

    "have a custom compound index for the service, nino and arn fields" in {

      val customIndexes = repo.indexes.toArray

      val allRecordsIndex = customIndexes(0)
      val activeRecordsIndex = customIndexes(1)

      allRecordsIndex.getKeys shouldBe Indexes.ascending("service", "nino", "arn", "active")
      allRecordsIndex.getOptions.isUnique shouldBe false
      activeRecordsIndex.getKeys shouldBe Indexes.ascending("service", "nino", "arn")
      activeRecordsIndex.getOptions.getName shouldBe "activeRelationshipsIndex"
      activeRecordsIndex.getOptions.getPartialFilterExpression.toBsonDocument shouldBe BsonDocument("active" -> true)
      activeRecordsIndex.getOptions.isUnique shouldBe true

    }

    "insert a new partial auth record" in {
      await(
        repo.create(
          Instant.parse("2020-01-01T00:00:00.000Z"),
          Arn("XARN1234567"),
          "HMRC-MTD-IT",
          Nino("SX579189D")
        )
      )
      await(repo.collection.countDocuments().toFuture()) shouldBe 1
    }

    "throw an exception if invalid service passed in" in {
      an[IllegalArgumentException] shouldBe thrownBy(
        repo.create(
          Instant.parse("2020-01-01T00:00:00.000Z"),
          Arn("XARN1234567"),
          "HMRC-MTD-VAT",
          Nino("SX579189D")
        )
      )
    }

    "throw an exception if duplicate active record is passed in" in {

      await(repo.collection.insertOne(partialAuth).toFuture())

      an[MongoWriteException] shouldBe thrownBy(
        await(
          repo
            .create(Instant.parse("2020-01-01T00:00:00.000Z"), Arn("XARN1234567"), "HMRC-MTD-IT", Nino("SX579189D"))
        )
      )
    }
  }

  "partialAuthRepository.findActive" should {

    "retrieve partial auth which matches service, nino and arn" in {
      val nonMatchingEvent1 = partialAuth.copy(arn = "ARN1234567")
      val nonMatchingEvent2 = partialAuth.copy(service = "HMRC-MTD-IT-SUPP", nino = "AB539803A")
      val listOfPartialAuths = Seq(partialAuth, nonMatchingEvent1, nonMatchingEvent2)
      await(repo.collection.insertMany(listOfPartialAuths).toFuture())
      await(repo.findActive("HMRC-MTD-IT", Nino("SX579189D"), Arn("XARN1234567"))) shouldBe Some(partialAuth)
    }

    "fail to retrieve partial auths when no partial auths match the given service" in {
      val unrelatedEvent = partialAuth.copy(service = "HMRC-MTD-IT-SUPP")
      await(repo.collection.insertOne(unrelatedEvent).toFuture())
      await(repo.findActive("HMRC-MTD-IT", Nino("SX579189D"), Arn("XARN1234567"))) shouldBe None
    }

    "fail to retrieve partial auths when no partial auths match the given nino" in {
      val unrelatedEvent = partialAuth.copy(nino = "AB539803A")
      await(repo.collection.insertOne(unrelatedEvent).toFuture())
      await(repo.findActive("HMRC-MTD-IT", Nino("SX579189D"), Arn("XARN1234567"))) shouldBe None
    }

    "fail to retrieve partial auths when no partial auths match the given arn" in {
      val unrelatedEvent = partialAuth.copy(arn = "XARN7654321")
      await(repo.collection.insertOne(unrelatedEvent).toFuture())
      await(repo.findActive("HMRC-MTD-IT", Nino("SX579189D"), Arn("XARN1234567"))) shouldBe None
    }
  }

  ".findByNino" should {

    "retrieve a record for a given nino" in {
      await(repo.collection.insertOne(partialAuth).toFuture())
      await(repo.findByNino(Nino("SX579189D"))) shouldBe Some(partialAuth)
    }

    "fail to retrieve records when none are found for the given nino" in {
      await(repo.findByNino(Nino("AA111111A"))) shouldBe None
    }
  }

  "deauthorise" should {
    "deauthorise PartialAuth invitation success" in {
      await(
        repo.create(
          Instant.parse("2020-01-01T00:00:00.000Z"),
          Arn("XARN1234567"),
          "HMRC-MTD-IT",
          Nino("SX579189D")
        )
      )
      await(repository.collection.countDocuments().toFuture()) shouldBe 1
      await(
        repo
          .deauthorise("HMRC-MTD-IT", Nino("SX579189D"), Arn("XARN1234567"), Instant.parse("2020-01-01T00:00:00.000Z"))
      )
      val result = await(
        repo.findActive(
          "HMRC-MTD-IT",
          Nino("SX579189D"),
          Arn("XARN1234567")
        )
      )
      result.isEmpty shouldBe true
      await(repo.collection.countDocuments().toFuture()) shouldBe 1
    }

    "deauthorise PartialAuth invitation return success even when invitation does not exist" in {
      await(repository.collection.countDocuments().toFuture()) shouldBe 0
      await(
        repo
          .deauthorise("HMRC-MTD-VAT", Nino("SX579189D"), Arn("XARN1234567"), Instant.parse("2020-01-01T00:00:00.000Z"))
      )
      await(repo.collection.countDocuments().toFuture()) shouldBe 0
    }
  }

  "deleteActivePartialAuth" should {
    "delete when active record exists" in {
      await(repo.collection.insertOne(partialAuth).toFuture())

      val result = await(repo.deleteActivePartialAuth("HMRC-MTD-IT", Nino("SX579189D"), Arn("XARN1234567")))

      result shouldBe true
    }
  }
}
