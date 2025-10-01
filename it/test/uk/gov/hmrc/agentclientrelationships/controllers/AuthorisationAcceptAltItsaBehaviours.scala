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

import play.api.i18n.Lang
import play.api.i18n.MessagesApi
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.model.DeAuthorised
import uk.gov.hmrc.agentclientrelationships.model.EmailInformation
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.PartialAuth
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthRelationship
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.stubs.EmailStubs
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.domain.TaxIdentifier

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.concurrent.ExecutionContext.Implicits.global

trait AuthorisationAcceptAltItsaBehaviours {
  this: BaseControllerISpec
    with HipStub
    with EmailStubs =>

  val invitationRepo: InvitationsRepository
  val partialAuthRepository: PartialAuthRepository
  val messagesApi: MessagesApi
  implicit val lang: Lang
  val dateFormatter: DateTimeFormatter

  // noinspection ScalaStyle
  def authorisationAcceptAltItsa(
    serviceIdAccept: String,
    serviceIdCheck: String
  ): Unit = {

    val suppliedClientId: TaxIdentifier = nino
    val clientId = nino
    val emailInfo = EmailInformation(
      to = Seq("agent@email.com"),
      templateId = "client_accepted_authorisation_request",
      parameters = Map(
        "agencyName" -> "testAgentName",
        "clientName" -> "Erling Haal",
        "expiryDate" -> LocalDate.now().format(dateFormatter),
        "service" -> messagesApi(s"service.$serviceIdAccept")
      )
    )

    s"PUT /agent-client-relationships/authorisation-response/accept/:invitationId for AltITSA $serviceIdAccept " should {

      def getRequestPath(invitationId: String): String = s"/agent-client-relationships/authorisation-response/accept/${invitationId}"

      s"return 201 when client accept ${serviceIdAccept} invitation and $serviceIdCheck relationship do not exists" in {

        // OAuth
        givenUserIsSubscribedClient(clientId)

        val enrolmentKeyAccept = EnrolmentKey(Service.forId(serviceIdAccept), clientId)

        // friendlyNameService.updateFriendlyName
        givenUpdateEnrolmentFriendlyNameResponse(
          "foo",
          enrolmentKeyAccept.toString,
          NO_CONTENT
        )

        givenCacheRefresh(arn)
        givenEmailSent(emailInfo)

        val pendingInvitation =
          invitationRepo.create(
            arn = arn.value,
            service = Service.forId(serviceIdAccept),
            clientId = clientId,
            suppliedClientId = suppliedClientId,
            clientName = "Erling Haal",
            agencyName = "testAgentName",
            agencyEmail = "agent@email.com",
            expiryDate = LocalDate.now(),
            clientType = Some("personal")
          ).futureValue

        val result = doAgentPutRequest(getRequestPath(pendingInvitation.invitationId))
        result.status shouldBe 204

        val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(arn.value).futureValue
        invitations.size shouldBe 1
        invitations.head.status shouldBe PartialAuth

        val partialAuthInvitations: Option[PartialAuthRelationship] = partialAuthRepository.findActive(nino, arn).futureValue
        partialAuthInvitations.size shouldBe 1
        partialAuthInvitations.head.active shouldBe true

        // createRelationshipRecord createEtmpRecord NOT called
        verifyAgentCanBeAllocatedCalled(
          clientId,
          arn,
          0
        )

        // Create relationship -createEsRecord NOT called
        verifyServiceEnrolmentAllocationSucceedsCalled(
          enrolmentKeyAccept,
          "bar",
          0
        )

        verifyInvitationEmailInfoSent(emailInfo)
        verifyRespondToInvitationAuditSent(
          getRequestPath(pendingInvitation.invitationId),
          invitations.head,
          accepted = true,
          isStride = false
        )
      }

      s"return 201 when accept ${serviceIdAccept} invitation and relationship exists for $serviceIdCheck " in {

        // OAuth
        givenUserIsSubscribedClient(clientId)

        val enrolmentKeyAccept = EnrolmentKey(Service.forId(serviceIdAccept), clientId)

        // friendlyNameService.updateFriendlyName
        givenUpdateEnrolmentFriendlyNameResponse(
          "foo",
          enrolmentKeyAccept.toString,
          NO_CONTENT
        )

        givenCacheRefresh(arn)
        givenEmailSent(emailInfo)

        // insert existing PartialAuth invitation
        invitationRepo.create(
          arn = arn.value,
          service = Service.forId(serviceIdCheck),
          clientId = clientId,
          suppliedClientId = suppliedClientId,
          clientName = "Erling Haal",
          agencyName = "testAgentName",
          agencyEmail = "agent@email.com",
          expiryDate = LocalDate.now(),
          clientType = Some("personal")
        ).map(invitation => invitationRepo.updateStatus(invitation.invitationId, PartialAuth))
          .futureValue

        // insert existing PartialAuth Repo
        partialAuthRepository
          .create(
            Instant.parse("2020-01-01T00:00:00.000Z"),
            arn,
            serviceIdCheck,
            nino
          ).futureValue

        // new invitation to be accepted
        val pendingInvitationSupport =
          invitationRepo.create(
            arn = arn.value,
            service = Service.forId(serviceIdAccept),
            clientId = clientId,
            suppliedClientId = suppliedClientId,
            clientName = "Erling Haal",
            agencyName = "testAgentName",
            agencyEmail = "agent@email.com",
            expiryDate = LocalDate.now(),
            clientType = Some("personal")
          ).futureValue

        val result = doAgentPutRequest(getRequestPath(pendingInvitationSupport.invitationId))
        result.status shouldBe 204

        val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(arn.value).futureValue
        invitations.size shouldBe 2
        invitations.exists(_.status == PartialAuth) shouldBe true
        invitations.exists(_.status == DeAuthorised) shouldBe true

        val partialAuthInvitationsAccepted: Option[PartialAuthRelationship] =
          partialAuthRepository.findActive(
            serviceIdAccept,
            nino,
            arn
          ).futureValue
        partialAuthInvitationsAccepted.size shouldBe 1
        partialAuthInvitationsAccepted.head.active shouldBe true

        val partialAuthInvitationsDeAuth: Option[PartialAuthRelationship] =
          partialAuthRepository.findActive(
            serviceIdCheck,
            nino,
            arn
          ).futureValue
        partialAuthInvitationsDeAuth.size shouldBe 0

        verifyInvitationEmailInfoSent(emailInfo)
        verifyRespondToInvitationAuditSent(
          getRequestPath(pendingInvitationSupport.invitationId),
          invitations.filter(_.status == PartialAuth).head,
          accepted = true,
          isStride = false
        )
      }

    }
  }

}
