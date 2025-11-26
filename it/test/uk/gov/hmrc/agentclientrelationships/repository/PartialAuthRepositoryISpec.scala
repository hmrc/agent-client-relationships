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
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.await
import play.api.test.Helpers.defaultAwaitTimeout
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthRelationship
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.support.RepositoryCleanupSupport
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.Instant
import scala.concurrent.ExecutionContext

class PartialAuthRepositoryISpec
extends AnyWordSpec
with Matchers
with GuiceOneAppPerSuite
with DefaultPlayMongoRepositorySupport[PartialAuthRelationship]
with RepositoryCleanupSupport {

  override lazy val app: Application = new GuiceApplicationBuilder()
    .configure("mongodb.uri" -> mongoUri, "fieldLevelEncryption.enable" -> true)
    .build()

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val repository: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]

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

      val customIndexes = repository.indexes.toArray

      val allRecordsIndex = customIndexes(0)
      val activeRecordsIndex = customIndexes(1)
      val ninoIndex = customIndexes(2)

      allRecordsIndex.getKeys shouldBe Indexes.ascending(
        "service",
        "nino",
        "arn",
        "active"
      )
      allRecordsIndex.getOptions.isUnique shouldBe false
      allRecordsIndex.getOptions.getName shouldBe "allRelationshipsIndex"
      activeRecordsIndex.getKeys shouldBe Indexes.ascending(
        "service",
        "nino",
        "arn"
      )
      activeRecordsIndex.getOptions.getName shouldBe "activeRelationshipsIndex"
      activeRecordsIndex.getOptions.getPartialFilterExpression.toBsonDocument shouldBe BsonDocument("active" -> true)
      activeRecordsIndex.getOptions.isUnique shouldBe true
      ninoIndex.getKeys shouldBe Indexes.ascending("nino")
      ninoIndex.getOptions.getName shouldBe "ninoIndex"

    }

    "insert a new partial auth record" in {
      await(
        repository
          .create(
            Instant.parse("2020-01-01T00:00:00.000Z"),
            Arn("XARN1234567"),
            "HMRC-MTD-IT",
            Nino("SX579189D")
          )
      )
      await(repository.collection.countDocuments().toFuture()) shouldBe 1
    }

    "throw an exception if invalid service passed in" in {
      an[IllegalArgumentException] shouldBe thrownBy(
        repository
          .create(
            Instant.parse("2020-01-01T00:00:00.000Z"),
            Arn("XARN1234567"),
            "HMRC-MTD-VAT",
            Nino("SX579189D")
          )
      )
    }

    "throw an exception if duplicate active record is passed in" in {

      await(repository.collection.insertOne(partialAuth).toFuture())

      an[MongoWriteException] shouldBe thrownBy(
        await(
          repository
            .create(
              Instant.parse("2020-01-01T00:00:00.000Z"),
              Arn("XARN1234567"),
              "HMRC-MTD-IT",
              Nino("SX579189D")
            )
        )
      )
    }
  }

  "partialAuthRepository.findActive" should {

    "retrieve partial auth which matches service, nino and arn" in {
      val nonMatchingEvent1 = partialAuth.copy(arn = "ARN1234567")
      val nonMatchingEvent2 = partialAuth.copy(service = "HMRC-MTD-IT-SUPP", nino = "AB539803A")
      val listOfPartialAuths = Seq(
        partialAuth,
        nonMatchingEvent1,
        nonMatchingEvent2
      )
      await(repository.collection.insertMany(listOfPartialAuths).toFuture())
      await(
        repository.findActive(
          "HMRC-MTD-IT",
          Nino("SX579189D"),
          Arn("XARN1234567")
        )
      ) shouldBe Some(partialAuth)
    }

    "fail to retrieve partial auths when no partial auths match the given service" in {
      val unrelatedEvent = partialAuth.copy(service = "HMRC-MTD-IT-SUPP")
      await(repository.collection.insertOne(unrelatedEvent).toFuture())
      await(
        repository.findActive(
          "HMRC-MTD-IT",
          Nino("SX579189D"),
          Arn("XARN1234567")
        )
      ) shouldBe None
    }

    "fail to retrieve partial auths when no partial auths match the given nino" in {
      val unrelatedEvent = partialAuth.copy(nino = "AB539803A")
      await(repository.collection.insertOne(unrelatedEvent).toFuture())
      await(
        repository.findActive(
          "HMRC-MTD-IT",
          Nino("SX579189D"),
          Arn("XARN1234567")
        )
      ) shouldBe None
    }

    "fail to retrieve partial auths when no partial auths match the given arn" in {
      val unrelatedEvent = partialAuth.copy(arn = "XARN7654321")
      await(repository.collection.insertOne(unrelatedEvent).toFuture())
      await(
        repository.findActive(
          "HMRC-MTD-IT",
          Nino("SX579189D"),
          Arn("XARN1234567")
        )
      ) shouldBe None
    }
  }

  ".findByNino" should {

    "retrieves records for a given nino" in {
      await(repository.collection.insertOne(partialAuth).toFuture())
      await(repository.findByNino(Nino("SX579189D"))) shouldBe Seq(partialAuth)
    }

    "fail to retrieve records when none are found for the given nino" in {
      await(repository.findByNino(Nino("AA111111A"))) shouldBe Seq.empty
    }
  }

  "deauthorise" should {
    "deauthorise PartialAuth invitation success" in {
      await(
        repository
          .create(
            Instant.parse("2020-01-01T00:00:00.000Z"),
            Arn("XARN1234567"),
            "HMRC-MTD-IT",
            Nino("SX579189D")
          )
      )
      await(repository.collection.countDocuments().toFuture()) shouldBe 1
      await(
        repository
          .deauthorise(
            "HMRC-MTD-IT",
            Nino("SX579189D"),
            Arn("XARN1234567"),
            Instant.parse("2020-01-01T00:00:00.000Z")
          )
      )
      val result = await(
        repository.findActive(
          "HMRC-MTD-IT",
          Nino("SX579189D"),
          Arn("XARN1234567")
        )
      )
      result.isEmpty shouldBe true
      await(repository.collection.countDocuments().toFuture()) shouldBe 1
    }

    "deauthorise PartialAuth invitation return success even when invitation does not exist" in {
      await(repository.collection.countDocuments().toFuture()) shouldBe 0
      await(
        repository
          .deauthorise(
            "HMRC-MTD-VAT",
            Nino("SX579189D"),
            Arn("XARN1234567"),
            Instant.parse("2020-01-01T00:00:00.000Z")
          )
      )
      await(repository.collection.countDocuments().toFuture()) shouldBe 0
    }
  }

  "deleteActivePartialAuth" should {
    "delete when active record exists" in {
      await(repository.collection.insertOne(partialAuth).toFuture())

      val result = await(
        repository.deleteActivePartialAuth(
          "HMRC-MTD-IT",
          Nino("SX579189D"),
          Arn("XARN1234567")
        )
      )

      result shouldBe true
    }
  }

}
