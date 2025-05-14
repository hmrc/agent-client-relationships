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

import play.api.i18n.{Lang, Langs, MessagesApi}
import play.api.libs.json.Json.toJson
import play.api.libs.json.{JsValue, Json}
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AuditService
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiErrorResults.ErrorBody
import uk.gov.hmrc.agentclientrelationships.model.invitation.{ApiAuthorisation, ApiCreateInvitationRequest}
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentReferenceRecord
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentKey, _}
import uk.gov.hmrc.agentclientrelationships.repository.{AgentReferenceRepository, InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentclientrelationships.services.ApiService
import uk.gov.hmrc.agentclientrelationships.stubs._
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentmtdidentifiers.model.Service._
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.auth.core.AuthConnector

import java.time.{Instant, LocalDate, ZoneId}
import scala.concurrent.ExecutionContext

class ApiControllerISpec extends BaseControllerISpec with ClientDetailsStub with HipStub with TestData {

  override def additionalConfig: Map[String, Any] = Map(
    "hip.enabled"                 -> true,
    "hip.BusinessDetails.enabled" -> true
  )

  val apiService: ApiService = app.injector.instanceOf[ApiService]
  val auditService: AuditService = app.injector.instanceOf[AuditService]
  val authConnector: AuthConnector = app.injector.instanceOf[AuthConnector]

  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  lazy val messagesApi: MessagesApi = app.injector.instanceOf[MessagesApi]
  implicit val langs: Langs = app.injector.instanceOf[Langs]
  implicit val lang: Lang = langs.availables.head

  val controller =
    new ApiController(
      apiService,
      auditService,
      authConnector,
      appConfig,
      stubControllerComponents()
    )

  val invitationRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val partialAuthRepository: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]
  val agentReferenceRepo: AgentReferenceRepository = app.injector.instanceOf[AgentReferenceRepository]

  val testDate: LocalDate = LocalDate.now()
  val testTime: Instant =
    testDate
      .atStartOfDay(ZoneId.systemDefault())
      .toInstant

  val uid = "TestUID"
  val agencyName = "test agency Name"
  val normalizedAgencyName = "test-agency-name"

  val itsaInvitation: Invitation =
    Invitation
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

  val altItsaInvitation: Invitation =
    Invitation
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

  val itsaSuppInvitation: Invitation =
    Invitation
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

  val vatInvitation: Invitation =
    Invitation
      .createNew(
        arn = arn.value,
        service = Vat,
        clientId = vrn,
        suppliedClientId = vrn,
        clientName = "TestClientName",
        agencyName = agencyName,
        agencyEmail = "agent@email.com",
        expiryDate = testDate,
        clientType = Some("personal")
      )
      .copy(created = testTime, lastUpdated = testTime)

  val trustInvitation: Invitation =
    Invitation
      .createNew(
        arn = arn.value,
        service = Trust,
        clientId = utr,
        suppliedClientId = utr,
        clientName = "TestClientName",
        agencyName = agencyName,
        agencyEmail = "agent@email.com",
        expiryDate = testDate,
        clientType = Some("personal")
      )
      .copy(created = testTime, lastUpdated = testTime)

  val agentReferenceRecord: AgentReferenceRecord = AgentReferenceRecord(
    uid = uid,
    arn = arn,
    normalisedAgentNames = Seq(normalizedAgencyName, "NormalisedAgentName2")
  )

  val baseInvitationInputData: ApiCreateInvitationRequest =
    ApiCreateInvitationRequest(
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

    if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) {
      givenDelegatedGroupIdsNotExistFor(EnrolmentKey(taxService, mtdItId))
      givenDelegatedGroupIdsNotExistFor(EnrolmentKey(multiAgentServicesOtherService(taxService), mtdItId))
      givenMtdItsaBusinessDetailsExists(nino = nino, mtdId = mtdItId, postCode = "AA1 1AA")
      givenNinoItsaBusinessDetailsExists(mtdId = mtdItId, nino = nino, postCode = "AA1 1AA")
    }

    if (taxService == HMRCMTDVAT) {
      givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMCE-VATDEC-ORG~VATRegNo~101747641"))
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

  def allServicesGetInvitation: Map[String, Invitation] = Map(
    HMRCMTDIT     -> itsaInvitation,
    HMRCMTDVAT    -> vatInvitation,
    HMRCMTDITSUPP -> itsaSuppInvitation
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

  private val multiAgentServicesOtherService: Map[String, String] =
    Map(HMRCMTDIT -> HMRCMTDITSUPP, HMRCMTDITSUPP -> HMRCMTDIT)

  "create invitation" should {

    // Expected tests
    allServices.keySet.foreach(taxService =>
      s"return 201 status and valid JSON when invitation is created for $taxService" in {
        val inputData: ApiCreateInvitationRequest = allServices(taxService)

        val clientId =
          if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) mtdItId.value
          else inputData.suppliedClientId

        getStandardStubForCreateInvitation(taxService)

        val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
        val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
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
        invitation.suppliedClientId shouldBe inputData.suppliedClientId
        invitation.clientId shouldBe clientId
        invitation.service shouldBe inputData.service

        verifyCreateInvitationAuditSent(requestPath, invitation)
      }
    )

    s"return Forbidden status and valid JSON CLIENT_REGISTRATION_NOT_FOUND when invitation is created for Alt Itsa - no client mtdItId" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      getStandardStubForCreateInvitation(HMRCMTDIT)
      givenMtdItIdIsUnKnownFor(nino)
      givenNinoIsUnknownFor(mtdItId)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "CLIENT_REGISTRATION_NOT_FOUND",
            "The details provided for this client do not match HMRC's records."
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe FORBIDDEN
      result.json shouldBe expectedJson
    }

    // PENDING INVITATION
    s"return Forbidden status and valid JSON DUPLICATE_AUTHORISATION_REQUEST when ITSA invitation is already exists for ITSA " in {
      val taxService = HMRCMTDIT
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      await(invitationRepo.collection.insertOne(itsaInvitation).toFuture())
      getStandardStubForCreateInvitation(taxService)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "DUPLICATE_AUTHORISATION_REQUEST",
            "An authorisation request for this service has already been created and is awaiting the client’s response.",
            invitationId = Some(itsaInvitation.invitationId)
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe FORBIDDEN
      result.json shouldBe expectedJson
    }

    // can not be MAIN and SUPP pending invitations at the same time for the same agent
    s"return Forbidden status and valid JSON DUPLICATE_AUTHORISATION_REQUEST for ITSA MAIN request when ITSA SUPP Pending invitation already exists" in {
      val taxService = HMRCMTDIT
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      val clientId =
        if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) mtdItId.value
        else inputData.suppliedClientId

      await(invitationRepo.collection.insertOne(itsaSuppInvitation).toFuture())
      getStandardStubForCreateInvitation(taxService)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "DUPLICATE_AUTHORISATION_REQUEST",
            "An authorisation request for this service has already been created and is awaiting the client’s response.",
            invitationId = Some(itsaSuppInvitation.invitationId)
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe FORBIDDEN
      result.json shouldBe expectedJson
    }

    s"return Forbidden status and valid JSON DUPLICATE_AUTHORISATION_REQUEST for ITSA SUPP request when ITSA MAIN Pending invitation already exists" in {
      val taxService = HMRCMTDITSUPP
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData.copy(service = MtdItSupp.id)

      val clientId =
        if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) mtdItId.value
        else inputData.suppliedClientId

      await(invitationRepo.collection.insertOne(itsaInvitation).toFuture())
      getStandardStubForCreateInvitation(taxService)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "DUPLICATE_AUTHORISATION_REQUEST",
            "An authorisation request for this service has already been created and is awaiting the client’s response.",
            invitationId = Some(itsaInvitation.invitationId)
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe FORBIDDEN
      result.json shouldBe expectedJson
    }

    s"return 201 status and valid JSON for ITSA request when Rejected request already exists in repo" in {
      val taxService = HMRCMTDIT
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      val clientId =
        if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) mtdItId.value
        else inputData.suppliedClientId

      await(invitationRepo.collection.insertOne(itsaInvitation.copy(status = Rejected)).toFuture())
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
      s"return FORBIDDEN status and valid JSON ALREADY_AUTHORISED when relationship already exists  for $taxService" in {
        val inputData: ApiCreateInvitationRequest = allServices(taxService)

        val taxIdentifier =
          if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) mtdItId
          else vrn

        getStandardStubForCreateInvitation(taxService)
        getActiveRelationshipsViaClient(taxIdentifier, arn)
        givenDelegatedGroupIdsExistFor(EnrolmentKey(taxService, taxIdentifier), Set("foo"))

        val expectedJson: JsValue = Json.toJson(
          toJson(
            ErrorBody(
              "ALREADY_AUTHORISED",
              "The client has already authorised the agent for this service. The agent does not need ask the client for this authorisation again."
            )
          )
        )

        val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
        val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
        result.status shouldBe FORBIDDEN
        result.json shouldBe expectedJson
      }
    )

    s"return 201 status and valid JSON when request ITSA MAIN but ITSA SUPP relationship already exists" in {

      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      val taxIdentifier = mtdItId
      val clientId = mtdItId.value

      getStandardStubForCreateInvitation(HMRCMTDIT)
      getActiveRelationshipsViaClient(taxIdentifier, arn)
      givenDelegatedGroupIdsExistFor(EnrolmentKey(HMRCMTDITSUPP, taxIdentifier), Set("foo"))

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
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

      val invitationSeq = invitationRepo
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

    s"return Forbidden status and valid JSON CLIENT_REGISTRATION_NOT_FOUND when invitation is created for Alt Itsa - no client mtdItId and PartialAuth relationship exists" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      getStandardStubForCreateInvitation(HMRCMTDIT)
      givenMtdItIdIsUnKnownFor(nino)
      givenNinoIsUnknownFor(mtdItId)

      await(
        partialAuthRepository
          .create(
            Instant.parse("2020-01-01T00:00:00.000Z"),
            arn,
            HMRCMTDIT,
            nino
          )
      )

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "CLIENT_REGISTRATION_NOT_FOUND",
            "The details provided for this client do not match HMRC's records."
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe FORBIDDEN
      result.json shouldBe expectedJson
    }

    s"return Forbidden status and valid JSON ALREADY_AUTHORISED when invitation is created for Alt Itsa - client mtdItId exists and PartialAuth relationship exists" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      getStandardStubForCreateInvitation(HMRCMTDIT)

      await(
        partialAuthRepository
          .create(
            Instant.parse("2020-01-01T00:00:00.000Z"),
            arn,
            HMRCMTDIT,
            nino
          )
      )

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "ALREADY_AUTHORISED",
            "The client has already authorised the agent for this service. The agent does not need ask the client for this authorisation again."
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe FORBIDDEN
      result.json shouldBe expectedJson
    }

    s"return 201 status and valid JSON when invitation is created for Alt Itsa - client mtdItId exists and PartialAuth for Alt Itsa Supp relationship exists" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      getStandardStubForCreateInvitation(HMRCMTDIT)
      val clientId = mtdItId.value

      await(
        partialAuthRepository
          .create(
            Instant.parse("2020-01-01T00:00:00.000Z"),
            arn,
            HMRCMTDITSUPP,
            nino
          )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
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
      invitation.suppliedClientId shouldBe inputData.suppliedClientId
      invitation.clientId shouldBe clientId
      invitation.service shouldBe inputData.service

      verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    s"return 201 status and valid JSON when invitation is created for Alt Itsa Supp - client mtdItId exists and PartialAuth for Alt Itsa Main relationship exists" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData.copy(service = HMRCMTDITSUPP)

      getStandardStubForCreateInvitation(HMRCMTDITSUPP)
      val clientId = mtdItId.value

      await(
        partialAuthRepository
          .create(
            Instant.parse("2020-01-01T00:00:00.000Z"),
            arn,
            HMRCMTDIT,
            nino
          )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
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
      invitation.suppliedClientId shouldBe inputData.suppliedClientId
      invitation.clientId shouldBe clientId
      invitation.service shouldBe inputData.service

      verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    // VALIDATION
    allServicesClientIdFormatInvalidService.keySet.foreach(taxService =>
      s"return BadRequest status and valid JSON CLIENT_ID_FORMAT_INVALID for $taxService" in {
        val inputData: ApiCreateInvitationRequest = allServicesClientIdFormatInvalidService(taxService)

        givenAuditConnector()
        val expectedJson: JsValue = Json.toJson(
          toJson(
            ErrorBody(
              "CLIENT_ID_FORMAT_INVALID",
              "Client identifier must be in the correct format. Check the API documentation to find the correct format."
            )
          )
        )

        val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
        val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
        result.status shouldBe BAD_REQUEST
        result.json shouldBe expectedJson
      }
    )

    s"return BadRequest status and valid JSON CLIENT_ID_DOES_NOT_MATCH_SERVICE for ${Trust.id}" in {
      val inputData: ApiCreateInvitationRequest =
        ApiCreateInvitationRequest(service = Trust.id, suppliedClientId = utr.value, knownFact = "AA1 1AA", None)

      givenAuditConnector()
      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "SERVICE_NOT_SUPPORTED",
            "The service requested is not supported. Check the API documentation to find which services are supported."
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe BAD_REQUEST
      result.json shouldBe expectedJson
    }

    allServicesClientIdFormatInvalidService.keySet.foreach(taxService =>
      s"return BadRequest status and valid JSON CLIENT_TYPE_NOT_SUPPORTED for $taxService when clientType is not supported" in {
        val inputData: ApiCreateInvitationRequest =
          allServices(taxService).copy(clientType = Some("UNSUPPORTED"))

        givenAuditConnector()
        val expectedJson: JsValue = Json.toJson(
          toJson(
            ErrorBody(
              "CLIENT_TYPE_NOT_SUPPORTED",
              "The client type requested is not supported. Check the API documentation to find which client types are supported."
            )
          )
        )

        val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
        val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
        result.status shouldBe BAD_REQUEST
        result.json shouldBe expectedJson
      }
    )

    // AGENT
    allServices.keySet.foreach(taxService =>
      s"return Forbidden status and valid JSON AGENT_TYPE_NOT_SUPPORTED when agent is suspended for $taxService" in {
        val inputData: ApiCreateInvitationRequest = allServices(taxService)

        getStandardStubForCreateInvitation(taxService)
        givenAgentRecordFound(
          arn,
          testAgentRecord.copy(suspensionDetails = Some(SuspensionDetails(suspensionStatus = true, regimes = None)))
        )

        val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
        val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())

        val expectedJson: JsValue = Json.toJson(
          toJson(
            ErrorBody(
              "AGENT_SUSPENDED",
              "This agent is suspended"
            )
          )
        )

        result.status shouldBe FORBIDDEN

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
    s"return Forbidden status and valid JSON VAT_CLIENT_INSOLVENT when VAT client is insolvent" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData
        .copy(service = HMRCMTDVAT, suppliedClientId = vrn.value, knownFact = "2020-01-01")

      getStandardStubForCreateInvitation(HMRCMTDVAT)
      givenVatCustomerInfoExists(vrn = vrn.value, regDate = "2020-01-01", isInsolvent = true)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "VAT_CLIENT_INSOLVENT",
            "The Vat registration number belongs to a customer that is insolvent."
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe FORBIDDEN
      result.json shouldBe expectedJson

    }

    // KnowFacts checks
    s"return Forbidden status and valid JSON VAT_REG_DATE_FORMAT_INVALID when VAT knowFact date format is invalid" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData
        .copy(service = HMRCMTDVAT, suppliedClientId = vrn.value, knownFact = "2020/01/01")

      getStandardStubForCreateInvitation(HMRCMTDVAT)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "VAT_REG_DATE_FORMAT_INVALID",
            "VAT registration date must be in the correct format. Check the API documentation to find the correct format."
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe BAD_REQUEST
      result.json shouldBe expectedJson

    }

    s"return Forbidden status and valid JSON VAT_REG_DATE_DOES_NOT_MATCH when VAT knowFact date not match" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData
        .copy(service = HMRCMTDVAT, suppliedClientId = vrn.value, knownFact = "2020-01-02")

      getStandardStubForCreateInvitation(HMRCMTDVAT)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "VAT_REG_DATE_DOES_NOT_MATCH",
            "The VAT registration date provided does not match HMRC's record for this client."
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe FORBIDDEN
      result.json shouldBe expectedJson
    }

    s"return Forbidden status and valid JSON POSTCODE_FORMAT_INVALID when ITSA knowFact postcode is wrong format" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData
        .copy(knownFact = "IAMWRONG12")

      getStandardStubForCreateInvitation(HMRCMTDIT)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "POSTCODE_FORMAT_INVALID",
            "Postcode must be in the correct format. Check the API documentation to find the correct format."
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe BAD_REQUEST
      result.json shouldBe expectedJson

    }

    s"return Forbidden status and valid JSON POSTCODE_DOES_NOT_MATCH when ITSA knowFact postcode do not MATCH" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData
        .copy(knownFact = "DM11 8DX")

      getStandardStubForCreateInvitation(HMRCMTDIT)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "POSTCODE_DOES_NOT_MATCH",
            "The postcode provided does not match HMRC's record for this client."
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe FORBIDDEN
      result.json shouldBe expectedJson

    }

    s"return Forbidden status and valid JSON POSTCODE_DOES_NOT_MATCH when ITSA client is oversea" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      getStandardStubForCreateInvitation(HMRCMTDIT)
      givenMtdItsaBusinessDetailsExists(nino = nino, mtdId = mtdItId, postCode = "AA1 1AA", countryCode = "XX")
      givenNinoItsaBusinessDetailsExists(mtdId = mtdItId, nino = nino, postCode = "AA1 1AA", countryCode = "XX")

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "POSTCODE_DOES_NOT_MATCH",
            "The postcode provided does not match HMRC's record for this client."
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
      val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())
      result.status shouldBe FORBIDDEN
      result.json shouldBe expectedJson

    }

  }

  "get invitation" should {

    // Expected tests
    allServicesGetInvitation.keySet.foreach(taxService =>
      s"return 200 status and valid JSON when invitation exists for $taxService" in {
        val invitation: Invitation = allServicesGetInvitation(taxService)

        await(invitationRepo.collection.insertOne(invitation).toFuture())
        await(agentReferenceRepo.create(agentReferenceRecord))
        givenAgentRecordFound(arn, testAgentRecord)

        val requestPath = s"/agent-client-relationships/api/${invitation.arn}/invitation/${invitation.invitationId}"
        val result = doGetRequest(requestPath)
        result.status shouldBe 200

        result.json shouldBe Json.obj(
          "uid"                 -> agentReferenceRecord.uid,
          "normalizedAgentName" -> normalizedAgencyName,
          "created"             -> testTime.toString,
          "service"             -> invitation.service,
          "status"              -> invitation.status,
          "expiresOn"           -> testDate.toString,
          "invitationId"        -> invitation.invitationId,
          "lastUpdated"         -> testTime.toString
        )

      }
    )

    allServicesGetInvitation.keySet.foreach(taxService =>
      s"return 200 status and valid JSON when invitation exists in any state for $taxService" in {
        val invitation: Invitation = allServicesGetInvitation(taxService).copy(status = Cancelled)

        await(invitationRepo.collection.insertOne(invitation).toFuture())
        await(agentReferenceRepo.create(agentReferenceRecord))
        givenAgentRecordFound(arn, testAgentRecord)

        val requestPath = s"/agent-client-relationships/api/${invitation.arn}/invitation/${invitation.invitationId}"
        val result = doGetRequest(requestPath)
        result.status shouldBe 200

        result.json shouldBe Json.obj(
          "uid"                 -> agentReferenceRecord.uid,
          "normalizedAgentName" -> normalizedAgencyName,
          "created"             -> testTime.toString,
          "service"             -> invitation.service,
          "status"              -> invitation.status,
          "expiresOn"           -> testDate.toString,
          "invitationId"        -> invitation.invitationId,
          "lastUpdated"         -> testTime.toString
        )

      }
    )

    allServicesGetInvitation.keySet.foreach(taxService =>
      s"return 200 status and valid JSON when invitation exists and create new UID if does not exists for $taxService" in {
        val invitation: Invitation = allServicesGetInvitation(taxService)

        await(invitationRepo.collection.insertOne(invitation).toFuture())
        givenAgentRecordFound(arn, testAgentRecord)

        val requestPath = s"/agent-client-relationships/api/${invitation.arn}/invitation/${invitation.invitationId}"
        val result = doGetRequest(requestPath)
        result.status shouldBe 200

        val resultApiAuthorisationRequestInfo = result.json.as[ApiAuthorisation]
        resultApiAuthorisationRequestInfo.normalizedAgentName shouldBe normalizedAgencyName
        resultApiAuthorisationRequestInfo.status shouldBe Pending
        resultApiAuthorisationRequestInfo.service shouldBe invitation.service
        resultApiAuthorisationRequestInfo.invitationId shouldBe invitation.invitationId

      }
    )

    allServicesGetInvitation.keySet.foreach(taxService =>
      s"return 404 NotFound status and valid JSON INVITATION_NOT_FOUND when invitationId does not for $taxService" in {
        val invitation: Invitation = allServicesGetInvitation(taxService)

        await(agentReferenceRepo.create(agentReferenceRecord))
        givenAgentRecordFound(arn, testAgentRecord)

        val expectedJson: JsValue = Json.toJson(
          toJson(
            ErrorBody(
              "INVITATION_NOT_FOUND",
              "The authorisation request cannot be found."
            )
          )
        )

        val requestPath = s"/agent-client-relationships/api/${invitation.arn}/invitation/${invitation.invitationId}"
        val result = doGetRequest(requestPath)
        result.status shouldBe NOT_FOUND
        result.json shouldBe expectedJson

      }
    )

    s"return 400 BAD_REQUEST status and valid JSON SERVICE_NOT_SUPPORTED when invitationId does not for Trust" in {
      val invitation: Invitation = vatInvitation.copy(service = Service.Trust.id)

      await(invitationRepo.collection.insertOne(invitation).toFuture())
      await(agentReferenceRepo.create(agentReferenceRecord))
      givenAgentRecordFound(arn, testAgentRecord)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "SERVICE_NOT_SUPPORTED",
            "The service requested is not supported. Check the API documentation to find which services are supported."
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${invitation.arn}/invitation/${invitation.invitationId}"
      val result = doGetRequest(requestPath)
      result.status shouldBe BAD_REQUEST
      result.json shouldBe expectedJson

    }

    s"return 403 FORBIDDEN status and valid JSON NO_PERMISSION_ON_AGENCY when invitationId does not for Trust" in {
      val invitation: Invitation = vatInvitation.copy(arn = arn2.value)

      await(invitationRepo.collection.insertOne(invitation).toFuture())
      await(agentReferenceRepo.create(agentReferenceRecord))
      givenAgentRecordFound(arn, testAgentRecord)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "NO_PERMISSION_ON_AGENCY",
            "The user that is signed in cannot access this authorisation request. Their details do not match the agent business that created the authorisation request."
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation/${invitation.invitationId}"
      val result = doGetRequest(requestPath)
      result.status shouldBe FORBIDDEN
      result.json shouldBe expectedJson

    }

    // AGENT
    allServicesGetInvitation.keySet.foreach(taxService =>
      s"return 403 FORBIDDEN status and valid JSON AGENT_SUSPENDED when invitation exists but agent is suspended  $taxService" in {
        val invitation: Invitation = allServicesGetInvitation(taxService)

        await(agentReferenceRepo.create(agentReferenceRecord))
        await(invitationRepo.collection.insertOne(invitation).toFuture())
        givenAgentRecordFound(
          arn,
          testAgentRecord.copy(suspensionDetails = Some(SuspensionDetails(suspensionStatus = true, regimes = None)))
        )

        val expectedJson: JsValue = Json.toJson(
          toJson(
            ErrorBody(
              "AGENT_SUSPENDED",
              "This agent is suspended"
            )
          )
        )

        val requestPath = s"/agent-client-relationships/api/${invitation.arn}/invitation/${invitation.invitationId}"
        val result = doGetRequest(requestPath)
        result.status shouldBe FORBIDDEN
        result.json shouldBe expectedJson

      }
    )

    allServicesGetInvitation.keySet.foreach(taxService =>
      s"return 500 INTERNAL_SERVER_ERROR status and valid JSON  when invitation exists but agent data not found  $taxService" in {
        val invitation: Invitation = allServicesGetInvitation(taxService)

        await(agentReferenceRepo.create(agentReferenceRecord))
        await(invitationRepo.collection.insertOne(invitation).toFuture())
        givenAgentDetailsErrorResponse(arn, 404)

        val requestPath = s"/agent-client-relationships/api/${invitation.arn}/invitation/${invitation.invitationId}"
        val result = doGetRequest(requestPath)
        result.status shouldBe INTERNAL_SERVER_ERROR

      }
    )

  }

  "get invitations" should {

    // Expected tests
    s"return 200 status and valid JSON when invitations exists for arn " in {
      val invitations: Seq[Invitation] = Seq(itsaInvitation, itsaSuppInvitation, vatInvitation, trustInvitation)

      await(invitationRepo.collection.insertMany(invitations).toFuture())
      await(agentReferenceRepo.create(agentReferenceRecord))
      givenAgentRecordFound(arn, testAgentRecord)

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitations"
      val result = doGetRequest(requestPath)
      result.status shouldBe 200

      result.json shouldBe Json.obj(
        "uid"                 -> agentReferenceRecord.uid,
        "normalizedAgentName" -> normalizedAgencyName,
        "invitations" -> Json.arr(
          Json.obj(
            "created"      -> testTime.toString,
            "service"      -> itsaInvitation.service,
            "status"       -> itsaInvitation.status,
            "expiresOn"    -> testDate.toString,
            "invitationId" -> itsaInvitation.invitationId,
            "lastUpdated"  -> testTime.toString
          ),
          Json.obj(
            "created"      -> testTime.toString,
            "service"      -> itsaSuppInvitation.service,
            "status"       -> itsaSuppInvitation.status,
            "expiresOn"    -> testDate.toString,
            "invitationId" -> itsaSuppInvitation.invitationId,
            "lastUpdated"  -> testTime.toString
          ),
          Json.obj(
            "created"      -> testTime.toString,
            "service"      -> vatInvitation.service,
            "status"       -> vatInvitation.status,
            "expiresOn"    -> testDate.toString,
            "invitationId" -> vatInvitation.invitationId,
            "lastUpdated"  -> testTime.toString
          )
        )
      )

    }

    s"return 404 NotFound status and valid JSON INVITATION_NOT_FOUND when no invitations for arn or supported service" in {
      val invitations: Seq[Invitation] = Seq(
        itsaInvitation.copy(arn = arn2.value),
        itsaSuppInvitation.copy(arn = arn2.value),
        vatInvitation.copy(arn = arn2.value),
        trustInvitation.copy(arn = arn2.value)
      )

      await(invitationRepo.collection.insertMany(invitations).toFuture())
      await(agentReferenceRepo.create(agentReferenceRecord))
      givenAgentRecordFound(arn, testAgentRecord)

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "INVITATION_NOT_FOUND",
            "The authorisation request cannot be found."
          )
        )
      )
      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitations"
      val result = doGetRequest(requestPath)
      result.status shouldBe NOT_FOUND
      result.json shouldBe expectedJson

    }

    s"return 403 FORBIDDEN status and valid JSON AGENT_SUSPENDED when invitation exists but agent is suspended" in {
      val invitations: Seq[Invitation] = Seq(itsaInvitation, itsaSuppInvitation, vatInvitation, trustInvitation)

      await(invitationRepo.collection.insertMany(invitations).toFuture())
      await(agentReferenceRepo.create(agentReferenceRecord))
      givenAgentRecordFound(
        arn,
        testAgentRecord.copy(suspensionDetails = Some(SuspensionDetails(suspensionStatus = true, regimes = None)))
      )

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "AGENT_SUSPENDED",
            "This agent is suspended"
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitations"
      val result = doGetRequest(requestPath)
      result.status shouldBe FORBIDDEN
      result.json shouldBe expectedJson

    }

    s"return 500 INTERNAL_SERVER_ERROR status and valid JSON  when invitation exists but agent data not found " in {
      val invitations: Seq[Invitation] = Seq(itsaInvitation, itsaSuppInvitation, vatInvitation, trustInvitation)

      await(invitationRepo.collection.insertMany(invitations).toFuture())
      await(agentReferenceRepo.create(agentReferenceRecord))
      givenAgentDetailsErrorResponse(arn, 404)

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitations"
      val result = doGetRequest(requestPath)
      result.status shouldBe INTERNAL_SERVER_ERROR

    }

  }

}
