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
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP, HMRCPIR, Vat}
import uk.gov.hmrc.agentmtdidentifiers.model.Vrn
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DAYS
import scala.util.Random

class InvitationsRepositoryISpec
    extends AnyWordSpec
    with Matchers
    with GuiceOneAppPerSuite
    with DefaultPlayMongoRepositorySupport[Invitation] {

  override lazy val app: Application =
    new GuiceApplicationBuilder()
      .configure("mongodb.uri" -> mongoUri, "fieldLevelEncryption.enable" -> true)
      .build()

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val repository: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]

  def pendingInvitation: Invitation = Invitation
    .createNew(
      "XARN1234567",
      Vat,
      Vrn("123456789"),
      Vrn("234567890"),
      "Macrosoft",
      "testAgentName",
      "agent@email.com",
      LocalDate.of(2020, 1, 1),
      Some("personal")
    )
    .copy(
      invitationId = Random.between(100000, 999999).toString, // making sure duplicates aren't generated
      created = Instant.now().truncatedTo(ChronoUnit.SECONDS),
      lastUpdated = Instant.now().truncatedTo(ChronoUnit.SECONDS)
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
          "testAgentName",
          "agent@email.com",
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
        await(repository.collection.insertOne(pendingInvitation).toFuture())

        await(repository.findAllForAgent("XARN1234567", Seq("HMRC-XYZ"), Seq("123456789"))) shouldBe Seq()
      }

      "the filter for the clientId is not satisfied" in {
        await(repository.collection.insertOne(pendingInvitation).toFuture())

        await(repository.findAllForAgent("XARN1234567", Seq(Vat.id), Seq("1"))) shouldBe Seq()
      }
    }

    "update the status of an invitation when a matching invitation is found" in {
      val invitation = pendingInvitation
      await(repository.collection.insertOne(invitation).toFuture())

      lazy val updatedInvitation = await(repository.updateStatus(invitation.invitationId, Accepted)).get
      updatedInvitation.status shouldBe Accepted
      updatedInvitation.lastUpdated.isAfter(invitation.lastUpdated)
    }

    "fail to update the status of an invitation when a matching invitation is not found" in {
      await(repository.updateStatus("ABC", Accepted)) shouldBe None
    }

    "update the status from Pending to Rejected of an invitation when a matching invitation is found" in {
      val invitation = pendingInvitation
      await(repository.collection.insertOne(invitation).toFuture())

      lazy val updatedInvitation =
        await(repository.updateStatusFromTo(invitation.invitationId, Pending, Rejected)).get
      updatedInvitation.status shouldBe Rejected
      updatedInvitation.lastUpdated.isAfter(invitation.lastUpdated)
    }

    "update the status from Accepted to DeAuthorised with relationshipEndedBy of an invitation when a matching invitation is found" in {
      val invitation = pendingInvitation.copy(status = Accepted)
      await(repository.collection.insertOne(invitation).toFuture())

      lazy val updatedInvitation =
        await(
          repository.updateStatusFromTo(
            invitationId = invitation.invitationId,
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

      lazy val updatedInvitation = await(
        repository
          .deauthorise(
            pendingInvitation.arn,
            pendingInvitation.suppliedClientId,
            pendingInvitation.service,
            "This guy"
          )
      ).get
      updatedInvitation.status shouldBe DeAuthorised
      updatedInvitation.relationshipEndedBy shouldBe Some("This guy")
      updatedInvitation.lastUpdated.isAfter(pendingInvitation.lastUpdated)
    }

    "de-authorise DeAuthorised invitation when a matching arn, service, suppliedClientId is not found" in {
      await(repository.collection.insertOne(pendingInvitation.copy(status = DeAuthorised)).toFuture())

      await(
        repository
          .deauthorise(pendingInvitation.arn, pendingInvitation.clientId, pendingInvitation.service, "This guy")
      ) shouldBe None
    }

    "update a client ID and client ID type when a matching invitation is found" in {
      val invitation = pendingInvitation
      await(repository.collection.insertOne(invitation).toFuture())
      await(
        repository
          .updateInvitation(
            invitation.service,
            invitation.clientId,
            invitation.clientIdType,
            invitation.service,
            "ABC",
            "ABCType"
          )
      ) shouldBe true
      lazy val updatedInvitation = await(repository.findOneById(invitation.invitationId)).get
      updatedInvitation.clientId shouldBe "ABC"
      updatedInvitation.clientIdType shouldBe "ABCType"
      updatedInvitation.suppliedClientId shouldBe "ABC"
      updatedInvitation.suppliedClientIdType shouldBe "ABCType"
    }

    "fail to update a client ID and client ID type when no matching invitation is found" in {
      await(repository.collection.insertOne(pendingInvitation).toFuture())
      await(repository.updateInvitation("MTD-IT-ID", "XYZ", "XYZType", "MTD-IT-ID", "ABC", "ABCType")) shouldBe false
    }

    "retrieve all pending invitations for client" in {
      val itsaInv = pendingInvitation.copy(suppliedClientId = "678", service = HMRCMTDIT)
      val itsaSuppInv = pendingInvitation.copy(suppliedClientId = "678", service = HMRCMTDITSUPP)
      val otherClientItsaSuppInv = pendingInvitation.copy(suppliedClientId = "789", service = HMRCMTDITSUPP)
      val expiredInv = pendingInvitation.copy(suppliedClientId = "678", service = HMRCMTDITSUPP, status = Expired)
      val irvInv = pendingInvitation.copy(suppliedClientId = "678", service = HMRCPIR)
      val listOfInvitations = Seq(itsaInv, itsaSuppInv, otherClientItsaSuppInv, expiredInv, irvInv)

      await(repository.collection.insertMany(listOfInvitations).toFuture())

      await(repository.findAllPendingForClient("678", Seq(HMRCMTDIT, HMRCMTDITSUPP))) shouldBe Seq(itsaInv, itsaSuppInv)
    }

    "produce a TrackRequestsResult with the correct pagination" in {
      val listOfInvitations = Seq(
        pendingInvitation,
        pendingInvitation.copy(invitationId = "234", suppliedClientId = "678", service = HMRCMTDIT),
        pendingInvitation.copy(invitationId = "345", suppliedClientId = "678", service = HMRCMTDITSUPP),
        pendingInvitation
          .copy(invitationId = "456", suppliedClientId = "678", service = HMRCMTDITSUPP, status = Expired),
        pendingInvitation.copy(invitationId = "567", suppliedClientId = "789", service = HMRCMTDITSUPP),
        pendingInvitation.copy(invitationId = "678", suppliedClientId = "678", service = HMRCPIR)
      )
      await(repository.collection.insertMany(listOfInvitations).toFuture())

      val result = await(repository.trackRequests("XARN1234567", None, None, 1, 10))
      result.totalResults shouldBe 6
      result.pageNumber shouldBe 1
      result.requests shouldBe listOfInvitations
      result.clientNames shouldBe listOfInvitations.map(_.clientName).distinct.sorted
      result.availableFilters shouldBe listOfInvitations.map(i => i.status.toString).distinct.sorted
    }
  }
}
