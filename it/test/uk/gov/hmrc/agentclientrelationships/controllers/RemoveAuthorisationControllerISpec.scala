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
import uk.gov.hmrc.agentclientrelationships.model.invitation.RemoveAuthorisationRequest
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.repository.DeleteRecord
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus
import uk.gov.hmrc.agentclientrelationships.stubs.AfiRelationshipStub
import uk.gov.hmrc.agentclientrelationships.stubs.ClientDetailsStub
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentclientrelationships.model.identifiers.CbcId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.ClientIdentifier
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Identifier
import uk.gov.hmrc.agentclientrelationships.model.identifiers.MtdItId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.CapitalGains
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Cbc
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.CbcNonUk
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdIt
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdItSupp
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.PersonalIncomeRecord
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Pillar2
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Ppt
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Trust
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.TrustNT
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Vat
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.mongo.lock.Lock

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class RemoveAuthorisationControllerISpec
extends RelationshipsBaseControllerISpec
with HipStub
with ClientDetailsStub
with AfiRelationshipStub
with TestData {

  val controller = app.injector.instanceOf[RemoveAuthorisationController]

  val invitationRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val partialAuthRepository: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]

  def allServices: Map[Service, TaxIdentifier] = Map(
    MtdIt -> mtdItId,
    //    PersonalIncomeRecord -> nino, PIR tested separately in this file
    Vat -> vrn,
    Trust -> utr,
    TrustNT -> urn,
    CapitalGains -> cgtRef,
    Ppt -> pptRef,
    Cbc -> cbcId,
    CbcNonUk -> cbcId,
    Pillar2 -> plrId,
    MtdItSupp -> mtdItId
  )

  val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/remove-authorisation"

  allServices.keySet
    .foreach(service =>
      s"s/agent/:arn/remove-authorisation" should {
        val taxIdentifier = allServices(service)
        val clientId: ClientIdentifier[TaxIdentifier] = ClientIdentifier(taxIdentifier)

        val suppliedClientId =
          taxIdentifier match {
            case _: MtdItId => ClientIdentifier(nino)
            case taxId => ClientIdentifier(taxId)
          }

        val enrolmentKey: EnrolmentKey =
          taxIdentifier match {
            case _: CbcId if service == Cbc => EnrolmentKey(service.enrolmentKey, Seq(Identifier("cbcId", cbcId.value), Identifier("UTR", utr.value)))
            case _ => EnrolmentKey(service.enrolmentKey, taxIdentifier)
          }

        val serviceId =
          service match {
            case PersonalIncomeRecord => PersonalIncomeRecord.id
            case s => s.id
          }
        val expiryDate = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime.plusSeconds(60).toLocalDate

        abstract class StubsForThisScenario(isAgent: Boolean = true) {
          if (isAgent)
            givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
          else
            givenUserIsSubscribedClient(taxIdentifier, withThisGgUserId = "ggUserId-client")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
          givenAgentCanBeDeallocated(taxIdentifier, arn)
          givenEnrolmentDeallocationSucceeds("foo", enrolmentKey)
          givenAdminUser("foo", "any")
          givenCacheRefresh(arn)

          taxIdentifier match {
            case mtdIdentifier @ MtdItId(_) =>
              givenMtdItIdIsKnownFor(nino, mtdIdentifier)
              givenMtdItsaBusinessDetailsExists(nino, mtdIdentifier)
            case _ @CbcId(_) if service == Cbc =>
              givenKnownFactsQuery(
                Service.Cbc,
                cbcId,
                Some(Seq(Identifier("cbcId", cbcId.value), Identifier("UTR", utr.value)))
              )
            case _ @CbcId(_) if service == CbcNonUk =>
              givenKnownFactsQuery(
                Service.CbcNonUk,
                cbcId,
                Some(Seq(Identifier("cbcId", cbcId.value)))
              )
            case _ @NinoWithoutSuffix(_) if service == PersonalIncomeRecord =>
              givenTerminateAfiRelationshipSucceeds(
                arn,
                PersonalIncomeRecord.id,
                nino.value
              )
            case _ =>
          }

        }

        s"when the relationship exists and no invitation record in Repository for ${service.id}" should {
          s"return 204 and sent audit event for $serviceId" in new StubsForThisScenario {
            doAgentPostRequest(
              requestPath,
              Json.toJson(RemoveAuthorisationRequest(clientId = suppliedClientId.value, service = serviceId)).toString()
            ).status shouldBe 204
            verifyDeleteRecordNotExists

            verifyTerminateRelationshipAuditSent(
              requestPath,
              arn.value,
              clientId.value,
              clientId.enrolmentId,
              serviceId,
              "AgentLedTermination"
            )
          }

          s"return 204 for $serviceId when initiated by client" in new StubsForThisScenario(isAgent = false) {
            val suppliedClientId: ClientIdentifier[TaxIdentifier] = ClientIdentifier(
              taxIdentifier
            ) // undoing the itsa -> nino replacement

            doAgentPostRequest(
              requestPath,
              Json.toJson(RemoveAuthorisationRequest(clientId = suppliedClientId.value, service = serviceId)).toString()
            ).status shouldBe 204
            verifyDeleteRecordNotExists
          }
        }

        s"when the relationship exists and the Arn matches that of current Agent user  for ${service.id}" should {
          s"resume an ongoing de-auth if unfinished ES delete record found  for ${service.id}" in new StubsForThisScenario {
            val newInvitation: Invitation = Invitation
              .createNew(
                arn.value,
                service,
                nino,
                nino,
                "TestClientName",
                "testAgentName",
                "agent@email.com",
                expiryDate,
                None
              )
              .copy(status = Accepted)

            await(invitationRepo.collection.insertOne(newInvitation).toFuture())

            await(
              deleteRecordRepository.create(
                DeleteRecord(
                  arn = arn.value,
                  enrolmentKey = enrolmentKey,
                  dateTime = LocalDateTime.now.minusMinutes(1),
                  syncToETMPStatus = Some(SyncStatus.Success),
                  syncToESStatus = Some(SyncStatus.Failed)
                )
              )
            )

            doAgentPostRequest(
              requestPath,
              Json.toJson(RemoveAuthorisationRequest(clientId = suppliedClientId.value, service = serviceId)).toString()
            ).status shouldBe 204

            await(invitationRepo.findOneById(newInvitation.invitationId)).get.status == DeAuthorised

            verifyDeleteRecordNotExists
            verifyTerminateRelationshipAuditSent(
              requestPath,
              arn.value,
              clientId.value,
              clientId.enrolmentId,
              serviceId,
              "AgentLedTermination"
            )
          }

          s"resume an ongoing de-auth if unfinished ETMP delete record found  for ${service.id}" in new StubsForThisScenario {
            await(
              deleteRecordRepository.create(
                DeleteRecord(
                  arn = arn.value,
                  enrolmentKey = enrolmentKey,
                  dateTime = LocalDateTime.now.minusMinutes(1),
                  syncToETMPStatus = Some(SyncStatus.Failed)
                )
              )
            )
            doAgentPostRequest(
              requestPath,
              Json
                .toJson(RemoveAuthorisationRequest(clientId = suppliedClientId.value, service = service.id))
                .toString()
            ).status shouldBe 204
            verifyDeleteRecordNotExists
          }

          s"resume an ongoing de-auth if some delete record found  for ${service.id}" in new StubsForThisScenario {
            await(
              deleteRecordRepository.create(
                DeleteRecord(
                  arn = arn.value,
                  enrolmentKey = enrolmentKey,
                  dateTime = LocalDateTime.now.minusMinutes(1)
                )
              )
            )
            doAgentPostRequest(
              requestPath,
              Json
                .toJson(RemoveAuthorisationRequest(clientId = suppliedClientId.value, service = service.id))
                .toString()
            ).status shouldBe 204
            verifyDeleteRecordNotExists
          }

          "return 423 when relationship deletion is already in progress" in new StubsForThisScenario {
            await(
              mongoLockRepository.collection
                .insertOne(
                  Lock(
                    id = s"recovery-${arn.value}-${enrolmentKey.tag}",
                    owner = "86515a24-1a37-4a40-9117-4a117d8dd42e",
                    expiryTime = Instant.now().plusSeconds(2),
                    timeCreated = Instant.now().minusMillis(500)
                  )
                )
                .toFuture()
            )

            doAgentPostRequest(
              requestPath,
              Json.toJson(RemoveAuthorisationRequest(clientId = suppliedClientId.value, service = service.id)).toString()
            ).status shouldBe LOCKED
          }
        }
      }
    )

  "for alt Itsa relationship" should {
    val taxIdentifier = mtdItId
    val service = MtdIt
    val enrolmentKey: EnrolmentKey = EnrolmentKey(MtdIt.id, taxIdentifier)
    val expiryDate = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime.plusSeconds(60).toLocalDate

    trait StubsForThisScenario {
      givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
      givenPrincipalAgentUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
      givenAgentCanBeDeallocated(taxIdentifier, arn)
      givenEnrolmentDeallocationSucceeds("foo", enrolmentKey)
      givenAdminUser("foo", "any")
      givenCacheRefresh(arn)
      givenMtdItIdIsUnKnownFor(nino)

    }

    "return 204 when PartialAuth exists in PartialAuth and Invitation Repo" in new StubsForThisScenario {
      val newInvitation: Invitation = Invitation
        .createNew(
          arn.value,
          service,
          nino,
          nino,
          "TestClientName",
          "testAgentName",
          "agent@email.com",
          expiryDate,
          None
        )
        .copy(status = PartialAuth)

      await(
        deleteRecordRepository.create(
          DeleteRecord(
            arn = arn.value,
            enrolmentKey = EnrolmentKey(service.enrolmentKey, nino),
            dateTime = LocalDateTime.now.minusMinutes(1),
            syncToETMPStatus = Some(SyncStatus.Success),
            syncToESStatus = Some(SyncStatus.Failed)
          )
        )
      )
      await(
        partialAuthRepository.create(
          Instant.now(),
          arn,
          MtdIt.id,
          nino
        )
      )
      await(invitationRepo.collection.insertOne(newInvitation).toFuture())

      doAgentPostRequest(
        requestPath,
        Json.toJson(RemoveAuthorisationRequest(nino.value, MtdIt.id)).toString()
      ).status shouldBe 204

      val partialAuthInvitations: Option[PartialAuthRelationship] =
        partialAuthRepository
          .findActive(
            MtdIt.id,
            nino,
            arn
          )
          .futureValue

      partialAuthInvitations.isDefined shouldBe false
      verifyDeleteRecordNotExists
      await(invitationRepo.findOneById(newInvitation.invitationId)).get.status == DeAuthorised

      verifyTerminatePartialAuthAuditSent(
        requestPath,
        arn.value,
        nino.value,
        service.id,
        "AgentLedTermination"
      )
    }

    "return 204 when PartialAuth exists in PartialAuth Repo and not not exists in InvitationRepo" in new StubsForThisScenario {
      await(
        partialAuthRepository.create(
          Instant.now(),
          arn,
          MtdIt.id,
          nino
        )
      )

      doAgentPostRequest(
        requestPath,
        Json.toJson(RemoveAuthorisationRequest(nino.value, MtdIt.id)).toString()
      ).status shouldBe 204

      val partialAuthInvitations: Option[PartialAuthRelationship] =
        partialAuthRepository
          .findActive(
            MtdIt.id,
            nino,
            arn
          )
          .futureValue

      partialAuthInvitations.isDefined shouldBe false
      await(invitationRepo.findAllForAgent(arn.value)) shouldBe Seq.empty

      verifyTerminatePartialAuthAuditSent(
        requestPath,
        arn.value,
        nino.value,
        service.id,
        "AgentLedTermination"
      )
    }

    "return 404 RELATIONSHIP_NOT_FOUND when PartialAuth does not exist in PartialAuth Repo" in new StubsForThisScenario {
      val result: HttpResponse = doAgentPostRequest(
        requestPath,
        Json.toJson(RemoveAuthorisationRequest(nino.value, MtdIt.id)).toString()
      )

      result.status shouldBe 404
      result.json shouldBe toJson(
        ErrorBody("RELATIONSHIP_NOT_FOUND", "The specified relationship was not found.")
      )

      val partialAuthInvitations: Option[PartialAuthRelationship] =
        partialAuthRepository
          .findActive(
            MtdIt.id,
            nino,
            arn
          )
          .futureValue

      partialAuthInvitations.isDefined shouldBe false
    }

  }

  "for PersonalIncome relationship" should {
    trait StubsForThisScenario {
      givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
      givenPrincipalAgentUser(arn, "foo")
      givenGroupInfo("foo", "bar")
    }

    "return 204 when AFI deletes the relationship" in new StubsForThisScenario {
      val pirInvitation: Invitation = Invitation
        .createNew(
          arn.value,
          PersonalIncomeRecord,
          nino,
          nino,
          "TestClientName",
          "testAgentName",
          "agent@email.com",
          LocalDate.now(),
          None
        )
        .copy(status = Accepted)

      await(invitationRepo.collection.insertOne(pirInvitation).toFuture())

      givenTerminateAfiRelationshipSucceeds(
        arn,
        PersonalIncomeRecord.id,
        nino.value
      )

      doAgentPostRequest(
        requestPath,
        Json.toJson(RemoveAuthorisationRequest(nino.value, PersonalIncomeRecord.id)).toString()
      ).status shouldBe 204

      verifyTerminateRelationshipAuditSent(
        requestPath,
        arn.value,
        nino.value,
        "NINO",
        PersonalIncomeRecord.id,
        "AgentLedTermination",
        enrolmentDeallocated = false,
        etmpRelationshipRemoved = false
      )

      eventually {
        await(invitationRepo.findOneById(pirInvitation.invitationId)).get.status shouldBe DeAuthorised
      }
    }

    "return 404 when AFI returns 404" in new StubsForThisScenario {
      givenTerminateAfiRelationshipFails(
        arn,
        PersonalIncomeRecord.id,
        nino.value,
        404
      )

      val result: HttpResponse = doAgentPostRequest(
        requestPath,
        Json.toJson(RemoveAuthorisationRequest(nino.value, PersonalIncomeRecord.id)).toString()
      )

      result.status shouldBe 404
      result.json shouldBe toJson(ErrorBody("RELATIONSHIP_NOT_FOUND", "The specified relationship was not found."))
    }

    "return 500 when AFI returns 500" in new StubsForThisScenario {
      givenTerminateAfiRelationshipFails(
        arn,
        PersonalIncomeRecord.id,
        nino.value
      )

      val result: HttpResponse = doAgentPostRequest(
        requestPath,
        Json.toJson(RemoveAuthorisationRequest(nino.value, PersonalIncomeRecord.id)).toString()
      )
      result.status shouldBe 500
    }
  }

  "handle errors" when {
    "request data is incorrect" should {
      "return BadRequest 400 status when clientId is not valid for service" in {
        val result = doAgentPostRequest(
          requestPath,
          Json.toJson(RemoveAuthorisationRequest("IncorrectNinoOrMtdItId", MtdIt.id)).toString()
        )
        result.status shouldBe 400
        result.json shouldBe toJson(
          ErrorBody(
            "INVALID_CLIENT_ID",
            "Invalid clientId \"IncorrectNinoOrMtdItId\", for service type \"HMRC-MTD-IT\""
          )
        )
      }

      "return NotImplemented 501 status and JSON Error If service is not supported" in {
        val result = doAgentPostRequest(
          requestPath,
          Json.toJson(RemoveAuthorisationRequest(nino.value, "IncorrectService")).toString()
        )
        result.status shouldBe 501

        val message = s"""Unsupported service "IncorrectService""""
        result.json shouldBe toJson(ErrorBody("UNSUPPORTED_SERVICE", message))
      }
    }

    "MtdId business details errors" should {
      "return Forbidden 403 status and JSON Error when MtdId business details record is empty " in {
        givenEmptyItsaBusinessDetailsExists(nino)
        val result = doAgentPostRequest(
          requestPath,
          Json.toJson(RemoveAuthorisationRequest(nino.value, MtdIt.id)).toString()
        )
        result.status shouldBe 403

        result.json shouldBe toJson(
          ErrorBody(
            "CLIENT_REGISTRATION_NOT_FOUND",
            "The Client's MTDfB registration or SAUTR (if alt-itsa is enabled) was not found."
          )
        )
      }
    }
  }

}
