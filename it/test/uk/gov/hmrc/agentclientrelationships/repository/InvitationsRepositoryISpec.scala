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

import com.mongodb.MongoWriteException
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Indexes
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.await
import play.api.test.Helpers.defaultAwaitTimeout
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDIT
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDVAT
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCPIR
import uk.gov.hmrc.agentmtdidentifiers.model.Service.MtdIt
import uk.gov.hmrc.agentmtdidentifiers.model.Service.Vat
import uk.gov.hmrc.agentmtdidentifiers.model.MtdItId
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.agentmtdidentifiers.model.Vrn
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.temporal.ChronoUnit
import java.time.Instant
import java.time.LocalDate
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DAYS
import scala.util.Random

class InvitationsRepositoryISpec
extends AnyWordSpec
with Matchers
with GuiceOneAppPerSuite
with DefaultPlayMongoRepositorySupport[Invitation] {

  override lazy val app: Application = new GuiceApplicationBuilder()
    .configure("mongodb.uri" -> mongoUri, "fieldLevelEncryption.enable" -> true)
    .build()

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val repository: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]

  def pendingInvitation(
    service: Service = Vat,
    clientId: TaxIdentifier = Vrn("123456789"),
    suppliedClientId: Option[TaxIdentifier] = None
  ): Invitation = Invitation
    .createNew(
      "XARN1234567",
      service,
      clientId,
      suppliedClientId.getOrElse(clientId),
      "Macrosoft",
      "testAgentName",
      "agent@email.com",
      LocalDate.of(
        2020,
        1,
        1
      ),
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
      val invitationIdIndex = repository.indexes.find(_.getKeys.asInstanceOf[BsonDocument].containsKey("invitationId")).get

      invitationIdIndex.getKeys shouldBe Indexes.ascending("invitationId")
      invitationIdIndex.getOptions.getName shouldBe "invitationIdIndex"
      invitationIdIndex.getOptions.isUnique shouldBe true
    }

    "create a new invitation record when one non-pending exists for the same agent and suppliedClientId" in {
      await(repository.collection.insertOne(pendingInvitation().copy(status = DeAuthorised)).toFuture())
      await(
        repository.create(
          "XARN1234567",
          Vat,
          Vrn("123456789"),
          Vrn("123456789"),
          "Macrosoft",
          "testAgentName",
          "agent@email.com",
          LocalDate.parse("2020-01-01"),
          Some("personal")
        )
      )
      await(repository.collection.countDocuments().toFuture()) shouldBe 2
    }

    "fail to create a Pending invitation when one exists for the same agent and suppliedClientId" in {
      await(repository.collection.insertOne(pendingInvitation()).toFuture())
      intercept[MongoWriteException](
        await(
          repository.create(
            "XARN1234567",
            Vat,
            Vrn("123456789"),
            Vrn("123456789"),
            "Macrosoft",
            "testAgentName",
            "agent@email.com",
            LocalDate.parse("2020-01-01"),
            Some("personal")
          )
        )
      )
    }

    "retrieve all existing invitations for a given ARN" in {
      val listOfInvitations = Seq(
        pendingInvitation(clientId = Vrn("123456789")),
        pendingInvitation(clientId = Vrn("123456788")),
        pendingInvitation(clientId = Vrn("123456787"))
      )
      await(repository.collection.insertMany(listOfInvitations).toFuture())

      await(repository.findAllForAgent("XARN1234567")) shouldBe listOfInvitations
    }

    "retrieve all existing invitations for a given ARN, filtered on the target services and clientIds" in {
      val listOfInvitations = Seq(
        pendingInvitation(clientId = Vrn("123456789")),
        pendingInvitation(clientId = Vrn("123456788")),
        pendingInvitation(clientId = Vrn("123456787"))
      )
      await(repository.collection.insertMany(listOfInvitations).toFuture())

      await(
        repository.findAllForAgent(
          "XARN1234567",
          Seq(Vat.id),
          Seq(
            "123456789",
            "123456788",
            "123456787"
          )
        )
      ) shouldBe listOfInvitations
    }

    "fail to retrieve invitations" when {

      "no invitations are found for a given ARN" in {
        await(repository.findAllForAgent("XARN1234567")) shouldBe Seq()
      }

      "no invitations are found for a given ARN, filtered on the target services and clientIds" in {
        await(
          repository.findAllForAgent(
            "XARN1234567",
            Seq(Vat.id),
            Seq("123456789")
          )
        ) shouldBe Seq()
      }

      "the filter for the service is not satisfied" in {
        await(repository.collection.insertOne(pendingInvitation()).toFuture())

        await(
          repository.findAllForAgent(
            "XARN1234567",
            Seq("HMRC-XYZ"),
            Seq("123456789")
          )
        ) shouldBe Seq()
      }

      "the filter for the clientId is not satisfied" in {
        await(repository.collection.insertOne(pendingInvitation()).toFuture())

        await(
          repository.findAllForAgent(
            "XARN1234567",
            Seq(Vat.id),
            Seq("1")
          )
        ) shouldBe Seq()
      }
    }

    "update the status of an invitation when a matching invitation is found" in {
      val invitation = pendingInvitation()
      await(repository.collection.insertOne(invitation).toFuture())

      lazy val updatedInvitation = await(repository.updateStatus(invitation.invitationId, Accepted))
      updatedInvitation.status shouldBe Accepted
      updatedInvitation.lastUpdated.isAfter(invitation.lastUpdated)
    }

    "fail to update the status of an invitation when a matching invitation is not found" in {
      intercept[RuntimeException](await(repository.updateStatus("ABC", Accepted)))
    }

    "update the status from Pending to Rejected of an invitation when a matching invitation is found" in {
      val invitation = pendingInvitation()
      await(repository.collection.insertOne(invitation).toFuture())

      lazy val updatedInvitation =
        await(
          repository.updateStatusFromTo(
            invitation.invitationId,
            Pending,
            Rejected
          )
        ).get
      updatedInvitation.status shouldBe Rejected
      updatedInvitation.lastUpdated.isAfter(invitation.lastUpdated)
    }

    "update the status from Accepted to DeAuthorised with relationshipEndedBy of an invitation when a matching invitation is found" in {
      val invitation = pendingInvitation().copy(status = Accepted)
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
      await(
        repository.updateStatusFromTo(
          "ABC",
          Pending,
          Rejected
        )
      ) shouldBe None
    }

    "de-authorise and return the invitation when a matching Authorised invitation is found" in {
      val invitation = pendingInvitation(
        MtdIt,
        MtdItId("1234567890"),
        Some(Nino("AB213308A"))
      )
      await(repository.collection.insertOne(invitation.copy(status = Accepted)).toFuture())

      lazy val updatedInvitation =
        await(
          repository.deauthorise(
            invitation.arn,
            invitation.clientId,
            invitation.service,
            "This guy"
          )
        ).get
      updatedInvitation.status shouldBe DeAuthorised
      updatedInvitation.relationshipEndedBy shouldBe Some("This guy")
      updatedInvitation.lastUpdated.isAfter(pendingInvitation().lastUpdated)
    }

    "return None when a matching Authorised invitation is not found" in {
      val invitation = pendingInvitation(
        MtdIt,
        MtdItId("1234567890"),
        Some(Nino("AB213308A"))
      )
      await(repository.collection.insertOne(invitation.copy(status = DeAuthorised)).toFuture())

      await(
        repository.deauthorise(
          invitation.arn,
          invitation.clientId,
          invitation.service,
          "This guy"
        )
      ) shouldBe None
    }

    "update a client ID and client ID type when a matching invitation is found" in {
      val invitation = pendingInvitation()
      await(repository.collection.insertOne(invitation).toFuture())
      await(
        repository.updateInvitation(
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
      await(repository.collection.insertOne(pendingInvitation()).toFuture())
      await(
        repository.updateInvitation(
          "MTD-IT-ID",
          "XYZ",
          "XYZType",
          "MTD-IT-ID",
          "ABC",
          "ABCType"
        )
      ) shouldBe false
    }

    "retrieve all pending invitations for client" in {
      val itsaInv = pendingInvitation().copy(suppliedClientId = "678", service = HMRCMTDIT)
      val itsaSuppInv = pendingInvitation().copy(suppliedClientId = "678", service = HMRCMTDITSUPP)
      val otherClientItsaSuppInv = pendingInvitation().copy(suppliedClientId = "789", service = HMRCMTDITSUPP)
      val expiredInv = pendingInvitation().copy(
        suppliedClientId = "678",
        service = HMRCMTDITSUPP,
        status = Expired
      )
      val irvInv = pendingInvitation().copy(suppliedClientId = "678", service = HMRCPIR)
      val listOfInvitations = Seq(
        itsaInv,
        itsaSuppInv,
        otherClientItsaSuppInv,
        expiredInv,
        irvInv
      )

      await(repository.collection.insertMany(listOfInvitations).toFuture())

      await(repository.findAllPendingForSuppliedClient("678", Seq(HMRCMTDIT, HMRCMTDITSUPP))) shouldBe Seq(
        itsaInv,
        itsaSuppInv
      )
    }

    "produce a TrackRequestsResult with the correct pagination" in {
      val listOfInvitations = Seq(
        pendingInvitation(),
        pendingInvitation().copy(
          invitationId = "234",
          suppliedClientId = "678",
          service = HMRCMTDIT
        ),
        pendingInvitation().copy(
          invitationId = "345",
          suppliedClientId = "678",
          service = HMRCMTDITSUPP
        ),
        pendingInvitation()
          .copy(
            invitationId = "456",
            suppliedClientId = "678",
            service = HMRCMTDITSUPP,
            status = Expired
          ),
        pendingInvitation().copy(
          invitationId = "567",
          suppliedClientId = "789",
          service = HMRCMTDITSUPP
        ),
        pendingInvitation().copy(
          invitationId = "678",
          suppliedClientId = "678",
          service = HMRCPIR
        )
      )
      await(repository.collection.insertMany(listOfInvitations).toFuture())

      val result = await(
        repository.trackRequests(
          "XARN1234567",
          None,
          None,
          1,
          10
        )
      )
      result.totalResults shouldBe 6
      result.pageNumber shouldBe 1
      result.requests shouldBe listOfInvitations
      result.clientNames shouldBe listOfInvitations.map(_.clientName).distinct.sorted
      result.availableFilters shouldBe listOfInvitations.map(i => i.status.toString).distinct.sorted
    }

    "retrieve a WarningEmailAggregatingResult that" should {

      "meets the criteria for a warning email to be sent" in {
        val newInvitation = pendingInvitation().copy(expiryDate = LocalDate.now().plusDays(22L))
        val acceptedInvitation = newInvitation.copy(
          invitationId = "1",
          suppliedClientId = "1",
          status = Accepted,
          expiryDate = LocalDate.now().plusDays(5L)
        )
        val warningEmailSentInvitation = newInvitation.copy(
          invitationId = "2",
          suppliedClientId = "2",
          warningEmailSent = true,
          expiryDate = LocalDate.now().plusDays(5L)
        )
        val tooEarlyForWarningInvitation = newInvitation
          .copy(
            invitationId = "3",
            suppliedClientId = "3",
            expiryDate = LocalDate.now().plusDays(6L)
          )
        val tooLateForWarningInvitation = newInvitation
          .copy(
            invitationId = "4",
            suppliedClientId = "4",
            expiryDate = LocalDate.now().minusDays(1L)
          )
        val expectedInvitation = newInvitation
          .copy(
            invitationId = "5",
            suppliedClientId = "5",
            expiryDate = LocalDate.now().plusDays(5L)
          )

        val listOfInvitations = Seq(
          newInvitation,
          acceptedInvitation,
          warningEmailSentInvitation,
          tooEarlyForWarningInvitation,
          tooLateForWarningInvitation,
          expectedInvitation
        )

        await(repository.collection.insertMany(listOfInvitations).toFuture())

        val result = await(repository.findAllForWarningEmail.toFuture())
        val expectedResult = Seq(WarningEmailAggregationResult(expectedInvitation.arn, Seq(expectedInvitation)))

        result.size shouldBe 1
        result shouldBe expectedResult
      }

      "groups the results by ARN and their associated invitations" in {
        val firstArnInv1 = pendingInvitation().copy(expiryDate = LocalDate.now().plusDays(5L), invitationId = "1")
        val firstArnInv2 = firstArnInv1.copy(invitationId = "2", suppliedClientId = "2")
        val firstArnInv3 = firstArnInv1.copy(invitationId = "3", suppliedClientId = "3")
        val secondArnInv1 = firstArnInv1.copy(
          arn = "2",
          invitationId = "4",
          suppliedClientId = "4"
        )
        val secondArnInv2 = secondArnInv1.copy(invitationId = "5", suppliedClientId = "5")
        val thirdArnInv1 = firstArnInv1.copy(
          arn = "3",
          invitationId = "6",
          suppliedClientId = "6"
        )

        val listOfInvitations = Seq(
          firstArnInv1,
          firstArnInv2,
          firstArnInv3,
          secondArnInv1,
          secondArnInv2,
          thirdArnInv1
        )

        await(repository.collection.insertMany(listOfInvitations).toFuture())

        val result = await(repository.findAllForWarningEmail.toFuture())
        val sortedResults = result.sortBy(_.invitations.size)

        result.size shouldBe 3
        sortedResults.head.arn shouldBe thirdArnInv1.arn
        sortedResults(1).arn shouldBe secondArnInv2.arn
        sortedResults(2).arn shouldBe firstArnInv1.arn
        sortedResults.head.invitations.size shouldBe 1
        sortedResults(1).invitations.size shouldBe 2
        sortedResults(2).invitations.size shouldBe 3
      }
    }

    "retrieve invitations meeting the criteria for an expired email to be sent" in {
      val newInvitation = pendingInvitation().copy(expiryDate = LocalDate.now().plusDays(22L))
      val acceptedInvitation = newInvitation
        .copy(
          invitationId = "1",
          suppliedClientId = "1",
          status = Accepted,
          expiryDate = LocalDate.now().minusDays(1L)
        )
      val expiredEmailSentInvitation = newInvitation.copy(
        invitationId = "2",
        suppliedClientId = "2",
        expiredEmailSent = true,
        expiryDate = LocalDate.now().minusDays(1L)
      )
      val tooEarlyForExpiredInvitation = newInvitation
        .copy(
          invitationId = "3",
          suppliedClientId = "3",
          expiryDate = LocalDate.now().plusDays(1L)
        )
      val expectedInvitation = newInvitation
        .copy(
          invitationId = "4",
          suppliedClientId = "4",
          expiryDate = LocalDate.now().minusDays(1L)
        )

      val listOfInvitations = Seq(
        newInvitation,
        acceptedInvitation,
        expiredEmailSentInvitation,
        tooEarlyForExpiredInvitation,
        expectedInvitation
      )

      await(repository.collection.insertMany(listOfInvitations).toFuture())

      val result = await(repository.findAllForExpiredEmail.toFuture())

      result.length shouldBe 1
      result.head shouldBe expectedInvitation
    }

    "update an invitation to set the warningEmailSent flag to true" in {
      val invitation = pendingInvitation()
      await(repository.collection.insertOne(invitation).toFuture())
      val result = await(repository.updateWarningEmailSent(invitation.invitationId))
      val updatedInvitation = await(repository.findOneById(invitation.invitationId))

      result shouldBe true
      updatedInvitation.get.warningEmailSent shouldBe true
    }

    "update an invitation to set the expiredEmailSent flag to true" in {
      val invitation = pendingInvitation()
      await(repository.collection.insertOne(invitation).toFuture())
      val result = await(repository.updateExpiredEmailSent(invitation.invitationId))
      val updatedInvitation = await(repository.findOneById(invitation.invitationId))

      result shouldBe true
      updatedInvitation.get.expiredEmailSent shouldBe true
    }

    "return invitations matching the given criteria with the .findAllBy function" when {

      val relevantInvitation = pendingInvitation()
      val irrelevantInvitation = pendingInvitation()
        .copy(
          arn = "TARN7654321",
          service = HMRCMTDIT,
          clientId = "AABBCC12D",
          status = Accepted
        )

      "all parameters are provided" in {
        await(repository.collection.insertMany(Seq(relevantInvitation, irrelevantInvitation)).toFuture())
        val result = await(
          repository.findAllBy(
            arn = Some("XARN1234567"),
            services = Seq(HMRCMTDVAT),
            clientIds = Seq("123456789"),
            status = Some(Pending)
          )
        )

        result shouldBe Seq(relevantInvitation)
      }

      "only an ARN is provided" in {
        await(repository.collection.insertMany(Seq(relevantInvitation, irrelevantInvitation)).toFuture())
        val result = await(repository.findAllBy(arn = Some("XARN1234567")))

        result shouldBe Seq(relevantInvitation)
      }

      "only a list of clientIds is provided" in {
        await(repository.collection.insertMany(Seq(relevantInvitation, irrelevantInvitation)).toFuture())
        val result = await(repository.findAllBy(clientIds = Seq("123456789")))

        result shouldBe Seq(relevantInvitation)
      }

      "only a list of services is provided (does not query database)" in {
        await(repository.collection.insertMany(Seq(relevantInvitation, irrelevantInvitation)).toFuture())
        val result = await(repository.findAllBy(services = Seq(HMRCMTDVAT)))

        result shouldBe Nil
      }

      "only a status is provided (does not query database)" in {
        await(repository.collection.insertMany(Seq(relevantInvitation, irrelevantInvitation)).toFuture())
        val result = await(repository.findAllBy(status = Some(Pending)))

        result shouldBe Nil
      }

      "no parameters are provided (does not query database)" in {
        await(repository.collection.insertMany(Seq(relevantInvitation, irrelevantInvitation)).toFuture())
        val result = await(repository.findAllBy())

        result shouldBe Nil
      }
    }
  }

}
