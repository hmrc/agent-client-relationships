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

package uk.gov.hmrc.agentclientrelationships.controllers

import play.api.libs.json.Json
import play.api.libs.json.Json.toJson
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.ErrorBody
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.errorBodyWrites
import uk.gov.hmrc.agentclientrelationships.model.invitation.RemoveAuthorisationRequest
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.repository.DeleteRecord
import uk.gov.hmrc.agentclientrelationships.repository.DeleteRecordRepository
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus
import uk.gov.hmrc.agentclientrelationships.stubs.AfiRelationshipStub
import uk.gov.hmrc.agentclientrelationships.stubs.AucdStubs
import uk.gov.hmrc.agentclientrelationships.stubs.AuthStub
import uk.gov.hmrc.agentclientrelationships.stubs.ClientDetailsStub
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.agentclientrelationships.stubs.RelationshipStubs
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdIt
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.PersonalIncomeRecord
import uk.gov.hmrc.agentclientrelationships.testsupport.testdata.IrvTestData
import uk.gov.hmrc.agentclientrelationships.testsupport.testdata.ItsaSuppTestData
import uk.gov.hmrc.agentclientrelationships.testsupport.testdata.ItsaTestData
import uk.gov.hmrc.agentclientrelationships.testsupport.testdata.TestData
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.mongo.lock.Lock

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class RemoveAuthorisationControllerISpec
extends RelationshipsBaseControllerISpec
with HipStub
with ClientDetailsStub
with AfiRelationshipStub
with TestData {

  val controller: RemoveAuthorisationController = app.injector.instanceOf[RemoveAuthorisationController]

  val invitationRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val partialAuthRepository: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]
  val deleteRecordRepo: DeleteRecordRepository = app.injector.instanceOf[DeleteRecordRepository]

  val requestPath: String = s"/agent-client-relationships/agent/${TestData.arn.value}/remove-authorisation"

  TestData.services.foreach { regimeData =>
    s"POST /agent/:arn/remove-authorisations for ${regimeData.service.id} and ${regimeData.clientId.value}" when {

      def setupStandardRelationship(): Unit = {
        // Lookup clientId if different from suppliedClientId
        regimeData.clientIdLookupStubs()
        // Lookup for secondary enrolment identifier if needed
        regimeData.clientKnownFactCheckStubs()
        RelationshipStubs.givenPrincipalAgentUser(TestData.arn, TestData.groupId)

        RelationshipStubs.givenDelegatedGroupIdsExistFor(regimeData.enrolment, Set(TestData.groupId))
        RelationshipStubs.givenEnrolmentDeallocationSucceeds(TestData.groupId, regimeData.enrolment)

        HipStub.givenAgentCanBeDeallocated(regimeData.clientId, TestData.arn)
        AucdStubs.givenCacheRefresh(TestData.arn)
      }

      def acceptedInvitation: Invitation = Invitation
        .createNew(
          TestData.arn.value,
          regimeData.service,
          regimeData.suppliedClientId,
          "TestClientName",
          "testAgentName",
          "agent@email.com",
          LocalDate.now(),
          None
        )
        .copy(status = Accepted)

      "standard relationship exists and no deauth record exists" should {
        "return 204 when client deauthorises the relationship" in {
          // Authorised as client
          regimeData.clientAuthStubs()
          setupStandardRelationship()
          val invitation = acceptedInvitation
          invitationRepo.collection.insertOne(invitation).toFuture().futureValue

          doAgentPostRequest(
            requestPath,
            Json.toJson(RemoveAuthorisationRequest(clientId = regimeData.clientId.value, service = regimeData.service.id)).toString()
          ).status shouldBe 204

          verifyDeleteRecordNotExists
          eventually {
            invitationRepo.findOneById(invitation.invitationId).futureValue.get.status shouldBe DeAuthorised
          }
          verifyTerminateRelationshipAuditSent(
            requestPath,
            TestData.arn.value,
            regimeData.clientId.value,
            regimeData.service.supportedClientIdType.enrolmentId,
            regimeData.service.id,
            "ClientLedTermination"
          )
        }

        "return 204 when agent deauthorises the relationship" in {
          // Authorised as agent
          AuthStub.givenUserIsSubscribedAgent(TestData.arn)
          setupStandardRelationship()
          val invitation = acceptedInvitation
          invitationRepo.collection.insertOne(invitation).toFuture().futureValue

          doAgentPostRequest(
            requestPath,
            Json.toJson(RemoveAuthorisationRequest(clientId = regimeData.clientId.value, service = regimeData.service.id)).toString()
          ).status shouldBe 204

          verifyDeleteRecordNotExists
          eventually {
            await(invitationRepo.findOneById(invitation.invitationId)).get.status shouldBe DeAuthorised
          }
          verifyTerminateRelationshipAuditSent(
            requestPath,
            TestData.arn.value,
            regimeData.clientId.value,
            regimeData.service.supportedClientIdType.enrolmentId,
            regimeData.service.id,
            "AgentLedTermination"
          )
        }

        "return 204 when stride deauthorises the relationship" in {
          // Authorised as stride
          AuthStub.givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-983283")
          setupStandardRelationship()
          val invitation = acceptedInvitation
          invitationRepo.collection.insertOne(invitation).toFuture().futureValue

          doAgentPostRequest(
            requestPath,
            Json.toJson(RemoveAuthorisationRequest(clientId = regimeData.clientId.value, service = regimeData.service.id)).toString()
          ).status shouldBe 204

          verifyDeleteRecordNotExists
          eventually {
            await(invitationRepo.findOneById(invitation.invitationId)).get.status shouldBe DeAuthorised
          }
          verifyTerminateRelationshipAuditSent(
            requestPath,
            TestData.arn.value,
            regimeData.clientId.value,
            regimeData.service.supportedClientIdType.enrolmentId,
            regimeData.service.id,
            "HMRCLedTermination"
          )
        }
      }

      "standard relationship exists and there is a deauth record" should {
        "return 204 after completing an unfinished EACD delete record" in {
          // Authorised as client
          regimeData.clientAuthStubs()
          setupStandardRelationship()
          val invitation = acceptedInvitation
          invitationRepo.collection.insertOne(invitation).toFuture().futureValue
          deleteRecordRepo.create(
            DeleteRecord(
              arn = TestData.arn.value,
              enrolmentKey = regimeData.enrolment,
              suppliedClientId = Some(regimeData.suppliedClientId.value),
              dateTime = LocalDateTime.now.minusMinutes(1),
              syncToETMPStatus = Some(SyncStatus.Success),
              syncToESStatus = Some(SyncStatus.Failed)
            )
          ).futureValue

          doAgentPostRequest(
            requestPath,
            Json.toJson(RemoveAuthorisationRequest(clientId = regimeData.clientId.value, service = regimeData.service.id)).toString()
          ).status shouldBe 204

          verifyDeleteRecordNotExists
          eventually {
            await(invitationRepo.findOneById(invitation.invitationId)).get.status shouldBe DeAuthorised
          }
          verifyTerminateRelationshipAuditSent(
            requestPath,
            TestData.arn.value,
            regimeData.clientId.value,
            regimeData.service.supportedClientIdType.enrolmentId,
            regimeData.service.id,
            "ClientLedTermination"
          )
        }

        "return 204 after completing an unfinished ETMP delete record" in {
          // Authorised as client
          regimeData.clientAuthStubs()
          setupStandardRelationship()
          val invitation = acceptedInvitation
          invitationRepo.collection.insertOne(invitation).toFuture().futureValue
          deleteRecordRepo.create(
            DeleteRecord(
              arn = TestData.arn.value,
              enrolmentKey = regimeData.enrolment,
              suppliedClientId = Some(regimeData.suppliedClientId.value),
              dateTime = LocalDateTime.now.minusMinutes(1),
              syncToETMPStatus = Some(SyncStatus.Failed)
            )
          ).futureValue

          doAgentPostRequest(
            requestPath,
            Json.toJson(RemoveAuthorisationRequest(clientId = regimeData.clientId.value, service = regimeData.service.id)).toString()
          ).status shouldBe 204

          verifyDeleteRecordNotExists
          eventually {
            await(invitationRepo.findOneById(invitation.invitationId)).get.status shouldBe DeAuthorised
          }
          verifyTerminateRelationshipAuditSent(
            requestPath,
            TestData.arn.value,
            regimeData.clientId.value,
            regimeData.service.supportedClientIdType.enrolmentId,
            regimeData.service.id,
            "ClientLedTermination"
          )
        }

        "return 204 after completing a completely unfinished delete record" in {
          // Authorised as client
          regimeData.clientAuthStubs()
          setupStandardRelationship()
          val invitation = acceptedInvitation
          invitationRepo.collection.insertOne(invitation).toFuture().futureValue
          deleteRecordRepo.create(
            DeleteRecord(
              arn = TestData.arn.value,
              enrolmentKey = regimeData.enrolment,
              suppliedClientId = Some(regimeData.suppliedClientId.value),
              dateTime = LocalDateTime.now.minusMinutes(1)
            )
          ).futureValue

          doAgentPostRequest(
            requestPath,
            Json.toJson(RemoveAuthorisationRequest(clientId = regimeData.clientId.value, service = regimeData.service.id)).toString()
          ).status shouldBe 204

          verifyDeleteRecordNotExists
          eventually {
            await(invitationRepo.findOneById(invitation.invitationId)).get.status shouldBe DeAuthorised
          }
          verifyTerminateRelationshipAuditSent(
            requestPath,
            TestData.arn.value,
            regimeData.clientId.value,
            regimeData.service.supportedClientIdType.enrolmentId,
            regimeData.service.id,
            "ClientLedTermination"
          )
        }

        "return 423 when a deauth is in progress" in {
          // Authorised as client
          regimeData.clientAuthStubs()
          setupStandardRelationship()
          val invitation = acceptedInvitation
          invitationRepo.collection.insertOne(invitation).toFuture().futureValue
          mongoLockRepository.collection
            .insertOne(
              Lock(
                id = s"recovery-${TestData.arn.value}-${regimeData.enrolment.tag}",
                owner = "86515a24-1a37-4a40-9117-4a117d8dd42e",
                expiryTime = Instant.now().plusSeconds(2),
                timeCreated = Instant.now().minusMillis(500)
              )
            )
            .toFuture().futureValue

          doAgentPostRequest(
            requestPath,
            Json.toJson(RemoveAuthorisationRequest(clientId = regimeData.clientId.value, service = regimeData.service.id)).toString()
          ).status shouldBe LOCKED
        }
      }
    }
  }

  Seq(ItsaTestData, ItsaSuppTestData).foreach { regimeData =>
    s"POST /agent/:arn/remove-authorisations for ${regimeData.service.id} and ${regimeData.suppliedClientId.value}" when {
      "partial auth relationship exists" should {
        "return 204 when client deauthorises the relationship" in {
          // Authorised as client
          regimeData.clientAuthStubs()

          // Lookup clientId fails
          regimeData.clientIdLookupFailureStubs()

          val newInvitation: Invitation = Invitation
            .createNew(
              TestData.arn.value,
              regimeData.service,
              regimeData.suppliedClientId,
              "TestClientName",
              "testAgentName",
              "agent@email.com",
              LocalDate.now(),
              None
            )
            .copy(status = PartialAuth)

          await(
            partialAuthRepository.create(
              Instant.now(),
              TestData.arn,
              regimeData.service.id,
              TestData.nino
            )
          )
          await(invitationRepo.collection.insertOne(newInvitation).toFuture())

          doAgentPostRequest(
            requestPath,
            Json.toJson(RemoveAuthorisationRequest(regimeData.suppliedClientId.value, regimeData.service.id)).toString()
          ).status shouldBe 204

          partialAuthRepository
            .findActive(
              regimeData.service.id,
              TestData.nino,
              TestData.arn
            )
            .futureValue shouldBe None

          eventually {
            await(invitationRepo.findOneById(newInvitation.invitationId)).get.status shouldBe DeAuthorised
          }

          verifyTerminatePartialAuthAuditSent(
            requestPath,
            TestData.arn.value,
            regimeData.suppliedClientId.value,
            regimeData.service.id,
            "ClientLedTermination"
          )
        }
      }
      "partial auth record does not exist" should {
        "return 404 RELATIONSHIP_NOT_FOUND" in {
          // Authorised as client
          regimeData.clientAuthStubs()

          // Lookup clientId fails
          regimeData.clientIdLookupFailureStubs()

          val result: HttpResponse = doAgentPostRequest(
            requestPath,
            Json.toJson(RemoveAuthorisationRequest(TestData.nino.value, regimeData.service.id)).toString()
          )

          result.status shouldBe 404
          result.json shouldBe toJson(
            ErrorBody("RELATIONSHIP_NOT_FOUND", "The specified relationship was not found.")
          )
        }
      }
    }
  }

  s"POST /agent/:arn/remove-authorisations for ${IrvTestData.service.id} and ${IrvTestData.suppliedClientId.value}" when {
    "IRV relationship exists and no deauth record exists" should {
      "return 204 when client deauthorises the relationship" in {
        IrvTestData.clientAuthStubs()

        val pirInvitation: Invitation = Invitation
          .createNew(
            TestData.arn.value,
            PersonalIncomeRecord,
            TestData.nino,
            "TestClientName",
            "testAgentName",
            "agent@email.com",
            LocalDate.now(),
            None
          )
          .copy(status = Accepted)

        await(invitationRepo.collection.insertOne(pirInvitation).toFuture())

        givenTerminateAfiRelationshipSucceeds(
          TestData.arn,
          PersonalIncomeRecord.id,
          TestData.nino.value
        )

        doAgentPostRequest(
          requestPath,
          Json.toJson(RemoveAuthorisationRequest(TestData.nino.value, PersonalIncomeRecord.id)).toString()
        ).status shouldBe 204

        eventually {
          await(invitationRepo.findOneById(pirInvitation.invitationId)).get.status shouldBe DeAuthorised
        }

        verifyTerminateRelationshipAuditSent(
          requestPath,
          TestData.arn.value,
          IrvTestData.clientId.value,
          IrvTestData.service.supportedClientIdType.enrolmentId,
          IrvTestData.service.id,
          "ClientLedTermination",
          enrolmentDeallocated = false,
          etmpRelationshipRemoved = false
        )
      }
    }
    "IRV relationship does not exist" should {
      "return 404 RELATIONSHIP_NOT_FOUND" in {
        IrvTestData.clientAuthStubs()

        givenTerminateAfiRelationshipFails(
          TestData.arn,
          PersonalIncomeRecord.id,
          TestData.nino.value,
          404
        )

        val result: HttpResponse = doAgentPostRequest(
          requestPath,
          Json.toJson(RemoveAuthorisationRequest(TestData.nino.value, PersonalIncomeRecord.id)).toString()
        )

        result.status shouldBe 404
        result.json shouldBe toJson(ErrorBody("RELATIONSHIP_NOT_FOUND", "The specified relationship was not found."))
      }
    }
  }

  "POST /agent/:arn/remove-authorisations should handle errors" when {
    "request data is incorrect" should {
      "throw error when clientId is not valid for service" in {
        val result = doAgentPostRequest(
          requestPath,
          Json.toJson(RemoveAuthorisationRequest("IncorrectNinoOrMtdItId", MtdIt.id)).toString()
        )
        result.status shouldBe 500
        result.body shouldBe """{"statusCode":500,"message":"Failed to build enrolment key because: Identifier IncorrectNinoOrMtdItId of type MtdItIdType provided for service HMRC-MTD-IT failed validation"}"""
      }

      "throw error if service is not supported" in {
        val result = doAgentPostRequest(
          requestPath,
          Json.toJson(RemoveAuthorisationRequest(nino.value, "IncorrectService")).toString()
        )
        result.status shouldBe 500
        result.body shouldBe """{"statusCode":500,"message":"Failed to build enrolment key because: Unknown service IncorrectService"}"""
      }
    }

    "MtdId business details errors" should {
      "throw error when MtdId business details record is invalid" in {
        HipStub.givenEmptyItsaBusinessDetailsExists(TestData.nino)
        val result = doAgentPostRequest(
          requestPath,
          Json.toJson(RemoveAuthorisationRequest(TestData.nino.value, MtdIt.id)).toString()
        )
        result.status shouldBe 500
      }
    }
  }

}
