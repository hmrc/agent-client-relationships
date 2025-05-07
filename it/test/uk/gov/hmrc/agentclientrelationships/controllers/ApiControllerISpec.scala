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
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiCreateInvitationRequest
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiErrorResults.ErrorBody

import java.time.{Instant, ZoneId}
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentKey, Pending, Rejected}
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentclientrelationships.services.ApiService
import uk.gov.hmrc.agentclientrelationships.stubs._
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentmtdidentifiers.model.Service._
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.auth.core.AuthConnector

import java.time.LocalDate
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

  val testDate: LocalDate = LocalDate.now()
  val testTime: Instant = testDate.atStartOfDay(ZoneId.systemDefault()).toInstant

  val itsaInvitation: Invitation =
    Invitation
      .createNew(
        arn = arn.value,
        service = MtdIt,
        clientId = mtdItId,
        suppliedClientId = nino,
        clientName = "TestClientName",
        agencyName = "testAgentName",
        agencyEmail = "agent@email.com",
        expiryDate = LocalDate.now(),
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
        agencyName = "testAgentName",
        agencyEmail = "agent@email.com",
        expiryDate = LocalDate.now(),
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
        agencyName = "testAgentName",
        agencyEmail = "agent@email.com",
        expiryDate = LocalDate.now(),
        clientType = Some("personal")
      )
      .copy(created = testTime, lastUpdated = testTime)

  val baseInvitationInputData: ApiCreateInvitationRequest =
    ApiCreateInvitationRequest(service = MtdIt.id, suppliedClientId = nino.value, knownFact = "AA1 1AA")

  def getStandartStubForApiInvitation(taxService: String) = {
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
      .copy(service = HMRCMTDVAT, suppliedClientId = vrn.value, knownFact = "2020-01-01"),
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
        agencyName = Some("testAgentName"),
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

        getStandartStubForApiInvitation(taxService)

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

      getStandartStubForApiInvitation(HMRCMTDIT)
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

//      verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    // PENDING INVITATION
    s"return Forbidden status and valid JSON DUPLICATE_AUTHORISATION_REQUEST when ITSA invitation is already exists for ITSA " in {
      val taxService = HMRCMTDIT
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      await(invitationRepo.collection.insertOne(itsaInvitation).toFuture())
      getStandartStubForApiInvitation(taxService)

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

      // verifyCreateInvitationAuditSent(requestPath, invitation) //TODO
    }

    // can not be MAIN and SUPP pending invitations at the same time for the same agent
    s"return Forbidden status and valid JSON DUPLICATE_AUTHORISATION_REQUEST for ITSA MAIN request when ITSA SUPP Pending invitation already exists" in {
      val taxService = HMRCMTDIT
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      val clientId =
        if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) mtdItId.value
        else inputData.suppliedClientId

      await(invitationRepo.collection.insertOne(itsaSuppInvitation).toFuture())
      getStandartStubForApiInvitation(taxService)

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

      // verifyCreateInvitationAuditSent(requestPath, invitation) //TODO
    }

    s"return Forbidden status and valid JSON DUPLICATE_AUTHORISATION_REQUEST for ITSA SUPP request when ITSA MAIN Pending invitation already exists" in {
      val taxService = HMRCMTDITSUPP
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData.copy(service = MtdItSupp.id)

      val clientId =
        if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) mtdItId.value
        else inputData.suppliedClientId

      await(invitationRepo.collection.insertOne(itsaInvitation).toFuture())
      getStandartStubForApiInvitation(taxService)

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

      // verifyCreateInvitationAuditSent(requestPath, invitation) //TODO
    }

    s"return 201 status and valid JSON for ITSA request when Rejected request already exists in repo" in {
      val taxService = HMRCMTDIT
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      val clientId =
        if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) mtdItId.value
        else inputData.suppliedClientId

      await(invitationRepo.collection.insertOne(itsaInvitation.copy(status = Rejected)).toFuture())
      getStandartStubForApiInvitation(taxService)

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

        getStandartStubForApiInvitation(taxService)
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
//    verifyCreateInvitationAuditSent(requestPath, invitation)
      }
    )

    s"return FORBIDDEN status and valid JSON ALREADY_AUTHORISED when request ITSA MAIN but ITSA SUPP relationship already exists" in {

      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      val taxIdentifier = mtdItId

      getStandartStubForApiInvitation(HMRCMTDIT)
      getActiveRelationshipsViaClient(taxIdentifier, arn)
      givenDelegatedGroupIdsExistFor(EnrolmentKey(HMRCMTDITSUPP, taxIdentifier), Set("foo"))

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
      //    verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    s"return FORBIDDEN status and valid JSON ALREADY_AUTHORISED when request ITSA SUPP but ITSA MAIN relationship already exists" in {

      val inputData: ApiCreateInvitationRequest = baseInvitationInputData.copy(service = HMRCMTDITSUPP)

      val taxIdentifier = mtdItId

      getStandartStubForApiInvitation(HMRCMTDITSUPP)
      getActiveRelationshipsViaClient(taxIdentifier, arn)
      givenDelegatedGroupIdsExistFor(EnrolmentKey(HMRCMTDIT, taxIdentifier), Set("foo"))

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
      //    verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    s"return Forbidden status and valid JSON CLIENT_REGISTRATION_NOT_FOUND when invitation is created for Alt Itsa - no client mtdItId and PartialAuth relationship exists" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      getStandartStubForApiInvitation(HMRCMTDIT)
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

      //    verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    s"return Forbidden status and valid JSON ALREADY_AUTHORISED when invitation is created for Alt Itsa - client mtdItId exists and PartialAuth relationship exists" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      getStandartStubForApiInvitation(HMRCMTDIT)

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

      //    verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    s"return Forbidden status and valid JSON ALREADY_AUTHORISED when invitation is created for Alt Itsa - client mtdItId exists and PartialAuth for Alt Itsa Supp relationship exists" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      getStandartStubForApiInvitation(HMRCMTDIT)

      await(
        partialAuthRepository
          .create(
            Instant.parse("2020-01-01T00:00:00.000Z"),
            arn,
            HMRCMTDITSUPP,
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

      //    verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    s"return Forbidden status and valid JSON ALREADY_AUTHORISED when invitation is created for Alt Itsa Supp - client mtdItId exists and PartialAuth for Alt Itsa Main relationship exists" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData.copy(service = HMRCMTDITSUPP)

      getStandartStubForApiInvitation(HMRCMTDITSUPP)

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

      //    verifyCreateInvitationAuditSent(requestPath, invitation)
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
//        verifyCreateInvitationAuditSent(requestPath, invitation)
      }
    )

    s"return BadRequest status and valid JSON CLIENT_ID_DOES_NOT_MATCH_SERVICE for ${Trust.id}" in {
      val inputData: ApiCreateInvitationRequest =
        ApiCreateInvitationRequest(service = Trust.id, suppliedClientId = utr.value, knownFact = "AA1 1AA")

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
      //        verifyCreateInvitationAuditSent(requestPath, invitation) //TODO
    }

    // AGENT
    allServices.keySet.foreach(taxService =>
      s"return Forbidden status and valid JSON AGENT_NOT_SUBSCRIBED when agent is suspended for $taxService" in {
        val inputData: ApiCreateInvitationRequest = allServices(taxService)

        val clientId =
          if (taxService == HMRCMTDIT || taxService == HMRCMTDITSUPP) mtdItId.value
          else inputData.suppliedClientId

        getStandartStubForApiInvitation(taxService)
        givenAgentRecordFound(
          arn,
          testAgentRecord.copy(suspensionDetails = Some(SuspensionDetails(suspensionStatus = true, regimes = None)))
        )

        val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
        val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())

        val expectedJson: JsValue = Json.toJson(
          toJson(
            ErrorBody(
              "AGENT_NOT_SUBSCRIBED",
              "This agent needs to create an agent services account before they can use this service."
            )
          )
        )

        result.status shouldBe FORBIDDEN

        result.json shouldBe expectedJson

//    verifyCreateInvitationAuditSent(requestPath, invitation) //TODO
      }
    )

    allServices.keySet.foreach(taxService =>
      s"return InternalServerError status and error message when agent DES return 404 for $taxService" in {
        val inputData: ApiCreateInvitationRequest = allServices(taxService)

        getStandartStubForApiInvitation(taxService)
        givenAgentDetailsErrorResponse(arn, 404)

        val requestPath = s"/agent-client-relationships/api/${arn.value}/invitation"
        val result = doAgentPostRequest(requestPath, Json.toJson(inputData).toString())

        result.status shouldBe INTERNAL_SERVER_ERROR

        //    verifyCreateInvitationAuditSent(requestPath, invitation) //TODO
      }
    )

    // Client validation
    s"return Forbidden status and valid JSON VAT_CLIENT_INSOLVENT when VAT client is insolvent" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData
        .copy(service = HMRCMTDVAT, suppliedClientId = vrn.value, knownFact = "2020-01-01")

      getStandartStubForApiInvitation(HMRCMTDVAT)
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

      //      verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    // KnowFacts checks
    s"return Forbidden status and valid JSON VAT_REG_DATE_FORMAT_INVALID when VAT knowFact date format is invalid" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData
        .copy(service = HMRCMTDVAT, suppliedClientId = vrn.value, knownFact = "2020/01/01")

      getStandartStubForApiInvitation(HMRCMTDVAT)

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

      //      verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    s"return Forbidden status and valid JSON VAT_REG_DATE_DOES_NOT_MATCH when VAT knowFact date not match" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData
        .copy(service = HMRCMTDVAT, suppliedClientId = vrn.value, knownFact = "2020-01-02")

      getStandartStubForApiInvitation(HMRCMTDVAT)

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

      //      verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    s"return Forbidden status and valid JSON POSTCODE_FORMAT_INVALID when ITSA knowFact postcode is wrong format" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData
        .copy(knownFact = "IAMWRONG12")

      getStandartStubForApiInvitation(HMRCMTDIT)

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

      //      verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    s"return Forbidden status and valid JSON POSTCODE_DOES_NOT_MATCH when ITSA knowFact postcode do not MATCH" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData
        .copy(knownFact = "DM11 8DX")

      getStandartStubForApiInvitation(HMRCMTDIT)

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

      //      verifyCreateInvitationAuditSent(requestPath, invitation)
    }

    s"return Forbidden status and valid JSON POSTCODE_DOES_NOT_MATCH when ITSA client is oversea" in {
      val inputData: ApiCreateInvitationRequest = baseInvitationInputData

      getStandartStubForApiInvitation(HMRCMTDIT)
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

      //      verifyCreateInvitationAuditSent(requestPath, invitation)
    }

  }
}
