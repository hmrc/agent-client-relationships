/*
 * Copyright 2025 HM Revenue & Customs
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

import play.api.http.Status.UNPROCESSABLE_ENTITY
import play.api.i18n.Lang
import play.api.i18n.Langs
import play.api.i18n.MessagesApi
import play.api.libs.json.Json
import play.api.libs.json.Json.toJson
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AuditService
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.AgentFiRelationshipConnector
import uk.gov.hmrc.agentclientrelationships.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service._
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiFailureResponse.{ErrorBody => AFRErrorBody}
import uk.gov.hmrc.agentclientrelationships.model.invitation.CreateInvitationRequest
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.{ErrorBody => IFRErrorBody}
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.services.DeleteRelationshipsService
import uk.gov.hmrc.agentclientrelationships.services.InvitationService
import uk.gov.hmrc.agentclientrelationships.services.ValidationService
import uk.gov.hmrc.agentclientrelationships.stubs._
import uk.gov.hmrc.agentclientrelationships.support.TestData
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
with HipStub
with TestData {

  val invitationService: InvitationService = app.injector.instanceOf[InvitationService]
  val validationService: ValidationService = app.injector.instanceOf[ValidationService]
  val auditService: AuditService = app.injector.instanceOf[AuditService]
  val authConnector: AuthConnector = app.injector.instanceOf[AuthConnector]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val es: EnrolmentStoreProxyConnector = app.injector.instanceOf[EnrolmentStoreProxyConnector]
  val deleteRelationshipService: DeleteRelationshipsService = app.injector.instanceOf[DeleteRelationshipsService]
  val agentFiRelationshipConnector: AgentFiRelationshipConnector = app.injector.instanceOf[AgentFiRelationshipConnector]
  lazy val messagesApi: MessagesApi = app.injector.instanceOf[MessagesApi]
  implicit val langs: Langs = app.injector.instanceOf[Langs]
  implicit val lang: Lang = langs.availables.head

  val controller =
    new InvitationController(
      invitationService,
      auditService,
      validationService,
      authConnector,
      appConfig,
      stubControllerComponents()
    )

  val invitationRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val partialAuthRepository: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]

  val clientName = "DummyClientName"
  val baseInvitationInputData: CreateInvitationRequest = CreateInvitationRequest(
    nino.value,
    NinoType.id,
    clientName,
    MtdIt.id,
    Some("personal")
  )

  def allServices: Map[String, CreateInvitationRequest] = Map(
    HMRCMTDIT -> baseInvitationInputData,
    HMRCPIR -> baseInvitationInputData.copy(service = HMRCPIR),
    HMRCMTDVAT -> baseInvitationInputData
      .copy(
        service = HMRCMTDVAT,
        clientId = vrn.value,
        suppliedClientIdType = VrnType.id
      ),
    HMRCTERSORG -> baseInvitationInputData
      .copy(
        service = HMRCTERSORG,
        clientId = utr.value,
        suppliedClientIdType = UtrType.id
      ),
    HMRCTERSNTORG -> baseInvitationInputData
      .copy(
        service = HMRCTERSNTORG,
        clientId = urn.value,
        suppliedClientIdType = UrnType.id
      ),
    HMRCCGTPD -> baseInvitationInputData
      .copy(
        service = HMRCCGTPD,
        clientId = cgtRef.value,
        suppliedClientIdType = CgtRefType.id
      ),
    HMRCPPTORG -> baseInvitationInputData
      .copy(
        service = HMRCPPTORG,
        clientId = pptRef.value,
        suppliedClientIdType = PptRefType.id
      ),
    HMRCCBCORG -> baseInvitationInputData
      .copy(
        service = HMRCCBCORG,
        clientId = cbcId.value,
        suppliedClientIdType = CbcIdType.id
      ),
    HMRCCBCNONUKORG -> baseInvitationInputData
      .copy(
        service = HMRCCBCNONUKORG,
        clientId = cbcId.value,
        suppliedClientIdType = CbcIdType.id
      ),
    HMRCPILLAR2ORG -> baseInvitationInputData
      .copy(
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

  val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.UK)
  "create invitation" should {

    allServices.keySet
      .foreach(taxService =>
        s"return 201 status and valid JSON when invitation is created for $taxService" in {
          val inputData: CreateInvitationRequest = allServices(taxService)

          val clientId =
            if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
              mtdItId.value
            else
              inputData.clientId
          val clientIdType =
            if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
              MtdItIdType.id
            else
              inputData.suppliedClientIdType

          givenAuditConnector()

          if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) {
            givenMtdItIdIsKnownFor(nino, mtdItId)
          }

          givenAgentRecordFound(arn, testAgentRecord)
          givenUserAuthorised()

          val requestPath = s"/agent-client-relationships/agent/${arn.value}/authorisation-request"
          val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
          result.status shouldBe 201

          val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(arn.value).futureValue

          invitations.size shouldBe 1

          val invitation = invitations.head

          result.json shouldBe Json.obj("invitationId" -> invitation.invitationId)

          invitation.status shouldBe Pending
          invitation.suppliedClientId shouldBe inputData.clientId
          invitation.suppliedClientIdType shouldBe inputData.suppliedClientIdType
          invitation.clientId shouldBe clientId
          invitation.clientIdType shouldBe clientIdType
          invitation.service shouldBe inputData.service
          invitation.clientName shouldBe clientName

          verifyCreateInvitationAuditSent(requestPath, invitation)
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
      givenUserAuthorised()

      val result = doAgentPostRequest(
        s"/agent-client-relationships/agent/${arn.value}/authorisation-request",
        Json.toJson(createInvitationInputData).toString()
      )
      result.status shouldBe 201

      val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(arn.value).futureValue

      invitations.size shouldBe 1

      val invitation = invitations.head

      result.json shouldBe Json.obj("invitationId" -> invitation.invitationId)

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
      givenUserAuthorised()

      invitationRepo.findAllForAgent(arn.value).futureValue shouldBe empty

      val result = doAgentPostRequest(
        s"/agent-client-relationships/agent/${arn.value}/authorisation-request",
        Json.toJson(createInvitationInputData).toString()
      )
      result.status shouldBe 501

      invitationRepo.findAllForAgent(arn.value).futureValue shouldBe empty

      val message = s"""Unsupported service "${createInvitationInputData.service}""""
      result.json shouldBe toJson(IFRErrorBody("UNSUPPORTED_SERVICE", message))
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
      givenUserAuthorised()

      invitationRepo.findAllForAgent(arn.value).futureValue shouldBe empty

      val result = doAgentPostRequest(
        s"/agent-client-relationships/agent/${arn.value}/authorisation-request",
        Json.toJson(createInvitationInputData).toString()
      )
      result.status shouldBe 400

      invitationRepo.findAllForAgent(arn.value).futureValue shouldBe empty

    }

    "return BadRequest 400 status and JSON Error when clientIdType is not valid for service" in {
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
      givenUserAuthorised()

      invitationRepo.findAllForAgent(arn.value).futureValue shouldBe empty

      val result = doAgentPostRequest(
        s"/agent-client-relationships/agent/${arn.value}/authorisation-request",
        Json.toJson(createInvitationInputData).toString()
      )
      result.status shouldBe 400

      val message =
        s"""Unsupported clientIdType "${createInvitationInputData.suppliedClientIdType}", for service type "${createInvitationInputData.service}"""".stripMargin
      result.json shouldBe toJson(IFRErrorBody("UNSUPPORTED_CLIENT_ID_TYPE", message))

      invitationRepo.findAllForAgent(arn.value).futureValue shouldBe empty

    }

    "return BadRequest 400 status and JSON Error when clientType is not valid for service" in {
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
      givenUserAuthorised()

      invitationRepo.findAllForAgent(arn.value).futureValue shouldBe empty

      val result = doAgentPostRequest(
        s"/agent-client-relationships/agent/${arn.value}/authorisation-request",
        Json.toJson(createInvitationInputData).toString()
      )
      result.status shouldBe 400

      val message = s"""Unsupported clientType "${createInvitationInputData.clientType}"""".stripMargin
      result.json shouldBe toJson(IFRErrorBody("UNSUPPORTED_CLIENT_TYPE", message))

      invitationRepo.findAllForAgent(arn.value).futureValue shouldBe empty

    }

    "return 401 when auth token is missing" in {
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

      requestIsNotAuthenticated()

      invitationRepo.findAllForAgent(arn.value).futureValue shouldBe empty

      val result = doAgentPostRequest(
        s"/agent-client-relationships/agent/${arn.value}/authorisation-request",
        Json.toJson(createInvitationInputData).toString()
      )
      result.status shouldBe 401

      invitationRepo.findAllForAgent(arn.value).futureValue shouldBe empty

    }

  }

  "reject invitation" should {

    allServices.keySet
      .foreach(taxService =>
        s"return 201 status and valid JSON when invitation is created for $taxService" in {
          val inputData: CreateInvitationRequest = allServices(taxService)
          val emailInfo = EmailInformation(
            to = Seq("agent@email.com"),
            templateId = "client_rejected_authorisation_request",
            parameters = Map(
              "agencyName" -> "testAgentName",
              "clientName" -> "Erling Haal",
              "expiryDate" -> LocalDate.now().format(dateFormatter),
              "service" -> messagesApi(s"service.$taxService")
            )
          )

          val clientId =
            if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
              mtdItId.value
            else
              inputData.clientId
          val clientIdType =
            if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
              MtdItIdType.id
            else
              inputData.suppliedClientIdType

          val clientIdentifier = ClientIdentifier(clientId, clientIdType)

          givenUserIsSubscribedClient(clientIdentifier.underlying)
          if (taxService == HMRCCBCORG)
            givenCbcUkExistsInES(cbcId, utr.value)
          givenEmailSent(emailInfo)

          val pendingInvitation = await(
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
          val requestPath = s"/agent-client-relationships/client/authorisation-response/reject/${pendingInvitation.invitationId}"
          val result = doAgentPutRequest(requestPath)
          result.status shouldBe 204

          val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(arn.value).futureValue

          invitations.size shouldBe 1
          invitations.head.status shouldBe Rejected

          verifyInvitationEmailInfoSent(emailInfo)
          verifyRespondToInvitationAuditSent(
            requestPath,
            pendingInvitation,
            accepted = false,
            isStride = false
          )
        }
      )

    s"return NoFound status when no Pending Invitation " in {

      val result = doAgentPutRequest(s"/agent-client-relationships/client/authorisation-response/reject/123456")
      result.status shouldBe 404

      val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(arn.value).futureValue

      invitations.size shouldBe 0

    }

  }

  "return 500 when the invitation is in an unexpected state" should {

    allServices.keySet
      .foreach(taxService =>
        s"return 201 status and valid JSON when invitation is created for $taxService" in {
          val inputData: CreateInvitationRequest = allServices(taxService)
          val emailInfo = EmailInformation(
            to = Seq("agent@email.com"),
            templateId = "client_rejected_authorisation_request",
            parameters = Map(
              "agencyName" -> "testAgentName",
              "clientName" -> "Erling Haal",
              "expiryDate" -> LocalDate.now().format(dateFormatter),
              "service" -> messagesApi(s"service.$taxService")
            )
          )

          val clientId =
            if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
              mtdItId.value
            else
              inputData.clientId
          val clientIdType =
            if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
              MtdItIdType.id
            else
              inputData.suppliedClientIdType

          val clientIdentifier = ClientIdentifier(clientId, clientIdType)

          givenUserIsSubscribedClient(clientIdentifier.underlying)
          if (taxService == HMRCCBCORG)
            givenCbcUkExistsInES(cbcId, utr.value)
          givenEmailSent(emailInfo)

          val pendingInvitation = await(
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

          val expiredInvitation =
            invitationRepo.updateStatus(
              invitationId = pendingInvitation.invitationId,
              status = Expired
            ).futureValue

          val requestPath = s"/agent-client-relationships/client/authorisation-response/reject/${expiredInvitation.invitationId}"
          val result = doAgentPutRequest(requestPath)
          result.status shouldBe 500

          val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(arn.value).futureValue

          invitations.size shouldBe 1
          invitations.head.status shouldBe Expired
        }
      )

    s"return NoFound status when no Pending Invitation " in {

      val result = doAgentPutRequest(s"/agent-client-relationships/client/authorisation-response/reject/123456")
      result.status shouldBe 404

      val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(arn.value).futureValue

      invitations.size shouldBe 0

    }

  }

  "replaceUrnWithUtr" should {

    val urn = "XXTRUST12345678"
    val utr = "1234567890"
    val requestJson = Json.obj("utr" -> utr)

    "return 204 when an invitation is found and updated" in {
      givenUserAuthorised()
      await(
        invitationRepo.create(
          arn.value,
          Service.forId(HMRCTERSNTORG),
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
      givenUserAuthorised()
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
      givenUserAuthorised()
      val invalidJson = Json.obj("abc" -> "xyz")
      val result = doAgentPostRequest(
        s"/agent-client-relationships/invitations/trusts-enrolment-orchestrator/$urn/update",
        invalidJson
      )
      result.status shouldBe 500
      result.body should include("JsResultException")
    }
  }

  "cancel invitation" should {

    allServices.keySet
      .foreach(taxService => {
        s"return 204 status when invitation is cancelled for $taxService" in {
          val inputData: CreateInvitationRequest = allServices(taxService)
          val clientId =
            if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
              mtdItId.value
            else
              inputData.clientId
          val clientIdType =
            if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
              MtdItIdType.id
            else
              inputData.suppliedClientIdType

          val clientIdentifier = ClientIdentifier(clientId, clientIdType)

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
          ).futureValue

          val pendingInvitation = invitationRepo.findAllForAgent(arn.value).futureValue.head
          val testUrl = s"/agent-client-relationships/agent/cancel-invitation/${pendingInvitation.invitationId}"
          val fakeRequest = FakeRequest("PUT", testUrl)
          givenAuthorisedAsValidAgent(fakeRequest, arn.value)

          val result = doAgentPutRequest(testUrl)
          result.status shouldBe 204
          result.body shouldBe ""

          val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(arn.value).futureValue

          invitations.size shouldBe 1
          invitations.head.status shouldBe Cancelled

        }

        s"return 409 status when invitation request is already cancelled for $taxService" in {
          val inputData: CreateInvitationRequest = allServices(taxService)
          val clientId =
            if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
              mtdItId.value
            else
              inputData.clientId
          val clientIdType =
            if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
              MtdItIdType.id
            else
              inputData.suppliedClientIdType

          val clientIdentifier = ClientIdentifier(clientId, clientIdType)

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
          ).futureValue

          val pendingInvitation = invitationRepo.findAllForAgent(arn.value).futureValue.head
          val testUrl = s"/agent-client-relationships/agent/cancel-invitation/${pendingInvitation.invitationId}"
          val fakeRequest = FakeRequest("PUT", testUrl)
          givenAuthorisedAsValidAgent(fakeRequest, arn.value)

          val result = doAgentPutRequest(testUrl)
          result.status shouldBe 204
          result.body shouldBe ""

          val result1 = doAgentPutRequest(testUrl)
          result1.status shouldBe 409
          result1.json shouldBe Json.toJson(AFRErrorBody("INVALID_INVITATION_STATUS"))

          val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(arn.value).futureValue

          invitations.size shouldBe 1
          invitations.head.status shouldBe Cancelled
        }

        s"return 422 when the auth ARN does not match the ARN in the found invitation for $taxService" in {
          val inputData: CreateInvitationRequest = allServices(taxService)
          val clientId =
            if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
              mtdItId.value
            else
              inputData.clientId
          val clientIdType =
            if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
              MtdItIdType.id
            else
              inputData.suppliedClientIdType

          val clientIdentifier = ClientIdentifier(clientId, clientIdType)

          invitationRepo
            .create(
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
            .futureValue

          val pendingInvitation =
            invitationRepo
              .findAllForAgent(arn.value)
              .futureValue
              .head
          val testUrl = s"/agent-client-relationships/agent/cancel-invitation/${pendingInvitation.invitationId}"
          val fakeRequest = FakeRequest("PUT", testUrl)
          givenAuthorisedAsValidAgent(fakeRequest, arn2.value)

          val result = doAgentPutRequest(testUrl)
          result.status shouldBe UNPROCESSABLE_ENTITY
          result.json shouldBe Json.toJson(AFRErrorBody("NO_PERMISSION_ON_AGENCY"))

          val invitations: Seq[Invitation] =
            invitationRepo
              .findAllForAgent(arn.value)
              .futureValue

          invitations.size shouldBe 1
          invitations.head.status shouldBe Pending
        }

        s"return 422 when status of the invitation is invalid for $taxService" in {
          val inputData: CreateInvitationRequest = allServices(taxService)
          val clientId =
            if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
              mtdItId.value
            else
              inputData.clientId
          val clientIdType =
            if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
              MtdItIdType.id
            else
              inputData.suppliedClientIdType

          val clientIdentifier = ClientIdentifier(clientId, clientIdType)

          invitationRepo
            .create(
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
            .futureValue

          val pendingInvitation =
            invitationRepo
              .findAllForAgent(arn.value)
              .futureValue
              .head

          invitationRepo
            .updateStatus(
              pendingInvitation.invitationId,
              Accepted
            )
            .futureValue

          val testUrl = s"/agent-client-relationships/agent/cancel-invitation/${pendingInvitation.invitationId}"
          val fakeRequest = FakeRequest("PUT", testUrl)
          givenAuthorisedAsValidAgent(fakeRequest, arn2.value)

          val result = doAgentPutRequest(testUrl)
          result.status shouldBe UNPROCESSABLE_ENTITY
          result.json shouldBe Json.toJson(AFRErrorBody("INVALID_INVITATION_STATUS"))

          val invitations: Seq[Invitation] =
            invitationRepo
              .findAllForAgent(arn.value)
              .futureValue

          invitations.size shouldBe 1
          invitations.head.status shouldBe Accepted
        }

      })

    s"return 422 status when invitation is not found" in {
      val testUrl = s"/agent-client-relationships/agent/cancel-invitation/FKTSJ6B9HZJBS"
      val fakeRequest = FakeRequest("PUT", testUrl)
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      val result = doAgentPutRequest(testUrl)
      result.status shouldBe UNPROCESSABLE_ENTITY
      result.json shouldBe Json.toJson(AFRErrorBody("INVITATION_NOT_FOUND"))

      val invitations: Seq[Invitation] =
        invitationRepo
          .findAllForAgent(arn.value)
          .futureValue
      invitations.size shouldBe 0
    }

  }

}
