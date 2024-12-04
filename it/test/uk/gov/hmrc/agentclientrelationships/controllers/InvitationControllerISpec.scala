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

import play.api.http.Status.NOT_FOUND
import play.api.libs.json.Json
import play.api.libs.json.Json.toJson
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.{EnrolmentStoreProxyConnector, PirRelationshipConnector}
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.ErrorBody
import uk.gov.hmrc.agentclientrelationships.model.invitation.{CreateInvitationRequest, DeleteInvitationRequest}
import uk.gov.hmrc.agentclientrelationships.model.{DeAuthorised, InvitationEvent, PartialAuth, Pending}
import uk.gov.hmrc.agentclientrelationships.repository.{DeleteRecord, InvitationsEventStoreRepository, InvitationsRepository, SyncStatus}
import uk.gov.hmrc.agentclientrelationships.services.{DeleteRelationshipsServiceWithAcr, InvitationService}
import uk.gov.hmrc.agentclientrelationships.stubs.{AfiRelationshipStub, ClientDetailsStub}
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{MtdIt, PersonalIncomeRecord, Vat}
import uk.gov.hmrc.agentmtdidentifiers.model.{ClientIdentifier, MtdItIdType, NinoType, VrnType}
import uk.gov.hmrc.auth.core.AuthConnector

import java.time.{Instant, LocalDateTime}
import scala.concurrent.ExecutionContext

class InvitationControllerISpec
    extends RelationshipsBaseControllerISpec
    with ClientDetailsStub
    with AfiRelationshipStub
    with TestData {

  val invitationService: InvitationService = app.injector.instanceOf[InvitationService]
  val authConnector: AuthConnector = app.injector.instanceOf[AuthConnector]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val es: EnrolmentStoreProxyConnector = app.injector.instanceOf[EnrolmentStoreProxyConnector]
  val deleteRelationshipService: DeleteRelationshipsServiceWithAcr =
    app.injector.instanceOf[DeleteRelationshipsServiceWithAcr]
  val pirRelationshipConnector: PirRelationshipConnector =
    app.injector.instanceOf[PirRelationshipConnector]

  val controller =
    new InvitationController(
      invitationService,
      authConnector,
      appConfig,
      es,
      deleteRelationshipService,
      pirRelationshipConnector,
      stubControllerComponents()
    )

  def invitationRepo: InvitationsRepository = new InvitationsRepository(mongoComponent, appConfig)
  def invitationEventsRepo: InvitationsEventStoreRepository = new InvitationsEventStoreRepository(mongoComponent)

  "create invitation link" should {

    // TODO WG - create test scenario for all Services
    // TODO WG - test expiry date of Invitation
    "return 201 status and valid JSON when invitation is created for Itsa " in {
      val suppliedClientId = nino.value
      val suppliedClientIdType = NinoType.id
      val clientId = mtdItId.value
      val clientIdType = MtdItIdType.id
      val service = MtdIt.id
      val clientName = "DummyClientName"

      val createInvitationInputData: CreateInvitationRequest = CreateInvitationRequest(
        clientId = suppliedClientId,
        suppliedClientIdType = suppliedClientIdType,
        clientName = clientName,
        service = service
      )

      givenAuditConnector()

      givenMtdItIdIsKnownFor(nino, mtdItId)

      invitationRepo
        .findAllForAgent(arn.value)
        .futureValue shouldBe empty

      val result =
        doAgentPostRequest(
          s"/agent-client-relationships/agent/${arn.value}/authorization-request",
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
      invitation.suppliedClientId shouldBe suppliedClientId
      invitation.suppliedClientIdType shouldBe suppliedClientIdType
      invitation.clientId shouldBe clientId
      invitation.clientIdType shouldBe clientIdType
      invitation.service shouldBe service
      invitation.clientName shouldBe clientName

    }

    "return 201 status and valid JSON when invitation is created for VAT " in {
      val suppliedClientId = vrn.value
      val suppliedClientIdType = VrnType.id
      val clientId = vrn.value
      val clientIdType = VrnType.id
      val service = Vat.id
      val clientName = "DummyClientName"

      val createInvitationInputData: CreateInvitationRequest = CreateInvitationRequest(
        clientId = suppliedClientId,
        suppliedClientIdType = suppliedClientIdType,
        clientName = clientName,
        service = service
      )

      givenAuditConnector()

      invitationRepo
        .findAllForAgent(arn.value)
        .futureValue shouldBe empty

      val result =
        doAgentPostRequest(
          s"/agent-client-relationships/agent/${arn.value}/authorization-request",
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
      invitation.suppliedClientId shouldBe suppliedClientId
      invitation.suppliedClientIdType shouldBe suppliedClientIdType
      invitation.clientId shouldBe clientId
      invitation.clientIdType shouldBe clientIdType
      invitation.service shouldBe service
      invitation.clientName shouldBe clientName

    }

    "return Forbidden 403 status and JSON Error when client registration not found for ItSa" in {
      val suppliedClientId = nino.value
      val suppliedClientIdType = NinoType.id
      val service = MtdIt.id
      val clientName = "DummyClientName"

      val createInvitationInputData: CreateInvitationRequest = CreateInvitationRequest(
        clientId = suppliedClientId,
        suppliedClientIdType = suppliedClientIdType,
        clientName = clientName,
        service = service
      )

      givenAuditConnector()

      givenMtdItIdIsUnKnownFor(nino)

      invitationRepo
        .findAllForAgent(arn.value)
        .futureValue shouldBe empty

      val result =
        doAgentPostRequest(
          s"/agent-client-relationships/agent/${arn.value}/authorization-request",
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

      val createInvitationInputData: CreateInvitationRequest = CreateInvitationRequest(
        clientId = suppliedClientId,
        suppliedClientIdType = suppliedClientIdType,
        clientName = clientName,
        service = service
      )

      givenAuditConnector()

      invitationRepo
        .findAllForAgent(arn.value)
        .futureValue shouldBe empty

      val result =
        doAgentPostRequest(
          s"/agent-client-relationships/agent/${arn.value}/authorization-request",
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

      val createInvitationInputData: CreateInvitationRequest = CreateInvitationRequest(
        clientId = suppliedClientId,
        suppliedClientIdType = suppliedClientIdType,
        clientName = clientName,
        service = service
      )

      givenAuditConnector()

      invitationRepo
        .findAllForAgent(arn.value)
        .futureValue shouldBe empty

      val result =
        doAgentPostRequest(
          s"/agent-client-relationships/agent/${arn.value}/authorization-request",
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

      val createInvitationInputData: CreateInvitationRequest = CreateInvitationRequest(
        clientId = suppliedClientId,
        suppliedClientIdType = suppliedClientIdType,
        clientName = clientName,
        service = service
      )

      givenAuditConnector()

      invitationRepo
        .findAllForAgent(arn.value)
        .futureValue shouldBe empty

      val result =
        doAgentPostRequest(
          s"/agent-client-relationships/agent/${arn.value}/authorization-request",
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

  }

  "/agent/:arn/remove-authorization" should {
    val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/remove-authorization"

    def verifyClientRemovedAgentServiceAuthorisationAuditSent(
      arn: String,
      clientId: String,
      clientIdType: String,
      service: String,
      currentUserAffinityGroup: String,
      authProviderId: String,
      authProviderIdType: String
    ) =
      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation,
        detail = Map(
          "agentReferenceNumber"     -> arn,
          "clientId"                 -> clientId,
          "clientIdType"             -> clientIdType,
          "service"                  -> service,
          "currentUserAffinityGroup" -> currentUserAffinityGroup,
          "authProviderId"           -> authProviderId,
          "authProviderIdType"       -> authProviderIdType
        ),
        tags = Map("transactionName" -> "client terminated agent:service authorisation", "path" -> requestPath)
      )

    def verifyHmrcRemovedAgentServiceAuthorisation(
      arn: String,
      clientId: String,
      service: String,
      authProviderId: String,
      authProviderIdType: String
    ) =
      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.HmrcRemovedAgentServiceAuthorisation,
        detail = Map(
          "authProviderId"       -> authProviderId,
          "authProviderIdType"   -> authProviderIdType,
          "agentReferenceNumber" -> arn,
          "clientId"             -> clientId,
          "service"              -> service
        ),
        tags = Map("transactionName" -> "hmrc remove agent:service authorisation", "path" -> requestPath)
      )

    "for MtdId when the relationship exists and no invitation record in Repository" should {

      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenAgentIsAllocatedAndAssignedToClient(mtdItEnrolmentKey, "bar")
        givenAgentCanBeDeallocatedInIF(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItEnrolmentKey)
        givenAdminUser("foo", "any")
        givenCacheRefresh(arn)
        givenItsaBusinessDetailsExists("nino", nino.value, mtdItId.value)
      }

      "return 204 and sent audit event" in new StubsForThisScenario {
        doAgentPostRequest(
          requestPath,
          Json.toJson(DeleteInvitationRequest(nino.value, MtdIt.id)).toString()
        ).status shouldBe 204
        verifyDeleteRecordNotExists

        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          mtdItId.value,
          "MTDITID",
          "HMRC-MTD-IT",
          "Agent",
          "ggUserId-agent",
          "GovernmentGateway"
        )
      }

    }

    "the relationship exists and the Arn matches that of current Agent user" should {

      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(mtdItEnrolmentKey, "bar")
        givenAgentCanBeDeallocatedInIF(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItEnrolmentKey)
        givenAdminUser("foo", "any")
        givenCacheRefresh(arn)
        givenItsaBusinessDetailsExists("nino", nino.value, mtdItId.value)
      }

      "resume an ongoing de-auth if unfinished ES delete record found" in new StubsForThisScenario {
        await(
          deleteRecordRepository.create(
            DeleteRecord(
              arn.value,
              Some(mtdItEnrolmentKey),
              dateTime = LocalDateTime.now.minusMinutes(1),
              syncToETMPStatus = Some(SyncStatus.Success),
              syncToESStatus = Some(SyncStatus.Failed)
            )
          )
        )
        doAgentPostRequest(
          requestPath,
          Json.toJson(DeleteInvitationRequest(nino.value, MtdIt.id)).toString()
        ).status shouldBe 204
        verifyDeleteRecordNotExists

        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          mtdItId.value,
          "MTDITID",
          "HMRC-MTD-IT",
          "Agent",
          "ggUserId-agent",
          "GovernmentGateway"
        )
      }

      "resume an ongoing de-auth if unfinished ETMP delete record found" in new StubsForThisScenario {
        await(
          deleteRecordRepository.create(
            DeleteRecord(
              arn.value,
              Some(mtdItEnrolmentKey),
              dateTime = LocalDateTime.now.minusMinutes(1),
              syncToETMPStatus = Some(SyncStatus.Failed)
            )
          )
        )
        doAgentPostRequest(
          requestPath,
          Json.toJson(DeleteInvitationRequest(nino.value, MtdIt.id)).toString()
        ).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "resume an ongoing de-auth if some delete record found" in new StubsForThisScenario {
        await(
          deleteRecordRepository.create(
            DeleteRecord(
              arn.value,
              Some(mtdItEnrolmentKey),
              dateTime = LocalDateTime.now.minusMinutes(1)
            )
          )
        )
        doAgentPostRequest(
          requestPath,
          Json.toJson(DeleteInvitationRequest(nino.value, MtdIt.id)).toString()
        ).status shouldBe 204
        verifyDeleteRecordNotExists
      }

    }

    "for PersonalIncome relationship" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
      }

      "return 204" in new StubsForThisScenario {
        givenTerminateAfiRelationshipSucceeds(arn, PersonalIncomeRecord.id, nino.value)

        doAgentPostRequest(
          requestPath,
          Json.toJson(DeleteInvitationRequest(nino.value, PersonalIncomeRecord.id)).toString()
        ).status shouldBe 204
      }

      "return 404 when Rir returns 404" in new StubsForThisScenario {
        givenTerminateAfiRelationshipFails(arn, PersonalIncomeRecord.id, nino.value, 404)

        val result = doAgentPostRequest(
          requestPath,
          Json.toJson(DeleteInvitationRequest(nino.value, PersonalIncomeRecord.id)).toString()
        )

        result.status shouldBe 404
        result.json shouldBe toJson(ErrorBody("INVITATION_NOT_FOUND", "The specified invitation was not found."))
      }

      "return 500 when Rir returns 500" in new StubsForThisScenario {
        givenTerminateAfiRelationshipFails(arn, PersonalIncomeRecord.id, nino.value)

        val result = doAgentPostRequest(
          requestPath,
          Json.toJson(DeleteInvitationRequest(nino.value, PersonalIncomeRecord.id)).toString()
        )
        result.status shouldBe 500
      }
    }

    "for alt Itsa relationship" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenMtdItIdIsKnownFor(nino, mtdItId)
      }

      "return 204 when PartialAuth exists in InvitationEvent Repo" in new StubsForThisScenario {
        await(invitationEventsRepo.create(PartialAuth, Instant.now(), arn.value, MtdIt.id, nino.value, None))

        doAgentPostRequest(
          requestPath,
          Json.toJson(DeleteInvitationRequest(nino.value, MtdIt.id)).toString()
        ).status shouldBe 204

        val invitations: Seq[InvitationEvent] = invitationEventsRepo
          .findAllForClient(MtdIt, ClientIdentifier(nino.value, MtdIt.supportedSuppliedClientIdType.id))
          .futureValue

        invitations.size shouldBe 2
        invitations.exists(_.status == PartialAuth) shouldBe true
        invitations.exists(x => x.status == DeAuthorised && x.deauthorisedBy.contains("Agent")) shouldBe true
      }

      "return Error and do not create DeAuthorised when PartialAuthdo not exists in InvitationEvent Repo" in new StubsForThisScenario {
        val result = doAgentPostRequest(
          requestPath,
          Json.toJson(DeleteInvitationRequest(nino.value, MtdIt.id)).toString()
        )

        result.status >= 400 && result.status < 600

        val invitations: Seq[InvitationEvent] = invitationEventsRepo
          .findAllForClient(MtdIt, ClientIdentifier(nino.value, MtdIt.supportedSuppliedClientIdType.id))
          .futureValue

        invitations.size shouldBe 0
      }

    }

    "when request data are incorrect" should {
      "return BadRequest 400 status when clientId is not valid for service" in {
        val result = doAgentPostRequest(
          requestPath,
          Json.toJson(DeleteInvitationRequest("IncorrectNino", MtdIt.id)).toString()
        )
        result.status shouldBe 400
        result.body shouldBe ""

      }

      "return NotImplemented 501 status and JSON Error If service is not supported" in {
        val result =
          doAgentPostRequest(
            requestPath,
            Json.toJson(DeleteInvitationRequest(nino.value, "IncorrectService")).toString()
          )
        result.status shouldBe 501

        val message = s"""Unsupported service "IncorrectService""""
        result.json shouldBe toJson(ErrorBody("UNSUPPORTED_SERVICE", message))
      }
    }

    "when MtdId business details errors" should {
      "return Forbidden 403 status and JSON Error when MtdId business details NotFound " in {
        givenItsaBusinessDetailsError(nino.value, NOT_FOUND)
        val result =
          doAgentPostRequest(
            requestPath,
            Json.toJson(DeleteInvitationRequest(nino.value, MtdIt.id)).toString()
          )
        result.status shouldBe 403

        result.json shouldBe toJson(
          ErrorBody(
            "CLIENT_REGISTRATION_NOT_FOUND",
            "The Client's MTDfB registration or SAUTR (if alt-itsa is enabled) was not found."
          )
        )
      }

      "return Forbidden 403 status and JSON Error when MtdId business details record is empty " in {
        givenEmptyItsaBusinessDetailsExists(nino.value)
        val result =
          doAgentPostRequest(
            requestPath,
            Json.toJson(DeleteInvitationRequest(nino.value, MtdIt.id)).toString()
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
