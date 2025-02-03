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
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.{Accepted, DeAuthorised, Expired, Invitation, Pending, Rejected}
import uk.gov.hmrc.agentclientrelationships.support.MongoApp
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP, HMRCPIR, Vat}
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
    Some("personal"),
    LocalDate.parse("2020-01-01"),
    Instant.now().truncatedTo(ChronoUnit.SECONDS),
    Instant.now().truncatedTo(ChronoUnit.SECONDS)
  )

  "InvitationsRepository" should {

    "have a TTL index of 30 days" in {
      val ttlIndex = repository.indexes.find(_.getKeys.asInstanceOf[BsonDocument].containsKey("created")).get

      ttlIndex.getOptions.getName shouldBe "timeToLive"
      ttlIndex.getOptions.getExpireAfter(DAYS) shouldBe 30
    }

    "have a custom index for the arn field" in {
      val arnIndex = repository.indexes.find(_.getKeys.asInstanceOf[BsonDocument].containsKey("arn")).get

      arnIndex.getKeys shouldBe Indexes.ascending("arn")
      arnIndex.getOptions.getName shouldBe "arnIndex"
      arnIndex.getOptions.isUnique shouldBe false
    }

    "have a custom index for the invitationId field" in {
      val invitationIdIndex =
        repository.indexes.find(_.getKeys.asInstanceOf[BsonDocument].containsKey("invitationId")).get

      invitationIdIndex.getKeys shouldBe Indexes.ascending("invitationId")
      invitationIdIndex.getOptions.getName shouldBe "invitationIdIndex"
      invitationIdIndex.getOptions.isUnique shouldBe true
    }

    "create a new invitation record" in {
      await(
        repository.create(
          "XARN1234567",
          Vat,
          Vrn("123456789"),
          Vrn("234567890"),
          "Macrosoft",
          LocalDate.parse("2020-01-01"),
          Some("personal")
        )
      )
      await(repository.collection.countDocuments().toFuture()) shouldBe 1
    }

    "retrieve all existing invitations for a given ARN" in {
      val listOfInvitations = Seq(pendingInvitation, pendingInvitation, pendingInvitation)
      await(repository.collection.insertMany(listOfInvitations).toFuture())

      await(repository.findAllForAgent("XARN1234567")) shouldBe listOfInvitations
    }

    "retrieve all existing invitations for a given ARN, filtered on the target services and clientIds" in {
      val listOfInvitations = Seq(pendingInvitation, pendingInvitation, pendingInvitation)
      await(repository.collection.insertMany(listOfInvitations).toFuture())

      await(repository.findAllForAgent("XARN1234567", Seq(Vat.id), Seq("123456789"))) shouldBe listOfInvitations
    }

    "fail to retrieve invitations" when {

      "no invitations are found for a given ARN" in {
        await(repository.findAllForAgent("XARN1234567")) shouldBe Seq()
      }

      "no invitations are found for a given ARN, filtered on the target services and clientIds" in {
        await(repository.findAllForAgent("XARN1234567", Seq(Vat.id), Seq("123456789"))) shouldBe Seq()
      }

      "the filter for the service is not satisfied" in {
        val listOfInvitations = Seq(pendingInvitation, pendingInvitation, pendingInvitation)
        await(repository.collection.insertMany(listOfInvitations).toFuture())

        await(repository.findAllForAgent("XARN1234567", Seq("HMRC-XYZ"), Seq("123456789"))) shouldBe Seq()
      }

      "the filter for the clientId is not satisfied" in {
        val listOfInvitations = Seq(pendingInvitation, pendingInvitation, pendingInvitation)
        await(repository.collection.insertMany(listOfInvitations).toFuture())

        await(repository.findAllForAgent("XARN1234567", Seq(Vat.id), Seq("1"))) shouldBe Seq()
      }
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

    "update the status from Pending to Rejected of an invitation when a matching invitation is found" in {
      await(repository.collection.insertOne(pendingInvitation).toFuture())

      val updatedInvitation =
        await(repository.updateStatusFromTo(pendingInvitation.invitationId, Pending, Rejected)).get
      updatedInvitation.status shouldBe Rejected
      updatedInvitation.lastUpdated.isAfter(pendingInvitation.lastUpdated)
    }

    "update the status from Accepted to DeAuthorised with relationshipEndedBy of an invitation when a matching invitation is found" in {
      await(repository.collection.insertOne(pendingInvitation.copy(status = Accepted)).toFuture())

      val updatedInvitation =
        await(
          repository.updateStatusFromTo(
            invitationId = pendingInvitation.invitationId,
            fromStatus = Accepted,
            toStatus = DeAuthorised,
            relationshipEndedBy = Some("HMRC")
          )
        ).get
      updatedInvitation.relationshipEndedBy shouldBe Some("HMRC")
    }

    "fail to update fromTo the status of an invitation when a matching invitation is not found" in {
      await(repository.updateStatusFromTo("ABC", Pending, Rejected)) shouldBe None
    }

    "de-authorise Accepted invitation when a matching arn, service, suppliedClientId is found" in {
      await(repository.collection.insertOne(pendingInvitation.copy(status = Accepted)).toFuture())

      val updatedInvitation = await(
        repository
          .deauthorise(pendingInvitation.arn, pendingInvitation.suppliedClientId, pendingInvitation.service, "This guy")
      ).get
      updatedInvitation.status shouldBe DeAuthorised
      updatedInvitation.relationshipEndedBy shouldBe Some("This guy")
      updatedInvitation.lastUpdated.isAfter(pendingInvitation.lastUpdated)
    }

    "de-authorise DeAuthorised invitation when a matching arn, service, suppliedClientId is not found" in {
      await(repository.collection.insertOne(pendingInvitation.copy(status = DeAuthorised)).toFuture())

      await(
        repository.deauthorise(pendingInvitation.arn, pendingInvitation.clientId, pendingInvitation.service, "This guy")
      ) shouldBe None
    }

    "update a client ID and client ID type when a matching invitation is found" in {
      await(repository.collection.insertOne(pendingInvitation).toFuture())
      await(
        repository.updateClientIdAndType(pendingInvitation.clientId, pendingInvitation.clientIdType, "ABC", "ABCType")
      ) shouldBe true
      val invitation = await(repository.findOneById(pendingInvitation.invitationId)).get
      invitation.clientId shouldBe "ABC"
      invitation.clientIdType shouldBe "ABCType"
      invitation.suppliedClientId shouldBe "ABC"
      invitation.suppliedClientIdType shouldBe "ABCType"
    }

    "fail to update a client ID and client ID type when no matching invitation is found" in {
      await(repository.collection.insertOne(pendingInvitation).toFuture())
      await(repository.updateClientIdAndType("XYZ", "XYZType", "ABC", "ABCType")) shouldBe false
    }

    "retrieve all pending invitations for client" in {
      val listOfInvitations = Seq(
        pendingInvitation,
        pendingInvitation.copy(invitationId = "234", suppliedClientId = "678", service = HMRCMTDIT),
        pendingInvitation.copy(invitationId = "345", suppliedClientId = "678", service = HMRCMTDITSUPP),
        pendingInvitation.copy(invitationId = "456", suppliedClientId = "678", service = HMRCMTDITSUPP, status = Expired),
        pendingInvitation.copy(invitationId = "567", suppliedClientId = "789", service = HMRCMTDITSUPP),
        pendingInvitation.copy(invitationId = "678", suppliedClientId = "678", service = HMRCPIR),
      )
      await(repository.collection.insertMany(listOfInvitations).toFuture())

      await(repository.findAllPendingForClient("678", Seq(HMRCMTDIT, HMRCMTDITSUPP))) shouldBe
        Seq(
          pendingInvitation.copy(invitationId = "234", suppliedClientId = "678", service = HMRCMTDIT),
          pendingInvitation.copy(invitationId = "345", suppliedClientId = "678", service = HMRCMTDITSUPP)
        )
    }
  }
}
