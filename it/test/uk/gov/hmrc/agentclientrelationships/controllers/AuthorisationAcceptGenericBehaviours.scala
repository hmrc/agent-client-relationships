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
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.model.Accepted
import uk.gov.hmrc.agentclientrelationships.model.EmailInformation
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.Pending
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCCBCORG
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCMTDIT
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.stubs.EmailStubs
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.domain.TaxIdentifier

import java.time.LocalDate
import java.time.format.DateTimeFormatter

trait AuthorisationAcceptGenericBehaviours {
  this: BaseControllerISpec
    with HipStub
    with EmailStubs =>

  val invitationRepo: InvitationsRepository
  val partialAuthRepository: PartialAuthRepository

  val messagesApi: MessagesApi
  implicit val lang: Lang

  val dateFormatter: DateTimeFormatter

  // noinspection ScalaStyle
  def authorisationAccept(
    serviceId: String,
    suppliedClientId: TaxIdentifier,
    suppliedClientIdType: String
  ): Unit = {

    val isItsa = Seq(HMRCMTDIT, HMRCMTDITSUPP).contains(serviceId)

    val clientId =
      if (isItsa)
        mtdItId
      else
        suppliedClientId

    val clientIdType =
      if (isItsa)
        MtdItIdType.id
      else
        suppliedClientIdType

    val enrolmentKey =
      if (serviceId == Service.Cbc.id)
        EnrolmentKey(s"${Service.Cbc.id}~$clientIdType~${clientId.value}~UTR~3087612352")
      else
        EnrolmentKey(Service.forId(serviceId), clientId)

    // return and email that relation is created
    val emailInfo = EmailInformation(
      to = Seq("agent@email.com"),
      templateId = "client_accepted_authorisation_request",
      parameters = Map(
        "agencyName" -> "testAgentName",
        "clientName" -> "Erling Haal",
        "expiryDate" -> LocalDate.now().format(dateFormatter),
        "service" -> messagesApi(s"service.$serviceId")
      )
    )

    s"PUT /agent-client-relationships/authorisation-response/accept/:invitationId for $serviceId and $suppliedClientIdType " should {

      def getRequestPath(invitationId: String): String = s"/agent-client-relationships/authorisation-response/accept/$invitationId"

      "return 201 when client accept invitation and the relationship do not exists in ES and ETMP " in {

        // OAuth
        givenUserIsSubscribedClient(suppliedClientId)

        // Validation EnrolmentKey
        if (serviceId == HMRCCBCORG)
          givenCbcUkExistsInES(cbcId, utr.value)

        // deleteSameAgentRelationship
        if (isItsa) {
          val serviceToCheck = Service.forId(
            if (serviceId == HMRCMTDIT)
              HMRCMTDITSUPP
            else
              HMRCMTDIT
          )
          val enrolmentKeyToCheck = EnrolmentKey(Service.forId(serviceToCheck.id), clientId)
          givenDelegatedGroupIdsNotExistFor(enrolmentKeyToCheck)
        }

        // deleteSameAgentRelationship - checkRelationshipsService
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")

        // createRelationshipRecord retrieveAgentUser(arn)
        givenAdminUser("foo", "any")

        // createRelationshipRecord createEtmpRecord
        givenAgentCanBeAllocated(clientId, arn)

        // Create relationship -createEsRecord
        givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKey) // no existing enroments
        givenServiceEnrolmentAllocationSucceeds(enrolmentKey, "bar") // ES realtionship creation successed

        // friendlyNameService.updateFriendlyName
        givenUpdateEnrolmentFriendlyNameResponse(
          "foo",
          enrolmentKey.toString,
          NO_CONTENT
        )

        givenCacheRefresh(arn)
        givenEmailSent(emailInfo)

        val pendingInvitation =
          invitationRepo.create(
            arn = arn.value,
            service = Service.forId(serviceId),
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
        verifyServiceEnrolmentAllocationSucceedsCalled(enrolmentKey, "bar")

        verifyInvitationEmailInfoSent(emailInfo)
        verifyRespondToInvitationAuditSent(
          getRequestPath(pendingInvitation.invitationId),
          invitations.head,
          accepted = true,
          isStride = false
        )
      }

      "return 201 when stride accept invitation and the relationship do not exists in ES and ETMP " in {

        // OAuth
        givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-983283")

        // Validation EnrolmentKey
        if (serviceId == HMRCCBCORG)
          givenCbcUkExistsInES(cbcId, utr.value)

        // deleteSameAgentRelationship
        if (isItsa) {
          val serviceToCheck = Service.forId(
            if (serviceId == HMRCMTDIT)
              HMRCMTDITSUPP
            else
              HMRCMTDIT
          )
          val enrolmentKeyToCheck = EnrolmentKey(Service.forId(serviceToCheck.id), clientId)
          givenDelegatedGroupIdsNotExistFor(enrolmentKeyToCheck)
        }

        // deleteSameAgentRelationship - checkRelationshipsService
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")

        // createRelationshipRecord retrieveAgentUser(arn)
        givenAdminUser("foo", "any")

        // createRelationshipRecord createEtmpRecord
        givenAgentCanBeAllocated(clientId, arn)

        // Create relationship -createEsRecord
        givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKey) // no existing enroments
        givenServiceEnrolmentAllocationSucceeds(enrolmentKey, "bar") // ES realtionship creation successed

        // friendlyNameService.updateFriendlyName
        givenUpdateEnrolmentFriendlyNameResponse(
          "foo",
          enrolmentKey.toString,
          NO_CONTENT
        )

        givenCacheRefresh(arn)
        givenEmailSent(emailInfo)

        val pendingInvitation =
          invitationRepo.create(
            arn = arn.value,
            service = Service.forId(serviceId),
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
        verifyServiceEnrolmentAllocationSucceedsCalled(enrolmentKey, "bar")

        verifyInvitationEmailInfoSent(emailInfo)
        verifyRespondToInvitationAuditSent(
          getRequestPath(pendingInvitation.invitationId),
          invitations.head,
          accepted = true,
          isStride = true
        )
      }

      "return 403 when agent accept invitation and the relationship do not exists in ES and ETMP " in {

        // OAuth
        givenUserIsSubscribedAgent(arn)
        givenAgentCanBeAllocated(clientId, arn)
        givenAgentGroupExistsFor("foo")

        // Validation EnrolmentKey
        if (serviceId == HMRCCBCORG)
          givenCbcUkExistsInES(cbcId, utr.value)

        val pendingInvitation =
          invitationRepo.create(
            arn = arn.value,
            service = Service.forId(serviceId),
            clientId = clientId,
            suppliedClientId = suppliedClientId,
            clientName = "Erling Haal",
            agencyName = "testAgentName",
            agencyEmail = "agent@email.com",
            expiryDate = LocalDate.now(),
            clientType = Some("personal")
          ).futureValue

        val result = doAgentPutRequest(getRequestPath(pendingInvitation.invitationId))
        result.status shouldBe 403

        val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(arn.value).futureValue
        invitations.size shouldBe 1
        invitations.head.status shouldBe pendingInvitation.status

        verifyAuditRequestNotSent(AgentClientRelationshipEvent.RespondToInvitation)
      }

      "return 404 when Invitation already Accepted " in {

        // OAuth
        givenUserIsSubscribedClient(suppliedClientId)

        val pendingInvitation =
          invitationRepo.create(
            arn = arn.value,
            service = Service.forId(serviceId),
            clientId = clientId,
            suppliedClientId = suppliedClientId,
            clientName = "Erling Haal",
            agencyName = "testAgentName",
            agencyEmail = "agent@email.com",
            expiryDate = LocalDate.now(),
            clientType = Some("personal")
          ).futureValue

        val acceptedInvitation =
          invitationRepo.updateStatus(
            invitationId = pendingInvitation.invitationId,
            status = Accepted
          ).futureValue

        val result = doAgentPutRequest(getRequestPath(acceptedInvitation.invitationId))
        result.status shouldBe 404

        val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(arn.value).futureValue
        invitations.size shouldBe 1
        invitations.head.status shouldBe acceptedInvitation.status

        // createRelationshipRecord createEtmpRecord called
        verifyAgentCanBeAllocatedCalled(
          clientId,
          arn,
          0
        )

        // Create relationship -createEsRecord called
        verifyServiceEnrolmentAllocationSucceedsCalled(
          enrolmentKey,
          "bar",
          0
        )

      }

      "return 404 when no Invitation " in {
        // OAuth
        givenUserIsSubscribedClient(suppliedClientId)

        val result = doAgentPutRequest(getRequestPath("XX0XXXX000XXXX"))
        result.status shouldBe 404

        val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(arn.value).futureValue
        invitations.size shouldBe 0
      }

      "return 503 when client accept invitation when ES is not available 503, ETMP relation created" in {

        // OAuth
        givenUserIsSubscribedClient(suppliedClientId)

        // Validation EnrolmentKey
        if (serviceId == HMRCCBCORG)
          givenCbcUkExistsInES(cbcId, utr.value)

        // deleteSameAgentRelationship
        if (isItsa) {
          val serviceToCheck = Service.forId(
            if (serviceId == HMRCMTDIT)
              HMRCMTDITSUPP
            else
              HMRCMTDIT
          )
          val enrolmentKeyToCheck = EnrolmentKey(Service.forId(serviceToCheck.id), clientId)
          givenDelegatedGroupIdsNotExistFor(enrolmentKeyToCheck)
        }

        // deleteSameAgentRelationship - checkRelationshipsService
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")

        // createRelationshipRecord retrieveAgentUser(arn)
        givenAdminUser("foo", "any")

        // createRelationshipRecord createEtmpRecord
        givenAgentCanBeAllocated(clientId, arn)

        // Create relationship -createEsRecord
        givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKey) // no existing enroments
        givenEnrolmentAllocationFailsWith(503)(
          groupId = "foo",
          clientUserId = "any",
          enrolmentKey = enrolmentKey,
          agentCode = "bar"
        )

        // friendlyNameService.updateFriendlyName
        givenUpdateEnrolmentFriendlyNameResponse(
          "foo",
          enrolmentKey.toString,
          NO_CONTENT
        )

        givenCacheRefresh(arn)
        givenEmailSent(emailInfo)

        val pendingInvitation =
          invitationRepo.create(
            arn = arn.value,
            service = Service.forId(serviceId),
            clientId = clientId,
            suppliedClientId = suppliedClientId,
            clientName = "Erling Haal",
            agencyName = "testAgentName",
            agencyEmail = "agent@email.com",
            expiryDate = LocalDate.now(),
            clientType = Some("personal")
          ).futureValue

        val result = doAgentPutRequest(getRequestPath(pendingInvitation.invitationId))
        result.status shouldBe 503

        val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(arn.value).futureValue
        invitations.size shouldBe 1
        invitations.head.status shouldBe Pending

        // createRelationshipRecord createEtmpRecord called
        verifyAgentCanBeAllocatedCalled(clientId, arn)

        // Create relationship -createEsRecord called
        verifyServiceEnrolmentAllocationSucceedsCalled(enrolmentKey, "bar")

        verifyAuditRequestNotSent(AgentClientRelationshipEvent.RespondToInvitation)
      }

      "return 502 when client accept invitation when ETMP creation fail, ES - created" in {

        // OAuth
        givenUserIsSubscribedClient(suppliedClientId)

        // Validation EnrolmentKey
        if (serviceId == HMRCCBCORG)
          givenCbcUkExistsInES(cbcId, utr.value)

        // deleteSameAgentRelationship
        if (isItsa) {
          val serviceToCheck = Service.forId(
            if (serviceId == HMRCMTDIT)
              HMRCMTDITSUPP
            else
              HMRCMTDIT
          )
          val enrolmentKeyToCheck = EnrolmentKey(Service.forId(serviceToCheck.id), clientId)
          givenDelegatedGroupIdsNotExistFor(enrolmentKeyToCheck)
        }

        // deleteSameAgentRelationship - checkRelationshipsService
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")

        // createRelationshipRecord retrieveAgentUser(arn)
        givenAdminUser("foo", "any")

        // createRelationshipRecord createEtmpRecord
        givenAgentCanNotBeAllocated(503)

        // Create relationship -createEsRecord
        givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKey) // no existing enroments
        givenServiceEnrolmentAllocationSucceeds(enrolmentKey, "bar") // ES realtionship creation successed

        // friendlyNameService.updateFriendlyName
        givenUpdateEnrolmentFriendlyNameResponse(
          "foo",
          enrolmentKey.toString,
          NO_CONTENT
        )

        givenCacheRefresh(arn)
        givenEmailSent(emailInfo)

        val pendingInvitation =
          invitationRepo.create(
            arn = arn.value,
            service = Service.forId(serviceId),
            clientId = clientId,
            suppliedClientId = suppliedClientId,
            clientName = "Erling Haal",
            agencyName = "testAgentName",
            agencyEmail = "agent@email.com",
            expiryDate = LocalDate.now(),
            clientType = Some("personal")
          ).futureValue

        val result = doAgentPutRequest(getRequestPath(pendingInvitation.invitationId))
        result.status shouldBe 502

        val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(arn.value).futureValue
        invitations.size shouldBe 1
        invitations.head.status shouldBe Pending

        // createRelationshipRecord createEtmpRecord called
        verifyAgentCanBeAllocatedCalled(clientId, arn)

        // Create relationship -createEsRecord called
        verifyServiceEnrolmentAllocationSucceedsCalled(
          enrolmentKey,
          "bar",
          0
        )

        verifyAuditRequestNotSent(AgentClientRelationshipEvent.RespondToInvitation)
      }

      "return 201 when accept invitation and ES relationship exists and ETMP relationship do not exists" in {

        // OAuth
        givenUserIsSubscribedClient(suppliedClientId)

        // Validation EnrolmentKey
        if (serviceId == HMRCCBCORG)
          givenCbcUkExistsInES(cbcId, utr.value)

        // deleteSameAgentRelationship
        if (isItsa) {
          val serviceToCheck = Service.forId(
            if (serviceId == HMRCMTDIT)
              HMRCMTDITSUPP
            else
              HMRCMTDIT
          )
          val enrolmentKeyToCheck = EnrolmentKey(Service.forId(serviceToCheck.id), clientId)
          givenDelegatedGroupIdsNotExistFor(enrolmentKeyToCheck)
        }

        // deleteSameAgentRelationship - checkRelationshipsService
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")

        // createRelationshipRecord retrieveAgentUser(arn)
        givenAdminUser("foo", "any")

        // createRelationshipRecord createEtmpRecord
        givenAgentCanBeAllocated(clientId, arn)

        // Create relationship -createEsRecord exists - dealocate first than allocate
        givenDelegatedGroupIdsExistFor(enrolmentKey = enrolmentKey, groupIds = Set("bar")) // check group
        givenEnrolmentExistsForGroupId("bar", agentEnrolmentKey(Arn("barArn"))) //
        givenEnrolmentDeallocationSucceeds("bar", enrolmentKey) // delete
        givenServiceEnrolmentAllocationSucceeds(enrolmentKey, "bar") // ES realtionship creation successed

        // friendlyNameService.updateFriendlyName
        givenUpdateEnrolmentFriendlyNameResponse(
          "foo",
          enrolmentKey.toString,
          NO_CONTENT
        )

        givenCacheRefresh(arn)
        givenEmailSent(emailInfo)

        val pendingInvitation =
          invitationRepo.create(
            arn = arn.value,
            service = Service.forId(serviceId),
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
        verifyServiceEnrolmentAllocationSucceedsCalled(enrolmentKey, "bar")

        verifyInvitationEmailInfoSent(emailInfo)
        verifyRespondToInvitationAuditSent(
          getRequestPath(pendingInvitation.invitationId),
          invitations.head,
          accepted = true,
          isStride = false
        )
      }

    }
  }

}
