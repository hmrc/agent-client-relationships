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
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiFailureResponse.ErrorBody
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentReferenceRecord
import uk.gov.hmrc.agentclientrelationships.repository.AgentReferenceRepository
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.services.AgentAssuranceService
import uk.gov.hmrc.agentclientrelationships.services.InvitationLinkService
import uk.gov.hmrc.agentclientrelationships.stubs._
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentmtdidentifiers.model.Service._
import uk.gov.hmrc.agentmtdidentifiers.model.SuspensionDetails
import uk.gov.hmrc.auth.core.AuthConnector

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import scala.concurrent.ExecutionContext

class ApiGetInvitationsControllerISpec
extends BaseControllerISpec
with ClientDetailsStub
with HipStub
with TestData {

  override def additionalConfig: Map[String, Any] = Map(
    "hip.enabled" -> true,
    "hip.BusinessDetails.enabled" -> true
  )

  val authConnector: AuthConnector = app.injector.instanceOf[AuthConnector]

  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  lazy val messagesApi: MessagesApi = app.injector.instanceOf[MessagesApi]
  implicit val langs: Langs = app.injector.instanceOf[Langs]
  implicit val lang: Lang = langs.availables.head

  val invitationLinkService: InvitationLinkService = app.injector.instanceOf[InvitationLinkService]
  val agentAssuranceService: AgentAssuranceService = app.injector.instanceOf[AgentAssuranceService]

  val invitationRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val partialAuthRepository: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]
  val agentReferenceRepo: AgentReferenceRepository = app.injector.instanceOf[AgentReferenceRepository]

  val controller =
    new ApiGetInvitationsController(
      invitationLinkService,
      agentAssuranceService,
      invitationRepo,
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

  val vatInvitation: Invitation = Invitation
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

  val trustInvitation: Invitation = Invitation
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

  "get invitations" should {

    // Expected tests
    s"return 200 status and valid JSON when invitations exists for arn " in {
      val invitations: Seq[Invitation] = Seq(
        itsaInvitation,
        itsaSuppInvitation,
        vatInvitation,
        trustInvitation
      )

      invitationRepo.collection.insertMany(invitations).toFuture().futureValue
      agentReferenceRepo.create(agentReferenceRecord).futureValue
      givenAgentRecordFound(arn, testAgentRecord)

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitations"
      val result = doGetRequest(requestPath)
      result.status shouldBe 200

      result.json shouldBe Json.obj(
        "uid" -> agentReferenceRecord.uid,
        "normalizedAgentName" -> normalizedAgencyName,
        "invitations" -> Json.arr(
          Json.obj(
            "created" -> testTime.toString,
            "service" -> itsaInvitation.service,
            "status" -> itsaInvitation.status,
            "expiresOn" -> testDate.toString,
            "invitationId" -> itsaInvitation.invitationId,
            "lastUpdated" -> testTime.toString
          ),
          Json.obj(
            "created" -> testTime.toString,
            "service" -> itsaSuppInvitation.service,
            "status" -> itsaSuppInvitation.status,
            "expiresOn" -> testDate.toString,
            "invitationId" -> itsaSuppInvitation.invitationId,
            "lastUpdated" -> testTime.toString
          ),
          Json.obj(
            "created" -> testTime.toString,
            "service" -> vatInvitation.service,
            "status" -> vatInvitation.status,
            "expiresOn" -> testDate.toString,
            "invitationId" -> vatInvitation.invitationId,
            "lastUpdated" -> testTime.toString
          )
        )
      )

    }

    s"return 204 and valid JSON I when no invitations for arn or supported service" in {
      val invitations: Seq[Invitation] = Seq(
        itsaInvitation.copy(arn = arn2.value),
        itsaSuppInvitation.copy(arn = arn2.value),
        vatInvitation.copy(arn = arn2.value),
        trustInvitation.copy(arn = arn2.value)
      )

      invitationRepo.collection.insertMany(invitations).toFuture().futureValue
      agentReferenceRepo.create(agentReferenceRecord).futureValue
      givenAgentRecordFound(arn, testAgentRecord)

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitations"
      val result = doGetRequest(requestPath)
      result.status shouldBe 200

      result.json shouldBe Json.obj(
        "uid" -> agentReferenceRecord.uid,
        "normalizedAgentName" -> normalizedAgencyName,
        "invitations" -> Json.arr()
      )

    }

    s"return UNPROCESSABLE_ENTITY status and valid JSON AGENT_SUSPENDED when invitation exists but agent is suspended" in {
      val invitations: Seq[Invitation] = Seq(
        itsaInvitation,
        itsaSuppInvitation,
        vatInvitation,
        trustInvitation
      )

      invitationRepo.collection.insertMany(invitations).toFuture().futureValue
      agentReferenceRepo.create(agentReferenceRecord).futureValue
      givenAgentRecordFound(
        arn,
        testAgentRecord.copy(suspensionDetails = Some(SuspensionDetails(suspensionStatus = true, regimes = None)))
      )

      val expectedJson: JsValue = Json.toJson(
        toJson(
          ErrorBody(
            "AGENT_SUSPENDED"
          )
        )
      )

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitations"
      val result = doGetRequest(requestPath)
      result.status shouldBe UNPROCESSABLE_ENTITY
      result.json shouldBe expectedJson

    }

    s"return 500 INTERNAL_SERVER_ERROR status and valid JSON  when invitation exists but agent data not found " in {
      val invitations: Seq[Invitation] = Seq(
        itsaInvitation,
        itsaSuppInvitation,
        vatInvitation,
        trustInvitation
      )

      invitationRepo.collection.insertMany(invitations).toFuture().futureValue
      agentReferenceRepo.create(agentReferenceRecord).futureValue
      givenAgentDetailsErrorResponse(arn, 404)

      val requestPath = s"/agent-client-relationships/api/${arn.value}/invitations"
      val result = doGetRequest(requestPath)
      result.status shouldBe INTERNAL_SERVER_ERROR
    }

  }

}
