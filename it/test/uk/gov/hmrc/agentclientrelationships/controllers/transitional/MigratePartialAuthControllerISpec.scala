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

package uk.gov.hmrc.agentclientrelationships.controllers.transitional

import play.api.libs.json.Json
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.controllers.BaseControllerISpec
import uk.gov.hmrc.agentclientrelationships.model.{Invitation, InvitationStatus, PartialAuth}
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentclientrelationships.services.InvitationService
import uk.gov.hmrc.agentclientrelationships.stubs.{AfiRelationshipStub, AgentAssuranceStubs, ClientDetailsStub, EmailStubs}
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentmtdidentifiers.model.Service._
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.domain.Nino

import java.time.temporal.ChronoUnit.MILLIS
import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}
import scala.concurrent.ExecutionContext

class MigratePartialAuthControllerISpec
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

  val controller =
    new MigratePartialAuthController(
      invitationService,
      stubControllerComponents()
    )

  def invitationRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  def partialAuthRepository: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]

  val clientName = "DummyClientName"
  private val now = LocalDateTime.now().truncatedTo(MILLIS)
  val activeExpiryDate: LocalDate = now.plusDays(21).toLocalDate
  val outOfRangeCreatedDate: LocalDateTime = now.minusDays(30)
  val outOfRangeExpiryDate: LocalDate = now.minusDays(9).toLocalDate
  val baseAltItsaInvitation: Invitation =
    Invitation.createNew(
      arn.value,
      MtdIt,
      mtdItId,
      nino,
      clientName,
      activeExpiryDate,
      Some("personal")
    )

  val activePartialAuthInvitation: Invitation = baseAltItsaInvitation.copy(
    status = PartialAuth,
    clientId = nino.value,
    clientIdType = NinoType.id,
    created = now.minusDays(10).toInstant(ZoneOffset.UTC),
    lastUpdated = now.minusDays(10).toInstant(ZoneOffset.UTC)
  )

  val expiredPartialAuthInvitation: Invitation = baseAltItsaInvitation.copy(
    status = PartialAuth,
    clientId = nino.value,
    clientIdType = NinoType.id,
    created = outOfRangeCreatedDate.toInstant(ZoneOffset.UTC),
    lastUpdated = outOfRangeCreatedDate.toInstant(ZoneOffset.UTC),
    expiryDate = outOfRangeExpiryDate
  )

  def jsonStringForAcaInvitation(invitation: Invitation): String =
    s"""
       |{
       |  "invitationId": "${invitation.invitationId}",
       |  "arn": "${invitation.arn}",
       |  "service": "${invitation.service}",
       |  "clientId": "${invitation.clientId}",
       |  "clientIdType": "${invitation.clientIdType}",
       |  "suppliedClientId": "${invitation.suppliedClientId}",
       |  "suppliedClientIdType": "${invitation.suppliedClientIdType}",
       |  "detailsForEmail": {"agencyEmail": "", "agencyName": "", "clientName": "$clientName"},
       |  "events": [{"time": ${invitation.created.toEpochMilli}, "status": "${invitation.status.toString}"}],
       |  "expiryDate": "${invitation.expiryDate.toString}"
       |}
       |""".stripMargin

  "migratePartialAuth" should {

    "return 204 status, store partial auth record and store active invitation" in {
      val result =
        doAgentPostRequest(
          "/agent-client-relationships/migrate/partial-auth-record",
          jsonStringForAcaInvitation(activePartialAuthInvitation)
        )

      result.status shouldBe 204

      lazy val storedPartialAuth = partialAuthRepository
        .findActive(Nino(activePartialAuthInvitation.clientId), Arn(activePartialAuthInvitation.arn))
        .futureValue

      storedPartialAuth shouldBe defined

      lazy val activeInvitation = invitationRepo
        .findOneById(activePartialAuthInvitation.invitationId)
        .futureValue

      activeInvitation shouldBe defined

      lazy val storedInvitation = activeInvitation.get

      storedInvitation.status shouldBe PartialAuth
      storedInvitation.suppliedClientId shouldBe activePartialAuthInvitation.clientId
      storedInvitation.suppliedClientIdType shouldBe activePartialAuthInvitation.suppliedClientIdType
      storedInvitation.clientId shouldBe activePartialAuthInvitation.clientId
      storedInvitation.clientIdType shouldBe activePartialAuthInvitation.clientIdType
      storedInvitation.service shouldBe activePartialAuthInvitation.service
      storedInvitation.clientName shouldBe clientName
    }

    "return 204 status and store only partial auth record when invitation has expired" in {
      val result =
        doAgentPostRequest(
          "/agent-client-relationships/migrate/partial-auth-record",
          jsonStringForAcaInvitation(expiredPartialAuthInvitation)
        )

      result.status shouldBe 204

      lazy val storedPartialAuth = partialAuthRepository
        .findActive(Nino(expiredPartialAuthInvitation.clientId), Arn(expiredPartialAuthInvitation.arn))
        .futureValue

      storedPartialAuth shouldBe defined

      lazy val activeInvitation = invitationRepo
        .findOneById(activePartialAuthInvitation.invitationId)
        .futureValue

      activeInvitation shouldBe None

    }

    "return BadRequest 400 status when invitation is not partial auth" in {
      val result =
        doAgentPostRequest(
          "/agent-client-relationships/migrate/partial-auth-record",
          jsonStringForAcaInvitation(baseAltItsaInvitation)
        )

      result.status shouldBe 400

    }

  }
}
