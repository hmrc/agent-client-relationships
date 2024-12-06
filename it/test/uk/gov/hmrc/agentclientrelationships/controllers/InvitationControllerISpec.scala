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
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.Pending
import uk.gov.hmrc.agentclientrelationships.model.invitation.CreateInvitationInputData
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.ErrorBody
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.services.InvitationService
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentmtdidentifiers.model.Service._
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.auth.core.AuthConnector

import scala.concurrent.ExecutionContext

class InvitationControllerISpec extends RelationshipsBaseControllerISpec with TestData {

  val invitationService: InvitationService = app.injector.instanceOf[InvitationService]
  val authConnector: AuthConnector = app.injector.instanceOf[AuthConnector]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  val controller =
    new InvitationController(invitationService, authConnector, appConfig, stubControllerComponents())

  def invitationRepo: InvitationsRepository = new InvitationsRepository(mongoComponent, appConfig)

  val clientName = "DummyClientName"
  val baseInvitationInputData: CreateInvitationInputData =
    CreateInvitationInputData(nino.value, NinoType.id, clientName, MtdIt.id)

  def allServices: Map[String, CreateInvitationInputData] = Map(
    HMRCMTDIT -> baseInvitationInputData,
    HMRCPIR   -> baseInvitationInputData.copy(inputService = HMRCPIR),
    HMRCMTDVAT -> baseInvitationInputData
      .copy(inputService = HMRCMTDVAT, inputSuppliedClientId = vrn.value, inputSuppliedClientIdType = VrnType.id),
    HMRCTERSORG -> baseInvitationInputData
      .copy(inputService = HMRCTERSORG, inputSuppliedClientId = utr.value, inputSuppliedClientIdType = UtrType.id),
    HMRCTERSNTORG -> baseInvitationInputData
      .copy(inputService = HMRCTERSNTORG, inputSuppliedClientId = urn.value, inputSuppliedClientIdType = UrnType.id),
    HMRCCGTPD -> baseInvitationInputData
      .copy(inputService = HMRCCGTPD, inputSuppliedClientId = cgtRef.value, inputSuppliedClientIdType = CgtRefType.id),
    HMRCPPTORG -> baseInvitationInputData
      .copy(inputService = HMRCPPTORG, inputSuppliedClientId = pptRef.value, inputSuppliedClientIdType = PptRefType.id),
    HMRCCBCORG -> baseInvitationInputData
      .copy(inputService = HMRCCBCORG, inputSuppliedClientId = cbcId.value, inputSuppliedClientIdType = CbcIdType.id),
    HMRCCBCNONUKORG -> baseInvitationInputData.copy(
      inputService = HMRCCBCNONUKORG,
      inputSuppliedClientId = cbcId.value,
      inputSuppliedClientIdType = CbcIdType.id
    ),
    HMRCPILLAR2ORG -> baseInvitationInputData.copy(
      inputService = HMRCPILLAR2ORG,
      inputSuppliedClientId = plrId.value,
      inputSuppliedClientIdType = PlrIdType.id
    ),
    HMRCMTDITSUPP -> baseInvitationInputData.copy(inputService = HMRCMTDITSUPP)
  )

  "create invitation link" should {

    // TODO WG - test expiry date of Invitation
    allServices.keySet.foreach(taxService =>
      s"return 201 status and valid JSON when invitation is created for $taxService" in {
        val inputData: CreateInvitationInputData = allServices(taxService)

        val clientId =
          if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) mtdItId.value else inputData.inputSuppliedClientId
        val clientIdType =
          if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) MtdItIdType.id
          else inputData.inputSuppliedClientIdType

        givenAuditConnector()

        if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) {
          givenMtdItIdIsKnownFor(nino, mtdItId)
        }

        invitationRepo
          .findAllForAgent(arn.value)
          .futureValue shouldBe empty

        val result =
          doAgentPostRequest(
            s"/agent-client-relationships/agent/${arn.value}/authorisation-request",
            Json.toJson(inputData).toString()
          )
        result.status shouldBe 201

        val invitationSeq = invitationRepo
          .findAllForAgent(arn.value)
          .futureValue

        invitationSeq.size shouldBe 1

        val invitation = invitationSeq.head

        result.json shouldBe Json.obj(
          "invitationId" -> invitation.invitationId
        )

        invitation.status shouldBe Pending
        invitation.suppliedClientId shouldBe inputData.inputSuppliedClientId
        invitation.suppliedClientIdType shouldBe inputData.inputSuppliedClientIdType
        invitation.clientId shouldBe clientId
        invitation.clientIdType shouldBe clientIdType
        invitation.service shouldBe inputData.inputService
        invitation.clientName shouldBe clientName

      }
    )

    "return Forbidden 403 status and JSON Error when client registration not found for ItSa" in {
      val suppliedClientId = nino.value
      val suppliedClientIdType = NinoType.id
      val service = MtdIt.id
      val clientName = "DummyClientName"

      val createInvitationInputData: CreateInvitationInputData = CreateInvitationInputData(
        inputSuppliedClientId = suppliedClientId,
        inputSuppliedClientIdType = suppliedClientIdType,
        clientName = clientName,
        inputService = service
      )

      givenAuditConnector()

      givenMtdItIdIsUnKnownFor(nino)

      invitationRepo
        .findAllForAgent(arn.value)
        .futureValue shouldBe empty

      val result =
        doAgentPostRequest(
          s"/agent-client-relationships/agent/${arn.value}/authorisation-request",
          Json.toJson(createInvitationInputData).toString()
        )
      result.status shouldBe 403

      invitationRepo
        .findAllForAgent(arn.value)
        .futureValue shouldBe empty

      result.json shouldBe toJson(
        ErrorBody(
          "CLIENT_REGISTRATION_NOT_FOUND",
          "The Client's MTDfB registration or SAUTR (if alt-itsa is enabled) was not found."
        )
      )
    }

    "return NotImplemented 501 status and JSON Error If service is not supported" in {
      val suppliedClientId = nino.value
      val suppliedClientIdType = NinoType.id
      val service = "HMRC-NOT-SUPPORTED"
      val clientName = "DummyClientName"

      val createInvitationInputData: CreateInvitationInputData = CreateInvitationInputData(
        inputSuppliedClientId = suppliedClientId,
        inputSuppliedClientIdType = suppliedClientIdType,
        clientName = clientName,
        inputService = service
      )

      givenAuditConnector()

      invitationRepo
        .findAllForAgent(arn.value)
        .futureValue shouldBe empty

      val result =
        doAgentPostRequest(
          s"/agent-client-relationships/agent/${arn.value}/authorisation-request",
          Json.toJson(createInvitationInputData).toString()
        )
      result.status shouldBe 501

      invitationRepo
        .findAllForAgent(arn.value)
        .futureValue shouldBe empty

      val message = s"""Unsupported service "${createInvitationInputData.inputService}""""
      result.json shouldBe toJson(ErrorBody("UNSUPPORTED_SERVICE", message))
    }

    "return BadRequest 400 status when clientId is not valid for service" in {
      val suppliedClientId = "NotValidNino"
      val suppliedClientIdType = NinoType.id
      val service = MtdIt.id
      val clientName = "DummyClientName"

      val createInvitationInputData: CreateInvitationInputData = CreateInvitationInputData(
        inputSuppliedClientId = suppliedClientId,
        inputSuppliedClientIdType = suppliedClientIdType,
        clientName = clientName,
        inputService = service
      )

      givenAuditConnector()

      invitationRepo
        .findAllForAgent(arn.value)
        .futureValue shouldBe empty

      val result =
        doAgentPostRequest(
          s"/agent-client-relationships/agent/${arn.value}/authorisation-request",
          Json.toJson(createInvitationInputData).toString()
        )
      result.status shouldBe 400

      invitationRepo
        .findAllForAgent(arn.value)
        .futureValue shouldBe empty

    }

    "return BadRequest 400 status and and JSON Error when clientIdType is not valid for service" in {
      val suppliedClientId = nino.value
      val suppliedClientIdType = "NotValidClientIdType"
      val service = MtdIt.id
      val clientName = "DummyClientName"

      val createInvitationInputData: CreateInvitationInputData = CreateInvitationInputData(
        inputSuppliedClientId = suppliedClientId,
        inputSuppliedClientIdType = suppliedClientIdType,
        clientName = clientName,
        inputService = service
      )

      givenAuditConnector()

      invitationRepo
        .findAllForAgent(arn.value)
        .futureValue shouldBe empty

      val result =
        doAgentPostRequest(
          s"/agent-client-relationships/agent/${arn.value}/authorisation-request",
          Json.toJson(createInvitationInputData).toString()
        )
      result.status shouldBe 400

      val message =
        s"""Unsupported clientIdType "${createInvitationInputData.inputSuppliedClientIdType}", for service type "${createInvitationInputData.inputService}"""".stripMargin
      result.json shouldBe toJson(ErrorBody("UNSUPPORTED_CLIENT_ID_TYPE", message))

      invitationRepo
        .findAllForAgent(arn.value)
        .futureValue shouldBe empty

    }

  }
}
