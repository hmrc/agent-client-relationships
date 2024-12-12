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
import uk.gov.hmrc.agentclientrelationships.connectors.{AgentFiRelationshipConnector, EnrolmentStoreProxyConnector}
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthRelationship
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.ErrorBody
import uk.gov.hmrc.agentclientrelationships.model.invitation.RemoveAuthorisationRequest
import uk.gov.hmrc.agentclientrelationships.repository.{DeleteRecord, InvitationsRepository, PartialAuthRepository, SyncStatus}
import uk.gov.hmrc.agentclientrelationships.services.{DeleteRelationshipsServiceWithAcr, RemoveAuthorisationService}
import uk.gov.hmrc.agentclientrelationships.stubs.{AfiRelationshipStub, ClientDetailsStub}
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{MtdIt, PersonalIncomeRecord}
import uk.gov.hmrc.auth.core.AuthConnector

import java.time.{Instant, LocalDateTime}
import scala.concurrent.ExecutionContext

class RemoveAuthorisationControllerISpec
    extends RelationshipsBaseControllerISpec
    with ClientDetailsStub
    with AfiRelationshipStub
    with TestData {

  val deAuthorisationService: RemoveAuthorisationService = app.injector.instanceOf[RemoveAuthorisationService]
  val authConnector: AuthConnector = app.injector.instanceOf[AuthConnector]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val es: EnrolmentStoreProxyConnector = app.injector.instanceOf[EnrolmentStoreProxyConnector]
  val deleteRelationshipService: DeleteRelationshipsServiceWithAcr =
    app.injector.instanceOf[DeleteRelationshipsServiceWithAcr]
  val agentFiRelationshipConnector: AgentFiRelationshipConnector =
    app.injector.instanceOf[AgentFiRelationshipConnector]

  val controller =
    new RemoveAuthorisationController(
      deAuthorisationService,
      agentFiRelationshipConnector,
      deleteRelationshipService,
      es,
      authConnector,
      appConfig,
      stubControllerComponents()
    )

  def invitationRepo: InvitationsRepository = new InvitationsRepository(mongoComponent, appConfig)
  def partialAuthRepository: PartialAuthRepository = new PartialAuthRepository(mongoComponent)

  "/agent/:arn/remove-authorisation" should {
    val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/remove-authorisation"

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
          Json.toJson(RemoveAuthorisationRequest(nino.value, MtdIt.id)).toString()
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
          Json.toJson(RemoveAuthorisationRequest(nino.value, MtdIt.id)).toString()
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
          Json.toJson(RemoveAuthorisationRequest(nino.value, MtdIt.id)).toString()
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
          Json.toJson(RemoveAuthorisationRequest(nino.value, MtdIt.id)).toString()
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
          Json.toJson(RemoveAuthorisationRequest(nino.value, PersonalIncomeRecord.id)).toString()
        ).status shouldBe 204
      }

      "return 404 when Rir returns 404" in new StubsForThisScenario {
        givenTerminateAfiRelationshipFails(arn, PersonalIncomeRecord.id, nino.value, 404)

        val result = doAgentPostRequest(
          requestPath,
          Json.toJson(RemoveAuthorisationRequest(nino.value, PersonalIncomeRecord.id)).toString()
        )

        result.status shouldBe 404
        result.json shouldBe toJson(ErrorBody("INVITATION_NOT_FOUND", "The specified invitation was not found."))
      }

      "return 500 when Rir returns 500" in new StubsForThisScenario {
        givenTerminateAfiRelationshipFails(arn, PersonalIncomeRecord.id, nino.value)

        val result = doAgentPostRequest(
          requestPath,
          Json.toJson(RemoveAuthorisationRequest(nino.value, PersonalIncomeRecord.id)).toString()
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

      "return 204 when PartialAuth exists in PartialAuth Repo" in new StubsForThisScenario {
        await(partialAuthRepository.create(Instant.now(), arn, MtdIt.id, nino))

        doAgentPostRequest(
          requestPath,
          Json.toJson(RemoveAuthorisationRequest(nino.value, MtdIt.id)).toString()
        ).status shouldBe 204

        val partialAuthInvitations: Option[PartialAuthRelationship] = partialAuthRepository
          .find(MtdIt.id, nino, arn)
          .futureValue

        partialAuthInvitations.isDefined shouldBe false
      }

      "return None when PartialAuth do not exists in PartialAuth Repo" in new StubsForThisScenario {
        val result = doAgentPostRequest(
          requestPath,
          Json.toJson(RemoveAuthorisationRequest(nino.value, MtdIt.id)).toString()
        )

        result.status >= 400 && result.status < 600

        val partialAuthInvitations: Option[PartialAuthRelationship] = partialAuthRepository
          .find(MtdIt.id, nino, arn)
          .futureValue

        partialAuthInvitations.isDefined shouldBe false
      }

    }

    "when request data are incorrect" should {
      "return BadRequest 400 status when clientId is not valid for service" in {
        val result = doAgentPostRequest(
          requestPath,
          Json.toJson(RemoveAuthorisationRequest("IncorrectNino", MtdIt.id)).toString()
        )
        result.status shouldBe 400
        result.body shouldBe ""

      }

      "return NotImplemented 501 status and JSON Error If service is not supported" in {
        val result =
          doAgentPostRequest(
            requestPath,
            Json.toJson(RemoveAuthorisationRequest(nino.value, "IncorrectService")).toString()
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

      "return Forbidden 403 status and JSON Error when MtdId business details record is empty " in {
        givenEmptyItsaBusinessDetailsExists(nino.value)
        val result =
          doAgentPostRequest(
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
