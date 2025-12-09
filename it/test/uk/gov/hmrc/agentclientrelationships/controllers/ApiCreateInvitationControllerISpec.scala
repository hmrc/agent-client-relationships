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

import play.api.http.Status.UNPROCESSABLE_ENTITY
import play.api.i18n.Lang
import play.api.i18n.Langs
import play.api.i18n.MessagesApi
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.Json.toJson
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AuditService
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.HipConnector
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiCreateInvitationRequest
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiFailureResponse.ErrorBody
import uk.gov.hmrc.agentclientrelationships.repository.AgentReferenceRepository
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.services.AgentAssuranceService
import uk.gov.hmrc.agentclientrelationships.services.CheckRelationshipsOrchestratorService
import uk.gov.hmrc.agentclientrelationships.services.ClientDetailsService
import uk.gov.hmrc.agentclientrelationships.services.ApiKnownFactsCheckService
import uk.gov.hmrc.agentclientrelationships.stubs._
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service._
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.model.identifiers.SuspensionDetails
import uk.gov.hmrc.auth.core.AuthConnector

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import scala.concurrent.ExecutionContext

class ApiCreateInvitationControllerISpec
extends BaseControllerISpec
with ClientDetailsStub
with HipStub
with TestData
with CitizenDetailsStub {

  override def additionalConfig: Map[String, Any] = Map(
    "hip.enabled" -> true
  )

  val auditService: AuditService = app.injector.instanceOf[AuditService]
  val authConnector: AuthConnector = app.injector.instanceOf[AuthConnector]
  val hipConnector: HipConnector = app.injector.instanceOf[HipConnector]
  val clientDetailsService: ClientDetailsService = app.injector.instanceOf[ClientDetailsService]
  val knowFactsCheckService: ApiKnownFactsCheckService = app.injector.instanceOf[ApiKnownFactsCheckService]
  val checkRelationshipsService: CheckRelationshipsOrchestratorService = app.injector.instanceOf[CheckRelationshipsOrchestratorService]
  val agentAssuranceService: AgentAssuranceService = app.injector.instanceOf[AgentAssuranceService]
  val invitationRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val partialAuthRepository: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]
  val agentReferenceRepo: AgentReferenceRepository = app.injector.instanceOf[AgentReferenceRepository]

  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  lazy val messagesApi: MessagesApi = app.injector.instanceOf[MessagesApi]
  implicit val langs: Langs = app.injector.instanceOf[Langs]
  implicit val lang: Lang = langs.availables.head

  val controller =
    new ApiCreateInvitationController(
      hipConnector,
      clientDetailsService,
      knowFactsCheckService,
      checkRelationshipsService,
      agentAssuranceService,
      invitationRepo,
      partialAuthRepository,
      auditService,
      authConnector,
      appConfig,
      stubControllerComponents()
    )

  val testDate: LocalDate = LocalDate.now()
  val testTime: Instant =
    testDate
      .atStartOfDay(ZoneId.systemDefault())
      .toInstant

  val uid = "TestUID"
  val agencyName = "test agency Name"
  val normalizedAgencyName = "test-agency-name"

  val itsaInvitation: Invitation = Invitation
    .createNew(
      arn = arn.value,
      service = MtdIt,
      clientId = mtdItId,
      suppliedClientId = nino,
      clientName = "TestClientName",
      agencyName = agencyName,
      agencyEmail = "agent@email.com",
      expiryDate = testDate,
      clientType = Some("personal")
    )
    .copy(created = testTime, lastUpdated = testTime)

  val itsaSuppInvitation: Invitation = Invitation
    .createNew(
      arn = arn.value,
      service = MtdItSupp,
      clientId = mtdItId,
      suppliedClientId = nino,
      clientName = "TestClientName",
      agencyName = agencyName,
      agencyEmail = "agent@email.com",
      expiryDate = testDate,
      clientType = Some("personal")
    )
    .copy(created = testTime, lastUpdated = testTime)

  val baseInvitationInputData: ApiCreateInvitationRequest = ApiCreateInvitationRequest(
    service = MtdIt.id,
    suppliedClientId = nino.value,
    knownFact = "AA1 1AA",
    Some("personal")
  )

  private def getStandardStubForCreateInvitation(taxService: String) = {
    givenAuditConnector()
    givenAgentGroupExistsFor("foo")
    givenAdminUser("foo", "bar")
    givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
    givenAgentRecordFound(arn, testAgentRecord)
    givenUserAuthorised()

    if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) {
      givenDelegatedGroupIdsNotExistFor(EnrolmentKey(taxService, mtdItId))
      givenDelegatedGroupIdsNotExistFor(EnrolmentKey(multiAgentServicesOtherService(taxService), mtdItId))
      givenMtdItsaBusinessDetailsExists(
        nino = nino,
        mtdId = mtdItId,
        postCode = "AA1 1AA"
      )
      givenNinoItsaBusinessDetailsExists(
        mtdId = mtdItId,
        nino = nino,
        postCode = "AA1 1AA"
      )
    }

    if (taxService == HMRCMTDVAT) {
      givenDelegatedGroupIdsNotExistFor(EnrolmentKey(taxService, vrn))
      givenVatCustomerInfoExists(vrn = vrn.value, regDate = "2020-01-01")
    }

  }

  def allServices: Map[String, ApiCreateInvitationRequest] = Map(
    HMRCMTDIT -> baseInvitationInputData,
    HMRCMTDVAT -> baseInvitationInputData
      .copy(
        service = HMRCMTDVAT,
        suppliedClientId = vrn.value,
        knownFact = "2020-01-01",
        clientType = Some("business")
      ),
    HMRCMTDITSUPP -> baseInvitationInputData.copy(service = HMRCMTDITSUPP)
  )

  def allServicesClientIdFormatInvalidService: Map[String, ApiCreateInvitationRequest] = Map(
    HMRCMTDIT -> baseInvitationInputData.copy(suppliedClientId = vrn.value),
    HMRCMTDVAT -> baseInvitationInputData
      .copy(service = HMRCMTDVAT, knownFact = "2020-01-01"),
    HMRCMTDITSUPP -> baseInvitationInputData.copy(service = HMRCMTDITSUPP, suppliedClientId = vrn.value)
  )

  val testAgentRecord: TestAgentDetailsDesResponse = TestAgentDetailsDesResponse(
    uniqueTaxReference = None,
    agencyDetails = Some(
      TestAgencyDetails(
        agencyName = Some(agencyName),
        agencyEmail = Some("agent@email.com"),
        agencyTelephone = None,
        agencyAddress = None
      )
    ),
    suspensionDetails = None
  )

  private val multiAgentServicesOtherService: Map[String, String] = Map(HMRCMTDIT -> HMRCMTDITSUPP, HMRCMTDITSUPP -> HMRCMTDIT)

  "create invitation" should {

    // Expected tests
    allServices.keySet.foreach(taxService =>
      s"return 201 status and valid JSON when invitation is created for $taxService" in {
        givenClientHasNoRelationshipWithAnyAgentInCESA(nino = nino)
        val inputData: ApiCreateInvitationRequest = allServices(taxService)

        val clientId =
          if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
            mtdItId.value
          else
            inputData.suppliedClientId

        getStandardStubForCreateInvitation(taxService)

        val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
        val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
        result.status shouldBe 201

        val invitationSeq =
          invitationRepo
            .findAllForAgent(arn.value)
            .futureValue

        invitationSeq.size shouldBe 1

        val invitation = invitationSeq.head

        result.json shouldBe Json.obj(
          "invitationId" -> invitation.invitationId
        )

        invitation.status shouldBe Pending
        invitation.suppliedClientId shouldBe inputData.suppliedClientId
        invitation.clientId shouldBe clientId
        invitation.service shouldBe inputData.service

        verifyCreateInvitationAuditSent(requestPath, invitation)
      }
    )

    s"return UnprocessableEntity status and valid JSON CLIENT_REGISTRATION_NOT_FOUND when invitation is created for Alt Itsa - no client mtdItId" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      givenCitizenDetailsError(nino, 404)
      getStandardStubForCreateInvitation(HMRCMTDIT)
      givenMtdItIdIsUnKnownFor(nino)
      givenNinoIsUnknownFor(mtdItId)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "CLIENT_REGISTRATION_NOT_FOUND"
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe UNPROCESSABLE_ENTITY
      result.json shouldBe expectedJson
    }

    // PENDING INVITATION
    s"return UNPROCESSABLE_ENTITY status and valid JSON DUPLICATE_AUTHORISATION_REQUEST when ITSA invitation is already exists for ITSA " in {
      val taxService = HMRCMTDIT
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      invitationRepo.collection.insertOne(itsaInvitation).toFuture().futureValue
      getStandardStubForCreateInvitation(taxService)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "DUPLICATE_AUTHORISATION_REQUEST",
            invitationId = Some(itsaInvitation.invitationId)
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe UNPROCESSABLE_ENTITY
      result.json shouldBe expectedJson
    }

    // can not be MAIN and SUPP pending invitations at the same time for the same agent
    s"return UNPROCESSABLE_ENTITY status and valid JSON DUPLICATE_AUTHORISATION_REQUEST for ITSA MAIN request when ITSA SUPP Pending invitation already exists" in {
      val taxService = HMRCMTDIT
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      invitationRepo.collection.insertOne(itsaSuppInvitation).toFuture().futureValue
      getStandardStubForCreateInvitation(taxService)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "DUPLICATE_AUTHORISATION_REQUEST",
            invitationId = Some(itsaSuppInvitation.invitationId)
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe UNPROCESSABLE_ENTITY
      result.json shouldBe expectedJson
    }

    s"return UNPROCESSABLE_ENTITY status and valid JSON DUPLICATE_AUTHORISATION_REQUEST for ITSA SUPP request when ITSA MAIN Pending invitation already exists" in {
      val taxService = HMRCMTDITSUPP
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData.copy(service = MtdItSupp.id)

      invitationRepo.collection.insertOne(itsaInvitation).toFuture().futureValue
      getStandardStubForCreateInvitation(taxService)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "DUPLICATE_AUTHORISATION_REQUEST",
            invitationId = Some(itsaInvitation.invitationId)
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe UNPROCESSABLE_ENTITY
      result.json shouldBe expectedJson
    }

    s"return 201 status and valid JSON for ITSA request when Rejected request already exists in repo" in {
      givenClientHasNoRelationshipWithAnyAgentInCESA(nino = nino)
      val taxService = HMRCMTDIT
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      val clientId =
        if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
          mtdItId.value
        else
          inputData.suppliedClientId
      invitationRepo.collection.insertOne(itsaInvitation.copy(status = Rejected)).toFuture().futureValue
      getStandardStubForCreateInvitation(taxService)

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe 201

      val invitationSeq = invitationRepo
        .findAllForAgent(arn.value)
        .futureValue
        .filter(_.status == Pending)

      invitationSeq.size shouldBe 1

      val invitation = invitationSeq.head

      result.json shouldBe Json.obj(
        "invitationId" -> invitation.invitationId
      )

      invitation.status shouldBe Pending
      invitation.suppliedClientId shouldBe inputData.suppliedClientId
      invitation.clientId shouldBe clientId
      invitation.service shouldBe inputData.service

      verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    // RELATIONSHIP
    allServices.keySet.foreach(taxService =>
      s"return UNPROCESSABLE_ENTITY status and valid JSON ALREADY_AUTHORISED when relationship already exists  for $taxService" in {
        val inputData: ApiCreateInvitationRequest = allServices(taxService)

        val taxIdentifier =
          if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP)
            mtdItId
          else
            vrn

        getStandardStubForCreateInvitation(taxService)
        getActiveRelationshipsViaClient(taxIdentifier, arn)
        givenDelegatedGroupIdsExistFor(EnrolmentKey(taxService, taxIdentifier), Set("foo"))

        val expectedJson: JsValue = Json.toJson(
          toJson(
            ErrorBody(
              "ALREADY_AUTHORISED"
            )
          )
        )

        val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
        val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
        result.status shouldBe UNPROCESSABLE_ENTITY
        result.json shouldBe expectedJson
      }
    )

    s"return 201 status and valid JSON when request ITSA MAIN but ITSA SUPP relationship already exists" in {

      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      val taxIdentifier = mtdItId
      val clientId = mtdItId.value

      givenClientHasNoRelationshipWithAnyAgentInCESA(nino = nino)
      getStandardStubForCreateInvitation(HMRCMTDIT)
      getActiveRelationshipsViaClient(taxIdentifier, arn)
      givenDelegatedGroupIdsExistFor(EnrolmentKey(HMRCMTDITSUPP, taxIdentifier), Set("foo"))

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe 201

      val invitationSeq =
        invitationRepo
          .findAllForAgent(arn.value)
          .futureValue

      invitationSeq.size shouldBe 1

      val invitation = invitationSeq.head

      result.json shouldBe Json.obj(
        "invitationId" -> invitation.invitationId
      )

      invitation.status shouldBe Pending
      invitation.suppliedClientId shouldBe inputData.suppliedClientId
      invitation.clientId shouldBe clientId
      invitation.service shouldBe inputData.service

      verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    s"return 201 status and valid when request ITSA SUPP but ITSA MAIN relationship already exists" in {

      val inputData: ApiCreateInvitationRequest = baseInvitationInputData.copy(service = HMRCMTDITSUPP)

      val taxIdentifier = mtdItId
      val clientId = mtdItId.value

      getStandardStubForCreateInvitation(HMRCMTDITSUPP)
      getActiveRelationshipsViaClient(taxIdentifier, arn)
      givenDelegatedGroupIdsExistFor(EnrolmentKey(HMRCMTDIT, taxIdentifier), Set("foo"))

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe 201

      val invitationSeq =
        invitationRepo
          .findAllForAgent(arn.value)
          .futureValue

      invitationSeq.size shouldBe 1

      val invitation = invitationSeq.head

      result.json shouldBe Json.obj(
        "invitationId" -> invitation.invitationId
      )

      invitation.status shouldBe Pending
      invitation.suppliedClientId shouldBe inputData.suppliedClientId
      invitation.clientId shouldBe clientId
      invitation.service shouldBe inputData.service

      verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    s"return UNPROCESSABLE_ENTITY status and valid JSON CLIENT_REGISTRATION_NOT_FOUND when invitation is created for Alt Itsa - no client mtdItId and PartialAuth relationship exists" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      givenCitizenDetailsError(nino, 404)
      getStandardStubForCreateInvitation(HMRCMTDIT)
      givenMtdItIdIsUnKnownFor(nino)
      givenNinoIsUnknownFor(mtdItId)

      partialAuthRepository
        .create(
          Instant.parse("2020-01-01T00:00:00.000Z"),
          arn,
          HMRCMTDIT,
          nino
        ).futureValue

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "CLIENT_REGISTRATION_NOT_FOUND"
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe UNPROCESSABLE_ENTITY
      result.json shouldBe expectedJson
    }

    s"return UNPROCESSABLE_ENTITY status and valid JSON ALREADY_AUTHORISED when invitation is created for Alt Itsa - client mtdItId exists and PartialAuth relationship exists (triggers a proper relationship creation in the background)" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      getStandardStubForCreateInvitation(HMRCMTDIT)
      givenAgentCanBeAllocated(mtdItId, arn)
      givenEnrolmentAllocationSucceeds(
        "foo",
        "bar",
        EnrolmentKey(Service.MtdIt, mtdItId),
        "NQJUEJCWT14"
      )
      givenCacheRefresh(arn)

      partialAuthRepository
        .create(
          Instant.parse("2020-01-01T00:00:00.000Z"),
          arn,
          HMRCMTDIT,
          nino
        ).futureValue

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "ALREADY_AUTHORISED"
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe UNPROCESSABLE_ENTITY
      result.json shouldBe expectedJson
    }

    s"return 201 status and valid JSON when invitation is created for Alt Itsa - client mtdItId exists and PartialAuth for Alt Itsa Supp relationship exists" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      getStandardStubForCreateInvitation(HMRCMTDIT)
      val clientId = mtdItId.value

      partialAuthRepository
        .create(
          Instant.parse("2020-01-01T00:00:00.000Z"),
          arn,
          HMRCMTDITSUPP,
          nino
        ).futureValue

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe 201

      val invitationSeq =
        invitationRepo
          .findAllForAgent(arn.value)
          .futureValue

      invitationSeq.size shouldBe 1

      val invitation = invitationSeq.head

      result.json shouldBe Json.obj(
        "invitationId" -> invitation.invitationId
      )

      invitation.status shouldBe Pending
      invitation.suppliedClientId shouldBe inputData.suppliedClientId
      invitation.clientId shouldBe clientId
      invitation.service shouldBe inputData.service

      verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    s"return 201 status and valid JSON when invitation is created for Alt Itsa Supp - client mtdItId exists and PartialAuth for Alt Itsa Main relationship exists" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData.copy(service = HMRCMTDITSUPP)

      getStandardStubForCreateInvitation(HMRCMTDITSUPP)
      val clientId = mtdItId.value

      partialAuthRepository
        .create(
          Instant.parse("2020-01-01T00:00:00.000Z"),
          arn,
          HMRCMTDIT,
          nino
        ).futureValue

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe 201

      val invitationSeq =
        invitationRepo
          .findAllForAgent(arn.value)
          .futureValue

      invitationSeq.size shouldBe 1

      val invitation = invitationSeq.head

      result.json shouldBe Json.obj(
        "invitationId" -> invitation.invitationId
      )

      invitation.status shouldBe Pending
      invitation.suppliedClientId shouldBe inputData.suppliedClientId
      invitation.clientId shouldBe clientId
      invitation.service shouldBe inputData.service

      verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    // VALIDATION
    allServicesClientIdFormatInvalidService.keySet.foreach(taxService =>
      s"return UNPROCESSABLE_ENTITY status and valid JSON CLIENT_ID_FORMAT_INVALID for $taxService" in {
        val inputData: ApiCreateInvitationRequest = allServicesClientIdFormatInvalidService(taxService)

        givenAuditConnector()
        givenUserAuthorised()
        val expectedJson: JsValue = Json.toJson(
          toJson(
            ErrorBody(
              "CLIENT_ID_FORMAT_INVALID"
            )
          )
        )

        val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
        val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
        result.status shouldBe UNPROCESSABLE_ENTITY
        result.json shouldBe expectedJson
      }
    )

    s"return UNPROCESSABLE_ENTITY status and valid JSON CLIENT_ID_DOES_NOT_MATCH_SERVICE for ${Trust.id}" in {
      val inputData: ApiCreateInvitationRequest = ApiCreateInvitationRequest(
        service = Trust.id,
        suppliedClientId = utr.value,
        knownFact = "AA1 1AA",
        None
      )

      givenAuditConnector()
      givenUserAuthorised()
      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "SERVICE_NOT_SUPPORTED"
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe UNPROCESSABLE_ENTITY
      result.json shouldBe expectedJson
    }

    allServicesClientIdFormatInvalidService.keySet.foreach(taxService =>
      s"return UNPROCESSABLE_ENTITY status and valid JSON CLIENT_TYPE_NOT_SUPPORTED for $taxService when clientType is not supported" in {
        val inputData: ApiCreateInvitationRequest = allServices(taxService).copy(clientType = Some("UNSUPPORTED"))

        givenAuditConnector()
        givenUserAuthorised()
        givenMtdItIdIsUnKnownFor(nino)
        val expectedJson: JsValue = Json.toJson(
          toJson(
            ErrorBody(
              "CLIENT_TYPE_NOT_SUPPORTED"
            )
          )
        )

        val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
        val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
        result.status shouldBe UNPROCESSABLE_ENTITY
        result.json shouldBe expectedJson
      }
    )

    // AGENT
    allServices.keySet.foreach(taxService =>
      s"return UNPROCESSABLE_ENTITY status and valid JSON AGENT_TYPE_NOT_SUPPORTED when agent is suspended for $taxService" in {
        val inputData: ApiCreateInvitationRequest = allServices(taxService)

        getStandardStubForCreateInvitation(taxService)
        givenAgentRecordFound(
          arn,
          testAgentRecord.copy(suspensionDetails = Some(SuspensionDetails(suspensionStatus = true, regimes = None)))
        )
        givenUserAuthorised()

        val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
        val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())

        val expectedJson: JsValue = Json.toJson(
          toJson(
            ErrorBody(
              "AGENT_SUSPENDED"
            )
          )
        )

        result.status shouldBe UNPROCESSABLE_ENTITY

        result.json shouldBe expectedJson
      }
    )

    allServices.keySet.foreach(taxService =>
      s"return InternalServerError status and error message when agent DES return 404 for $taxService" in {
        val inputData: ApiCreateInvitationRequest = allServices(taxService)

        getStandardStubForCreateInvitation(taxService)
        givenAgentDetailsErrorResponse(arn, 404)

        val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
        val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())

        result.status shouldBe INTERNAL_SERVER_ERROR

      }
    )

    // Client validation
    s"return UNPROCESSABLE_ENTITY status and valid JSON VAT_CLIENT_INSOLVENT when VAT client is insolvent" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData
        .copy(
          service = HMRCMTDVAT,
          suppliedClientId = vrn.value,
          knownFact = "2020-01-01"
        )

      getStandardStubForCreateInvitation(HMRCMTDVAT)
      givenVatCustomerInfoExists(
        vrn = vrn.value,
        regDate = "2020-01-01",
        isInsolvent = true
      )

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "VAT_CLIENT_INSOLVENT"
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe UNPROCESSABLE_ENTITY
      result.json shouldBe expectedJson

    }

    // KnowFacts checks
    s"return UNPROCESSABLE_ENTITY status and valid JSON VAT_REG_DATE_FORMAT_INVALID when VAT knowFact date format is invalid" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData
        .copy(
          service = HMRCMTDVAT,
          suppliedClientId = vrn.value,
          knownFact = "2020/01/01"
        )

      getStandardStubForCreateInvitation(HMRCMTDVAT)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "VAT_REG_DATE_FORMAT_INVALID"
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe UNPROCESSABLE_ENTITY
      result.json shouldBe expectedJson

    }

    s"return UNPROCESSABLE_ENTITY status and valid JSON VAT_REG_DATE_DOES_NOT_MATCH when VAT knowFact date not match" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData
        .copy(
          service = HMRCMTDVAT,
          suppliedClientId = vrn.value,
          knownFact = "2020-01-02"
        )

      getStandardStubForCreateInvitation(HMRCMTDVAT)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "VAT_REG_DATE_DOES_NOT_MATCH"
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe UNPROCESSABLE_ENTITY
      result.json shouldBe expectedJson
    }

    s"return UNPROCESSABLE_ENTITY status and valid JSON POSTCODE_FORMAT_INVALID when ITSA knowFact postcode is wrong format" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData
        .copy(knownFact = "IAMWRONG12")

      getStandardStubForCreateInvitation(HMRCMTDIT)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "POSTCODE_FORMAT_INVALID"
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe UNPROCESSABLE_ENTITY
      result.json shouldBe expectedJson

    }

    s"return UNPROCESSABLE_ENTITY status and valid JSON POSTCODE_DOES_NOT_MATCH when ITSA knowFact postcode do not MATCH" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData
        .copy(knownFact = "DM11 8DX")

      getStandardStubForCreateInvitation(HMRCMTDIT)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "POSTCODE_DOES_NOT_MATCH"
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe UNPROCESSABLE_ENTITY
      result.json shouldBe expectedJson

    }

    s"return UNPROCESSABLE_ENTITY status and valid JSON INVALID_PAYLOAD when ITSA country is not valid" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      getStandardStubForCreateInvitation(HMRCMTDIT)
      givenMtdItsaBusinessDetailsExists(
        nino = nino,
        mtdId = mtdItId,
        postCode = "AA1 1AA",
        countryCode = "XX"
      )
      givenNinoItsaBusinessDetailsExists(
        mtdId = mtdItId,
        nino = nino,
        postCode = "AA1 1AA",
        countryCode = "XX"
      )

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "INVALID_PAYLOAD"
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe UNPROCESSABLE_ENTITY
      result.json shouldBe expectedJson

    }

  }

}
