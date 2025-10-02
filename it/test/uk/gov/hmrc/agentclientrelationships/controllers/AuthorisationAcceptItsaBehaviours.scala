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
import uk.gov.hmrc.agentclientrelationships.model.Accepted
import uk.gov.hmrc.agentclientrelationships.model.DeAuthorised
import uk.gov.hmrc.agentclientrelationships.model.EmailInformation
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.stubs.EmailStubs
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.domain.TaxIdentifier

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.concurrent.ExecutionContext.Implicits.global

trait AuthorisationAcceptItsaBehaviours {
  this: BaseControllerISpec
    with HipStub
    with EmailStubs =>

  val invitationRepo: InvitationsRepository
  val messagesApi: MessagesApi
  implicit val lang: Lang
  val dateFormatter: DateTimeFormatter

  // noinspection ScalaStyle
  def authorisationAcceptItsa(
    serviceIdAccept: String,
    serviceIdCheck: String
  ): Unit = {

    val suppliedClientId: TaxIdentifier = nino
    val clientId = mtdItId
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

    s"PUT /agent-client-relationships/authorisation-response/accept/:invitationId for ITSA $serviceIdAccept " should {

      def getRequestPath(invitationId: String): String = s"/agent-client-relationships/authorisation-response/accept/${invitationId}"

      s"return 201 when client accept ${serviceIdAccept} invitation and $serviceIdCheck relationship do not exists" in {

        // OAuth
        givenUserIsSubscribedClient(clientId)

        val enrolmentKeyAccept = EnrolmentKey(Service.forId(serviceIdAccept), clientId)
        val enrolmentKeyToCheck = EnrolmentKey(Service.forId(serviceIdCheck), clientId)

        // checkRelationshipsService and delete if exists for HMRCMTDIT, NOT HMRCMTDITSUPP in ES
        if (serviceIdAccept == HMRCMTDITSUPP)
          givenDelegatedGroupIdsNotExistFor(enrolmentKeyToCheck)
        else {
          givenDelegatedGroupIdsNotExistFor(enrolmentKeyToCheck)
          givenDelegatedGroupIdsNotExistFor(enrolmentKeyAccept)
        }
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")

        // createRelationshipRecord retrieveAgentUser(arn)
        givenAdminUser("foo", "any")

        // createRelationshipRecord createEtmpRecord
        givenAgentCanBeAllocated(clientId, arn)

        // Create relationship -createEsRecord
        givenServiceEnrolmentAllocationSucceeds(enrolmentKeyAccept, "bar") // ES realtionship creation successed

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
        invitations.head.status shouldBe Accepted

        // createRelationshipRecord createEtmpRecord called
        verifyAgentCanBeAllocatedCalled(clientId, arn)

        // Create relationship -createEsRecord called
        verifyServiceEnrolmentAllocationSucceedsCalled(enrolmentKeyAccept, "bar")

        verifyInvitationEmailInfoSent(emailInfo)
        verifyRespondToInvitationAuditSent(
          getRequestPath(pendingInvitation.invitationId),
          invitations.head,
          accepted = true,
          isStride = false
        )
      }

      s"return 201 when accept ${serviceIdAccept} invitation and ES relationship exists for $serviceIdCheck " in {

        // OAuth
        givenUserIsSubscribedClient(clientId)

        val enrolmentKeyAccept = EnrolmentKey(Service.forId(serviceIdAccept), clientId)
        val enrolmentKeyToCheck = EnrolmentKey(Service.forId(serviceIdCheck), clientId)

        // checkRelationshipsService for HMRCMTDIT
        givenDelegatedGroupIdsExistFor(enrolmentKeyToCheck, Set("foo")) // exists for HMRCMTDIT in ETMP
        givenEnrolmentDeallocationSucceeds("foo", enrolmentKeyToCheck) // delete ES
        givenAgentCanBeDeallocated(clientId, arn) // delete ETMP
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")

        // createRelationshipRecord retrieveAgentUser(arn)
        givenAdminUser("foo", "any")

        // createRelationshipRecord createEtmpRecord
        givenAgentCanBeAllocated(clientId, arn)

        // Create relationship -createEsRecord exists - dealocate first than allocate
        givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKeyAccept) // no existing enroments
        givenServiceEnrolmentAllocationSucceeds(enrolmentKeyAccept, "bar") // ES realtionship creation successed

        // friendlyNameService.updateFriendlyName
        givenUpdateEnrolmentFriendlyNameResponse(
          "foo",
          enrolmentKeyAccept.toString,
          NO_CONTENT
        )

        givenCacheRefresh(arn)
        givenEmailSent(emailInfo)

        // existing invitation
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
        ).map(invitation => invitationRepo.updateStatus(invitation.invitationId, Accepted))
          .futureValue

        // new invitation
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
        invitations.exists(_.status == Accepted) shouldBe true
        invitations.exists(_.status == DeAuthorised) shouldBe true

        // checkRelationshipsService for HMRCMTDIT called
        verifyEnrolmentDeallocationAttempt("foo", enrolmentKeyToCheck) // dealocate ETMP called 1
        verifyAgentDeallocationCalled(clientId, arn) // dealocate ES called 1

        // createRelationshipRecord createEtmpRecord called
        verifyAgentCanBeAllocatedCalled(clientId, arn)

        // Create relationship -createEsRecord called
        verifyServiceEnrolmentAllocationSucceedsCalled(enrolmentKeyAccept, "bar")

        verifyInvitationEmailInfoSent(emailInfo)
        verifyRespondToInvitationAuditSent(
          getRequestPath(pendingInvitationSupport.invitationId),
          invitations.filter(_.status == Accepted).head,
          accepted = true,
          isStride = false
        )
      }

    }
  }

}
