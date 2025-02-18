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
import uk.gov.hmrc.agentclientrelationships.connectors.{AgentFiRelationshipConnector, EnrolmentStoreProxyConnector}
import uk.gov.hmrc.agentclientrelationships.model.{EmailInformation, Pending, Rejected}
import uk.gov.hmrc.agentclientrelationships.model.invitation.CreateInvitationRequest
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.ErrorBody
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentclientrelationships.services.{DeleteRelationshipsServiceWithAcr, InvitationService}
import uk.gov.hmrc.agentclientrelationships.stubs.{AfiRelationshipStub, AgentAssuranceStubs, ClientDetailsStub, EmailStubs}
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentmtdidentifiers.model.Service._
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.auth.core.AuthConnector

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import scala.concurrent.ExecutionContext

class InvitationControllerISpec
    extends BaseControllerISpec
    with ClientDetailsStub
    with AfiRelationshipStub
    with AgentAssuranceStubs
    with EmailStubs
    with TestData {

  val invitationService: InvitationService = app.injector.instanceOf[InvitationService]
  val authConnector: AuthConnector = app.injector.instanceOf[AuthConnector]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val es: EnrolmentStoreProxyConnector = app.injector.instanceOf[EnrolmentStoreProxyConnector]
  val deleteRelationshipService: DeleteRelationshipsServiceWithAcr =
    app.injector.instanceOf[DeleteRelationshipsServiceWithAcr]
  val agentFiRelationshipConnector: AgentFiRelationshipConnector =
    app.injector.instanceOf[AgentFiRelationshipConnector]

  val controller =
    new InvitationController(
      invitationService,
      authConnector,
      appConfig,
      stubControllerComponents()
    )

  val invitationRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val partialAuthRepository: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]

  val clientName = "DummyClientName"
  val baseInvitationInputData: CreateInvitationRequest =
    CreateInvitationRequest(nino.value, NinoType.id, clientName, MtdIt.id, Some("personal"))

  def allServices: Map[String, CreateInvitationRequest] = Map(
    HMRCMTDIT -> baseInvitationInputData,
    HMRCPIR   -> baseInvitationInputData.copy(service = HMRCPIR),
    HMRCMTDVAT -> baseInvitationInputData
      .copy(service = HMRCMTDVAT, clientId = vrn.value, suppliedClientIdType = VrnType.id),
    HMRCTERSORG -> baseInvitationInputData
      .copy(service = HMRCTERSORG, clientId = utr.value, suppliedClientIdType = UtrType.id),
    HMRCTERSNTORG -> baseInvitationInputData
      .copy(service = HMRCTERSNTORG, clientId = urn.value, suppliedClientIdType = UrnType.id),
    HMRCCGTPD -> baseInvitationInputData
      .copy(service = HMRCCGTPD, clientId = cgtRef.value, suppliedClientIdType = CgtRefType.id),
    HMRCPPTORG -> baseInvitationInputData
      .copy(service = HMRCPPTORG, clientId = pptRef.value, suppliedClientIdType = PptRefType.id),
    HMRCCBCORG -> baseInvitationInputData
      .copy(service = HMRCCBCORG, clientId = cbcId.value, suppliedClientIdType = CbcIdType.id),
    HMRCCBCNONUKORG -> baseInvitationInputData.copy(
      service = HMRCCBCNONUKORG,
      clientId = cbcId.value,
      suppliedClientIdType = CbcIdType.id
    ),
    HMRCPILLAR2ORG -> baseInvitationInputData.copy(
      service = HMRCPILLAR2ORG,
      clientId = plrId.value,
      suppliedClientIdType = PlrIdType.id
    ),
    HMRCMTDITSUPP -> baseInvitationInputData.copy(service = HMRCMTDITSUPP)
  )

  val testAgentRecord: TestAgentDetailsDesResponse = TestAgentDetailsDesResponse(
    uniqueTaxReference = None,
    agencyDetails = Some(
      TestAgencyDetails(
        agencyName = Some("testAgentName"),
        agencyEmail = Some("agent@email.com"),
        agencyTelephone = None,
        agencyAddress = None
      )
    ),
    suspensionDetails = None
  )

  val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.UK)
  "create invitation link" should {

    allServices.keySet.foreach(taxService =>
      s"return 201 status and valid JSON when invitation is created for $taxService" in {
        val inputData: CreateInvitationRequest = allServices(taxService)

        val clientId =
          if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) mtdItId.value else inputData.clientId
        val clientIdType =
          if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) MtdItIdType.id
          else inputData.suppliedClientIdType

        givenAuditConnector()

        if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) {
          givenMtdItIdIsKnownFor(nino, mtdItId)
        }

        givenAgentRecordFound(arn, testAgentRecord)

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
        invitation.suppliedClientId shouldBe inputData.clientId
        invitation.suppliedClientIdType shouldBe inputData.suppliedClientIdType
        invitation.clientId shouldBe clientId
        invitation.clientIdType shouldBe clientIdType
        invitation.service shouldBe inputData.service
        invitation.clientName shouldBe clientName

      }
    )

    "return 201 status and valid JSON when invitation is created for altItSa" in {
      val suppliedClientId = nino.value
      val suppliedClientIdType = NinoType.id
      val service = MtdIt.id
      val clientName = "DummyClientName"
      val clientType = Some("personal")

      val createInvitationInputData: CreateInvitationRequest = CreateInvitationRequest(
        clientId = suppliedClientId,
        suppliedClientIdType = suppliedClientIdType,
        clientName = clientName,
        service = service,
        clientType = clientType
      )

      val inputData: CreateInvitationRequest = baseInvitationInputData

      givenAuditConnector()
      givenMtdItIdIsUnKnownFor(nino)

      givenAgentRecordFound(arn, testAgentRecord)

      val result =
        doAgentPostRequest(
          s"/agent-client-relationships/agent/${arn.value}/authorisation-request",
          Json.toJson(createInvitationInputData).toString()
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
      invitation.suppliedClientId shouldBe inputData.clientId
      invitation.suppliedClientIdType shouldBe inputData.suppliedClientIdType
      invitation.clientId shouldBe suppliedClientId
      invitation.clientIdType shouldBe suppliedClientIdType
      invitation.service shouldBe inputData.service
      invitation.clientName shouldBe clientName
    }

    "return NotImplemented 501 status and JSON Error If service is not supported" in {
      val suppliedClientId = nino.value
      val suppliedClientIdType = NinoType.id
      val service = "HMRC-NOT-SUPPORTED"
      val clientName = "DummyClientName"
      val clientType = Some("personal")

      val createInvitationInputData: CreateInvitationRequest = CreateInvitationRequest(
        clientId = suppliedClientId,
        suppliedClientIdType = suppliedClientIdType,
        clientName = clientName,
        service = service,
        clientType = clientType
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

      val message = s"""Unsupported service "${createInvitationInputData.service}""""
      result.json shouldBe toJson(ErrorBody("UNSUPPORTED_SERVICE", message))
    }

    "return BadRequest 400 status when clientId is not valid for service" in {
      val suppliedClientId = "NotValidNino"
      val suppliedClientIdType = NinoType.id
      val service = MtdIt.id
      val clientName = "DummyClientName"
      val clientType = Some("personal")

      val createInvitationInputData: CreateInvitationRequest = CreateInvitationRequest(
        clientId = suppliedClientId,
        suppliedClientIdType = suppliedClientIdType,
        clientName = clientName,
        service = service,
        clientType = clientType
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
      val clientType = Some("personal")

      val createInvitationInputData: CreateInvitationRequest = CreateInvitationRequest(
        clientId = suppliedClientId,
        suppliedClientIdType = suppliedClientIdType,
        clientName = clientName,
        service = service,
        clientType = clientType
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
        s"""Unsupported clientIdType "${createInvitationInputData.suppliedClientIdType}", for service type "${createInvitationInputData.service}"""".stripMargin
      result.json shouldBe toJson(ErrorBody("UNSUPPORTED_CLIENT_ID_TYPE", message))

      invitationRepo
        .findAllForAgent(arn.value)
        .futureValue shouldBe empty

    }

    "return BadRequest 400 status and and JSON Error when clientType is not valid for service" in {
      val suppliedClientId = nino.value
      val suppliedClientIdType = NinoType.id
      val service = MtdIt.id
      val clientName = "DummyClientName"
      val clientType = Some("invalid")

      val createInvitationInputData: CreateInvitationRequest = CreateInvitationRequest(
        clientId = suppliedClientId,
        suppliedClientIdType = suppliedClientIdType,
        clientName = clientName,
        service = service,
        clientType = clientType
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
        s"""Unsupported clientType "${createInvitationInputData.clientType}"""".stripMargin
      result.json shouldBe toJson(ErrorBody("UNSUPPORTED_CLIENT_TYPE", message))

      invitationRepo
        .findAllForAgent(arn.value)
        .futureValue shouldBe empty

    }

  }

  "reject invitation" should {

    allServices.keySet.foreach(taxService =>
      s"return 201 status and valid JSON when invitation is created for $taxService" in {
        val inputData: CreateInvitationRequest = allServices(taxService)
        val emailInfo = EmailInformation(
          to = Seq("abc@abc.com"),
          templateId = "client_rejected_authorisation_request",
          parameters = Map(
            "agencyName" -> "My Agency",
            "clientName" -> "Erling Haal",
            "expiryDate" -> LocalDate.now().format(dateFormatter),
            "service"    -> taxService
          )
        )

        val clientId =
          if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) mtdItId.value else inputData.clientId
        val clientIdType =
          if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) MtdItIdType.id
          else inputData.suppliedClientIdType

        val clientIdentifier = ClientIdentifier(clientId, clientIdType)

        givenAgentRecordFound(arn, agentRecordResponse)

        await(
          invitationRepo.create(
            arn.value,
            Service.forId(taxService),
            clientIdentifier,
            clientIdentifier,
            "Erling Haal",
            "testAgentName",
            "agent@email.com",
            LocalDate.now(),
            Some("personal")
          )
        )

        val pendingInvitation = invitationRepo
          .findAllForAgent(arn.value)
          .futureValue
          .head

        val result =
          doAgentPutRequest(
            s"/agent-client-relationships/client/authorisation-response/reject/${pendingInvitation.invitationId}"
          )
        result.status shouldBe 204

        val invitationSeq = invitationRepo
          .findAllForAgent(arn.value)
          .futureValue

        invitationSeq.size shouldBe 1
        invitationSeq.head.status shouldBe Rejected

        verifyAgentRecordFoundSent(arn)
        verifyRejectInvitationSent(emailInfo)
      }
    )

    s"return NoFound status when no Pending Invitation " in {

      val result =
        doAgentPutRequest(
          s"/agent-client-relationships/client/authorisation-response/reject/123456"
        )
      result.status shouldBe 404

      val invitationSeq = invitationRepo
        .findAllForAgent(arn.value)
        .futureValue

      invitationSeq.size shouldBe 0

    }

  }

  "replaceUrnWithUtr" should {

    val urn = "XXTRUST12345678"
    val utr = "1234567890"
    val requestJson = Json.obj("utr" -> utr)

    "return 204 when an invitation is found and updated" in {
      await(
        invitationRepo.create(
          arn.value,
          Service.forId(HMRCTERSORG),
          Urn(urn),
          Urn(urn),
          "Erling Haal",
          "testAgentName",
          "agent@email.com",
          LocalDate.now(),
          Some("personal")
        )
      )
      val result = doAgentPostRequest(
        s"/agent-client-relationships/invitations/trusts-enrolment-orchestrator/$urn/update",
        requestJson
      )
      val updatedInvitation = await(invitationRepo.findAllForAgent(arn.value)).head

      result.status shouldBe 204
      updatedInvitation.clientId shouldBe utr
      updatedInvitation.clientIdType shouldBe UtrType.id
      updatedInvitation.suppliedClientId shouldBe utr
      updatedInvitation.suppliedClientIdType shouldBe UtrType.id
    }

    "return 404 when no invitation was found" in {
      val result = doAgentPostRequest(
        s"/agent-client-relationships/invitations/trusts-enrolment-orchestrator/$urn/update",
        requestJson
      )
      result.status shouldBe 404
    }

    "return 400 when request body is not JSON" in {
      val result = doAgentPostRequest(
        s"/agent-client-relationships/invitations/trusts-enrolment-orchestrator/$urn/update",
        "stringBody"
      )
      result.status shouldBe 400
    }

    "return 500 when the JSON body is invalid" in {
      val invalidJson = Json.obj("abc" -> "xyz")
      val result = doAgentPostRequest(
        s"/agent-client-relationships/invitations/trusts-enrolment-orchestrator/$urn/update",
        invalidJson
      )
      result.status shouldBe 500
      result.body should include("JsResultException")
    }
  }
}
