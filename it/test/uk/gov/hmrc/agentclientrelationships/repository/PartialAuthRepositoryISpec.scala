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
import uk.gov.hmrc.agentclientrelationships.model.{Accepted, DeAuthorised, InvitationEvent}
import uk.gov.hmrc.agentclientrelationships.support.MongoApp
import uk.gov.hmrc.agentmtdidentifiers.model.Service.Vat
import uk.gov.hmrc.agentmtdidentifiers.model.Vrn

import java.time.Instant
import scala.concurrent.ExecutionContext

class PartialAuthRepositoryISpec extends AnyWordSpec with Matchers with MongoApp with GuiceOneAppPerSuite {

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val repository: PartialAuthRepository = new PartialAuthRepository(mongoComponent)

  val invitationEvent: InvitationEvent = InvitationEvent(
    Accepted,
    Instant.parse("2020-02-02T00:00:00.000Z"),
    "XARN1234567",
    "HMRC-MTD-VAT",
    "123456789",
    None
  )

  "InvitationsEventStoreRepository" should {

    "have a custom compound index for the service and clientId fields" in {
      val customIndex = repository.indexes.find(_.getKeys.asInstanceOf[BsonDocument].containsKey("service")).get

      customIndex.getKeys shouldBe Indexes.ascending("service", "clientId")
      customIndex.getOptions.getName shouldBe "clientQueryIndex"
      customIndex.getOptions.isUnique shouldBe false
    }

    "create a new invitation event record" in {
      await(
        repository.create(
          Accepted,
          Instant.parse("2020-01-01T00:00:00.000Z"),
          "XARN1234567",
          Vat,
          Vrn("123456789"),
          None
        )
      )
      await(repository.collection.countDocuments().toFuture()) shouldBe 1
    }

    "retrieve all existing invitation events for a matching service and client ID" in {
      val relatedEvent = invitationEvent.copy(status = DeAuthorised, deauthorisedBy = Some("Me"))
      val unrelatedEvent = invitationEvent.copy(service = "HMRC-MTD-IT", clientId = "XAIT0000111122")
      val listOfInvitationEvents = Seq(invitationEvent, relatedEvent, unrelatedEvent)
      await(repository.collection.insertMany(listOfInvitationEvents).toFuture())

      await(repository.findAllForClient(Vat, Vrn("123456789"))) shouldBe Seq(invitationEvent, relatedEvent)
    }

    "fail to retrieve invitations when no invitations match the given service" in {
      val unrelatedEvent = invitationEvent.copy(service = "HMRC-MTD-IT")
      await(repository.collection.insertOne(unrelatedEvent).toFuture())
      await(repository.findAllForClient(Vat, Vrn("123456789"))) shouldBe Seq()
    }

    "fail to retrieve invitations when no invitations match the given clientId" in {
      val unrelatedEvent = invitationEvent.copy(clientId = "234567890")
      await(repository.collection.insertOne(unrelatedEvent).toFuture())
      await(repository.findAllForClient(Vat, Vrn("123456789"))) shouldBe Seq()
    }
  }
}
