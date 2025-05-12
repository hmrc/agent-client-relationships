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
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.ErrorBody
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.transitional.ChangeInvitationStatusRequest
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentmtdidentifiers.model.Service.CapitalGains
import uk.gov.hmrc.agentmtdidentifiers.model.Service.Cbc
import uk.gov.hmrc.agentmtdidentifiers.model.Service.CbcNonUk
import uk.gov.hmrc.agentmtdidentifiers.model.Service.MtdIt
import uk.gov.hmrc.agentmtdidentifiers.model.Service.MtdItSupp
import uk.gov.hmrc.agentmtdidentifiers.model.Service.PersonalIncomeRecord
import uk.gov.hmrc.agentmtdidentifiers.model.Service.Pillar2
import uk.gov.hmrc.agentmtdidentifiers.model.Service.Ppt
import uk.gov.hmrc.agentmtdidentifiers.model.Service.Trust
import uk.gov.hmrc.agentmtdidentifiers.model.Service.TrustNT
import uk.gov.hmrc.agentmtdidentifiers.model.Service.Vat
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.TaxIdentifier

import java.time.Instant
import java.time.ZoneOffset
import scala.concurrent.ExecutionContext

class ChangeInvitationStatusControllerISpec
extends BaseControllerISpec
with TestData {

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  val invitationRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val partialAuthRepository: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]

  def allServices: Map[Service, TaxIdentifier] = Map(
    MtdIt -> mtdItId,
    PersonalIncomeRecord -> nino,
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

  def requestPath(
    service: String,
    clientId: String
  ): String = s"/agent-client-relationships/transitional/change-invitation-status/arn/${arn.value}/service/$service/client/$clientId"

  allServices.foreach(testset =>
    s"/transitional/change-invitation-status/arn/:arn/service/:service/client/:clientId change status to DeAuthorised" should {
      val (service, taxIdentifier) = testset
      val clientId: ClientIdentifier[TaxIdentifier] = ClientIdentifier(taxIdentifier)
      val suppliedClientId =
        taxIdentifier match {
          case _: MtdItId => ClientIdentifier(nino)
          case taxId => ClientIdentifier(taxId)
        }
      val clientName = "TestClientName"
      val agentName = "testAgentName"
      val agentEmail = "agent@email.com"
      val expiryDate = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime.plusSeconds(60).toLocalDate
      val serviceId =
        service match {
          case PersonalIncomeRecord => PersonalIncomeRecord.id
          case s => s.id
        }

      s"when no invitation record for ${service.id}" should {
        s"return 404 NOT_FOUND" in {
          val result = doAgentPutRequest(
            requestPath(serviceId, suppliedClientId.value),
            Json
              .toJson(ChangeInvitationStatusRequest(invitationStatus = DeAuthorised, endedBy = Some("TestUser")))
              .toString()
          )
          result.status shouldBe NOT_FOUND
        }

      }

      s"when invitation exists with the status Accepted in invitationStore for ${service.id}" should {
        s"update status to " in {
          val newInvitation: Invitation = Invitation
            .createNew(
              arn.value,
              service,
              clientId,
              suppliedClientId,
              clientName,
              agentName,
              agentEmail,
              expiryDate,
              None
            )
            .copy(status = Accepted)

          await(invitationRepo.collection.insertOne(newInvitation).toFuture())

          doAgentPutRequest(
            requestPath(serviceId, suppliedClientId.value),
            Json
              .toJson(ChangeInvitationStatusRequest(invitationStatus = DeAuthorised, endedBy = Some("TestUser")))
              .toString()
          ).status shouldBe 204

          await(invitationRepo.findOneById(newInvitation.invitationId)).get.status == DeAuthorised
        }
      }

      s"when invitation exists with the status DeAuthorised in invitationStore for ${service.id}" should {
        s"update return NOT_FOUND 404 " in {
          val newInvitation = Invitation
            .createNew(
              arn.value,
              service,
              clientId,
              suppliedClientId,
              clientName,
              agentName,
              agentEmail,
              expiryDate,
              None
            )
            .copy(status = DeAuthorised)

          await(invitationRepo.collection.insertOne(newInvitation).toFuture())

          doAgentPutRequest(
            requestPath(serviceId, suppliedClientId.value),
            Json
              .toJson(ChangeInvitationStatusRequest(invitationStatus = DeAuthorised, endedBy = Some("TestUser")))
              .toString()
          ).status shouldBe NOT_FOUND

          await(invitationRepo.findOneById(newInvitation.invitationId)).get.status == DeAuthorised
        }
      }

    }
  )

  s"/transitional/change-invitation-status/arn/:arn/service/:service/client/:clientId for MtdIt PartialAuth status to DeAuthorised" should {
    val service = MtdIt
    val taxIdentifier = nino
    val clientId: ClientIdentifier[TaxIdentifier] = ClientIdentifier(taxIdentifier)
    val suppliedClientId = ClientIdentifier(nino)
    val clientName = "TestClientName"
    val agentName = "testAgentName"
    val agentEmail = "agent@email.com"
    val expiryDate = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime.plusSeconds(60).toLocalDate

    s"when no invitation record for ${service.id}" should {
      s"return 404 NOT_FOUND" in {
        val result = doAgentPutRequest(
          requestPath(service.id, suppliedClientId.value),
          Json
            .toJson(ChangeInvitationStatusRequest(invitationStatus = DeAuthorised, endedBy = Some("TestUser")))
            .toString()
        )
        result.status shouldBe NOT_FOUND

      }

    }

    s"when invitation exists with the status PartialAuth in invitationStore for ${service.id}" should {
      s"update status to " in {
        val newInvitation = Invitation
          .createNew(
            arn.value,
            service,
            clientId,
            suppliedClientId,
            clientName,
            agentName,
            agentEmail,
            expiryDate,
            None
          )
          .copy(status = PartialAuth)

        await(invitationRepo.collection.insertOne(newInvitation).toFuture())

        doAgentPutRequest(
          requestPath(service.id, suppliedClientId.value),
          Json
            .toJson(ChangeInvitationStatusRequest(invitationStatus = DeAuthorised, endedBy = Some("TestUser")))
            .toString()
        ).status shouldBe 204

        await(invitationRepo.findOneById(newInvitation.invitationId)).get.status == DeAuthorised
      }
    }

    s"when invitation exists with the status PartialAuth in PartialAuthStore for ${service.id}" should {
      s"update status to " in {
        val created = Instant.parse("2020-01-01T00:00:00.000Z")
        val partialAuth = PartialAuthRelationship(
          created,
          arn.value,
          service.id,
          nino.value,
          active = true,
          lastUpdated = created
        )

        val newInvitation = Invitation
          .createNew(
            arn.value,
            service,
            clientId,
            suppliedClientId,
            clientName,
            agentName,
            agentEmail,
            expiryDate,
            None
          )
          .copy(status = PartialAuth)

        await(invitationRepo.collection.insertOne(newInvitation).toFuture())
        await(partialAuthRepository.collection.insertOne(partialAuth).toFuture())

        doAgentPutRequest(
          requestPath(service.id, suppliedClientId.value),
          Json
            .toJson(ChangeInvitationStatusRequest(invitationStatus = DeAuthorised, endedBy = Some("TestUser")))
            .toString()
        ).status shouldBe 204

        await(invitationRepo.findOneById(newInvitation.invitationId)).get.status == DeAuthorised
        await(
          partialAuthRepository.findActive(
            service.id,
            nino,
            arn
          )
        ) shouldBe None
      }
    }

    s"when invitation exists with the status PartialAuth in PartialAuthStore and InvitationStore for ${service.id}" should {
      s"update status to " in {
        val created = Instant.parse("2020-01-01T00:00:00.000Z")
        val partialAuth = PartialAuthRelationship(
          created,
          arn.value,
          service.id,
          nino.value,
          active = true,
          lastUpdated = created
        )

        await(partialAuthRepository.collection.insertOne(partialAuth).toFuture())

        doAgentPutRequest(
          requestPath(service.id, suppliedClientId.value),
          Json
            .toJson(ChangeInvitationStatusRequest(invitationStatus = DeAuthorised, endedBy = Some("TestUser")))
            .toString()
        ).status shouldBe 204

        await(
          partialAuthRepository.findActive(
            service.id,
            nino,
            arn
          )
        ) shouldBe None
      }
    }

    s"when invitation exists with the status DeAuthorised in invitationStore for ${service.id}" should {
      s"update return NOT_FOUND 404 " in {
        val newInvitation = Invitation
          .createNew(
            arn.value,
            service,
            clientId,
            suppliedClientId,
            clientName,
            agentName,
            agentEmail,
            expiryDate,
            None
          )
          .copy(status = DeAuthorised)

        await(invitationRepo.collection.insertOne(newInvitation).toFuture())

        doAgentPutRequest(
          requestPath(service.id, suppliedClientId.value),
          Json
            .toJson(ChangeInvitationStatusRequest(invitationStatus = DeAuthorised, endedBy = Some("TestUser")))
            .toString()
        ).status shouldBe NOT_FOUND

        await(invitationRepo.findOneById(newInvitation.invitationId)).get.status == DeAuthorised
      }
    }

  }

  "handle errors" should {
    "when request data are incorrect" should {
      "return BadRequest 400 status when clientId is not valid for service" in {
        val result = doAgentPutRequest(
          requestPath(MtdIt.id, mtdItId.value),
          Json
            .toJson(ChangeInvitationStatusRequest(invitationStatus = DeAuthorised, endedBy = Some("TestUser")))
            .toString()
        )
        result.status shouldBe 400
        result.json shouldBe toJson(
          ErrorBody("INVALID_CLIENT_ID", "Invalid clientId \"ABCDEF123456789\", for service type \"HMRC-MTD-IT\"")
        )
      }

      "return NotImplemented 501 status and JSON Error If service is not supported" in {
        val result = doAgentPutRequest(
          requestPath("IncorrectService", mtdItId.value),
          Json
            .toJson(ChangeInvitationStatusRequest(invitationStatus = DeAuthorised, endedBy = Some("TestUser")))
            .toString()
        )
        result.status shouldBe 501

        val message = s"""Unsupported service "IncorrectService""""
        result.json shouldBe toJson(ErrorBody("UNSUPPORTED_SERVICE", message))
      }

    }
  }

}
