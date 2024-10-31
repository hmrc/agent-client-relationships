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
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.{Accepted, DeAuthorised, Invitation, Pending}
import uk.gov.hmrc.agentclientrelationships.support.MongoApp
import uk.gov.hmrc.agentmtdidentifiers.model.Service.Vat
import uk.gov.hmrc.agentmtdidentifiers.model.Vrn

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DAYS

class InvitationsRepositoryISpec extends AnyWordSpec with Matchers with MongoApp with GuiceOneAppPerSuite {

  val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val repository: InvitationsRepository = new InvitationsRepository(mongoComponent, appConfig)

  val pendingInvitation: Invitation = Invitation(
    "123",
    "XARN1234567",
    "HMRC-MTD-VAT",
    "123456789",
    "vrn",
    "234567890",
    "vrn",
    "Macrosoft",
    Pending,
    None,
    LocalDate.parse("2020-01-01"),
    Instant.now().truncatedTo(ChronoUnit.SECONDS),
    Instant.now().truncatedTo(ChronoUnit.SECONDS)
  )

  "InvitationsRepository" should {

    "have a TTL of 30 days" in {
      val ttlIndex = repository.indexes.find(_.getKeys.asInstanceOf[BsonDocument].containsKey("created")).get

      ttlIndex.getOptions.getName shouldBe "timeToLive"
      ttlIndex.getOptions.getExpireAfter(DAYS) shouldBe 30
    }

    "create a new invitation record" in {
      await(
        repository.create(
          "XARN1234567",
          Vat,
          Vrn("123456789"),
          Vrn("234567890"),
          "Macrosoft",
          LocalDate.parse("2020-01-01")
        )
      )
      await(repository.collection.countDocuments().toFuture()) shouldBe 1
    }

    "retrieve all existing invitations for a given ARN" in {
      val listOfInvitations = Seq(pendingInvitation, pendingInvitation, pendingInvitation)
      await(repository.collection.insertMany(listOfInvitations).toFuture())

      await(repository.findAllForAgent("XARN1234567")) shouldBe listOfInvitations
    }

    "fail to retrieve invitations when no invitations are found for a given ARN" in {
      await(repository.findAllForAgent("XARN1234567")) shouldBe Seq()
    }

    "update the status of an invitation when a matching invitation is found" in {
      await(repository.collection.insertOne(pendingInvitation).toFuture())

      val updatedInvitation = await(repository.updateStatus(pendingInvitation.invitationId, Accepted)).get
      updatedInvitation.status shouldBe Accepted
      updatedInvitation.lastUpdated.isAfter(pendingInvitation.lastUpdated)
    }

    "fail to update the status of an invitation when a matching invitation is not found" in {
      await(repository.updateStatus("ABC", Accepted)) shouldBe None
    }

    "de-authorise an invitation when a matching invitation is found" in {
      await(repository.collection.insertOne(pendingInvitation).toFuture())

      val updatedInvitation = await(repository.deauthorise(pendingInvitation.invitationId, "This guy")).get
      updatedInvitation.status shouldBe DeAuthorised
      updatedInvitation.relationshipEndedBy shouldBe Some("This guy")
      updatedInvitation.lastUpdated.isAfter(pendingInvitation.lastUpdated)
    }

    "fail to de-authorise an invitation when a matching invitation is not found" in {
      await(repository.deauthorise("ABC", "This guy")) shouldBe None
    }
  }
}
