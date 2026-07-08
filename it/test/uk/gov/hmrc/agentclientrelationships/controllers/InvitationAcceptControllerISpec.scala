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
import play.api.i18n.Langs
import play.api.i18n.MessagesApi
import play.api.test.Helpers.NO_CONTENT
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.model.Accepted
import uk.gov.hmrc.agentclientrelationships.model.DeAuthorised
import uk.gov.hmrc.agentclientrelationships.model.EmailInformation
import uk.gov.hmrc.agentclientrelationships.model.Expired
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.PartialAuth
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthRelationship
import uk.gov.hmrc.agentclientrelationships.model.Pending
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.stubs.AfiRelationshipStub
import uk.gov.hmrc.agentclientrelationships.stubs.AucdStubs
import uk.gov.hmrc.agentclientrelationships.stubs.AuthStub
import uk.gov.hmrc.agentclientrelationships.stubs.DataStreamStub
import uk.gov.hmrc.agentclientrelationships.stubs.EmailStubs
import uk.gov.hmrc.agentclientrelationships.stubs.EnrolmentStoreProxyStubs
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.agentclientrelationships.stubs.RelationshipStubs
import uk.gov.hmrc.agentclientrelationships.stubs.UsersGroupsSearchStubs
import uk.gov.hmrc.agentclientrelationships.testsupport.testdata.IrvTestData
import uk.gov.hmrc.agentclientrelationships.testsupport.testdata.ItsaSuppTestData
import uk.gov.hmrc.agentclientrelationships.testsupport.testdata.ItsaTestData
import uk.gov.hmrc.agentclientrelationships.testsupport.testdata.TestData

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import scala.concurrent.ExecutionContext

class InvitationAcceptControllerISpec
extends BaseISpec
with HipStub
with EmailStubs {

  val invitationRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val partialAuthRepository: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]
  val messagesApi: MessagesApi = app.injector.instanceOf[MessagesApi]
  val langs: Langs = app.injector.instanceOf[Langs]
  val lang: Lang = langs.availables.head
  val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.UK)
  implicit val executionContext: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  def getRequestPath(invitationId: String): String = s"/agent-client-relationships/authorisation-response/accept/$invitationId"

  TestData.services.foreach { regimeData =>
    val testEmail = EmailInformation(
      to = Seq("agent@email.com"),
      templateId = "client_accepted_authorisation_request",
      parameters = Map(
        "agencyName" -> "testAgentName",
        "clientName" -> "Erling Haal",
        "expiryDate" -> LocalDate.now().format(dateFormatter),
        "service" -> messagesApi(s"service.${regimeData.service.id}")(lang)
      )
    )
    s"PUT /agent-client-relationships/authorisation-response/accept/:invitationId" +
      s" for ${regimeData.service.id} and ${regimeData.suppliedClientId.value}" should {
        "return 201 when client accepts invitation successfully when authorised for someone else" in {
          // Authorised as client
          regimeData.clientAuthStubs()

          // Lookup clientId if different from suppliedClientId
          regimeData.clientIdLookupStubs()

          // Lookup for secondary enrolment identifier if needed
          regimeData.clientKnownFactCheckStubs()

          // Remove existing relationships for supporting services if needed
          regimeData.supportingService.foreach { supp =>
            val suppEnrolment = regimeData.enrolment.copy(service = supp.id)
            HipStub.givenAgentCanBeDeallocated(regimeData.clientId, TestData.arn)
            RelationshipStubs.givenPrincipalAgentUser(TestData.arn, TestData.groupId)
            RelationshipStubs.givenDelegatedGroupIdsExistFor(suppEnrolment, Set(TestData.groupId))
            RelationshipStubs.givenEnrolmentDeallocationSucceeds(TestData.groupId, suppEnrolment)
          }

          // Fetch agent details
          RelationshipStubs.givenPrincipalAgentUser(TestData.arn, TestData.groupId)
          UsersGroupsSearchStubs.givenGroupInfo(TestData.groupId, TestData.agentCode)
          UsersGroupsSearchStubs.givenAdminUser(TestData.groupId, TestData.adminUser)

          // Create ETMP relationship
          HipStub.givenAgentCanBeAllocated(regimeData.clientId, TestData.arn)

          // Check existing EACD relationship with other agent and remove
          if (!regimeData.multipleAgents) {
            RelationshipStubs.givenDelegatedGroupIdsExistFor(regimeData.enrolment, Set(TestData.groupId2))
            RelationshipStubs.givenEnrolmentExistsForGroupId(TestData.groupId2, RelationshipStubs.agentEnrolmentKey(TestData.arn))
            RelationshipStubs.givenEnrolmentDeallocationSucceeds(TestData.groupId2, regimeData.enrolment)
          }

          // Create EACD relationship
          EnrolmentStoreProxyStubs.givenEnrolmentAllocationSucceeds(
            groupId = TestData.groupId,
            clientUserId = TestData.adminUser,
            enrolmentKey = regimeData.enrolment,
            agentCode = TestData.agentCode
          )

          EnrolmentStoreProxyStubs.givenUpdateEnrolmentFriendlyNameResponse(
            TestData.groupId,
            regimeData.enrolment.tag,
            NO_CONTENT
          )
          AucdStubs.givenCacheRefresh(TestData.arn)
          EmailStubs.givenEmailSent(testEmail)

          val pendingInvitation =
            invitationRepo.create(
              arn = TestData.arn.value,
              service = Service.forId(regimeData.service.id),
              suppliedClientId = regimeData.suppliedClientId,
              clientName = "Erling Haal",
              agencyName = "testAgentName",
              agencyEmail = "agent@email.com",
              expiryDate = LocalDate.now(),
              clientType = Some("personal")
            ).futureValue

          val result = doAgentPutRequest(getRequestPath(pendingInvitation.invitationId))
          result.status shouldBe 204

          val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(TestData.arn.value).futureValue
          invitations.size shouldBe 1
          invitations.head.status shouldBe Accepted

          // ETMP relationship created
          HipStub.verifyAgentCanBeAllocatedCalled(regimeData.clientId, TestData.arn)

          // EACD relationship created
          EnrolmentStoreProxyStubs.verifyEnrolmentAllocationAttempt(
            groupId = TestData.groupId,
            clientUserId = TestData.adminUser,
            enrolmentKey = regimeData.enrolment,
            agentCode = TestData.agentCode
          )

          EmailStubs.verifyInvitationEmailInfoSent(testEmail)
          DataStreamStub.verifyRespondToInvitationAuditSent(
            getRequestPath(pendingInvitation.invitationId),
            invitations.head,
            accepted = true,
            isStride = false,
            Some(regimeData.enrolment)
          )
        }

        "return 201 when client accepts invitation successfully" in {
          // Authorised as client
          regimeData.clientAuthStubs()

          // Lookup clientId if different from suppliedClientId
          regimeData.clientIdLookupStubs()

          // Lookup for secondary enrolment identifier if needed
          regimeData.clientKnownFactCheckStubs()

          // Remove existing relationships for supporting services if needed
          regimeData.supportingService.foreach { supp =>
            val suppEnrolment = regimeData.enrolment.copy(service = supp.id)
            HipStub.givenAgentCanBeDeallocated(regimeData.clientId, TestData.arn)
            RelationshipStubs.givenPrincipalAgentUser(TestData.arn, TestData.groupId)
            RelationshipStubs.givenDelegatedGroupIdsExistFor(suppEnrolment, Set(TestData.groupId))
            RelationshipStubs.givenEnrolmentDeallocationSucceeds(TestData.groupId, suppEnrolment)
          }

          // Fetch agent details
          RelationshipStubs.givenPrincipalAgentUser(TestData.arn, TestData.groupId)
          UsersGroupsSearchStubs.givenGroupInfo(TestData.groupId, TestData.agentCode)
          UsersGroupsSearchStubs.givenAdminUser(TestData.groupId, TestData.adminUser)

          // Create ETMP relationship
          HipStub.givenAgentCanBeAllocated(regimeData.clientId, TestData.arn)

          // Check existing EACD relationship with other agent
          if (!regimeData.multipleAgents) {
            RelationshipStubs.givenDelegatedGroupIdsNotExistFor(regimeData.enrolment)
          }

          // Create EACD relationship
          EnrolmentStoreProxyStubs.givenEnrolmentAllocationSucceeds(
            groupId = TestData.groupId,
            clientUserId = TestData.adminUser,
            enrolmentKey = regimeData.enrolment,
            agentCode = TestData.agentCode
          )

          EnrolmentStoreProxyStubs.givenUpdateEnrolmentFriendlyNameResponse(
            TestData.groupId,
            regimeData.enrolment.tag,
            NO_CONTENT
          )
          AucdStubs.givenCacheRefresh(TestData.arn)
          EmailStubs.givenEmailSent(testEmail)

          val pendingInvitation =
            invitationRepo.create(
              arn = TestData.arn.value,
              service = Service.forId(regimeData.service.id),
              suppliedClientId = regimeData.suppliedClientId,
              clientName = "Erling Haal",
              agencyName = "testAgentName",
              agencyEmail = "agent@email.com",
              expiryDate = LocalDate.now(),
              clientType = Some("personal")
            ).futureValue

          val result = doAgentPutRequest(getRequestPath(pendingInvitation.invitationId))
          result.status shouldBe 204

          val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(TestData.arn.value).futureValue
          invitations.size shouldBe 1
          invitations.head.status shouldBe Accepted

          // ETMP relationship created
          HipStub.verifyAgentCanBeAllocatedCalled(regimeData.clientId, TestData.arn)

          // EACD relationship created
          EnrolmentStoreProxyStubs.verifyEnrolmentAllocationAttempt(
            groupId = TestData.groupId,
            clientUserId = TestData.adminUser,
            enrolmentKey = regimeData.enrolment,
            agentCode = TestData.agentCode
          )

          EmailStubs.verifyInvitationEmailInfoSent(testEmail)
          DataStreamStub.verifyRespondToInvitationAuditSent(
            getRequestPath(pendingInvitation.invitationId),
            invitations.head,
            accepted = true,
            isStride = false,
            Some(regimeData.enrolment)
          )
        }

        "return 201 when stride accepts invitation successfully" in {
          // Authorised as stride
          AuthStub.givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-983283")

          // Lookup clientId if different from suppliedClientId
          regimeData.clientIdLookupStubs()

          // Lookup for secondary enrolment identifier if needed
          regimeData.clientKnownFactCheckStubs()

          // Remove existing relationships for supporting services if needed
          regimeData.supportingService.foreach { supp =>
            val suppEnrolment = regimeData.enrolment.copy(service = supp.id)
            HipStub.givenAgentCanBeDeallocated(regimeData.clientId, TestData.arn)
            RelationshipStubs.givenPrincipalAgentUser(TestData.arn, TestData.groupId)
            RelationshipStubs.givenDelegatedGroupIdsExistFor(suppEnrolment, Set(TestData.groupId))
            RelationshipStubs.givenEnrolmentDeallocationSucceeds(TestData.groupId, suppEnrolment)
          }

          // Fetch agent details
          RelationshipStubs.givenPrincipalAgentUser(TestData.arn, TestData.groupId)
          UsersGroupsSearchStubs.givenGroupInfo(TestData.groupId, TestData.agentCode)
          UsersGroupsSearchStubs.givenAdminUser(TestData.groupId, TestData.adminUser)

          // Create ETMP relationship
          HipStub.givenAgentCanBeAllocated(regimeData.clientId, TestData.arn)

          // Check existing EACD relationship with other agent
          if (!regimeData.multipleAgents) {
            RelationshipStubs.givenDelegatedGroupIdsNotExistFor(regimeData.enrolment)
          }

          // Create EACD relationship
          EnrolmentStoreProxyStubs.givenEnrolmentAllocationSucceeds(
            groupId = TestData.groupId,
            clientUserId = TestData.adminUser,
            enrolmentKey = regimeData.enrolment,
            agentCode = TestData.agentCode
          )

          EnrolmentStoreProxyStubs.givenUpdateEnrolmentFriendlyNameResponse(
            TestData.groupId,
            regimeData.enrolment.tag,
            NO_CONTENT
          )
          AucdStubs.givenCacheRefresh(TestData.arn)
          EmailStubs.givenEmailSent(testEmail)

          val pendingInvitation =
            invitationRepo.create(
              arn = TestData.arn.value,
              service = Service.forId(regimeData.service.id),
              suppliedClientId = regimeData.suppliedClientId,
              clientName = "Erling Haal",
              agencyName = "testAgentName",
              agencyEmail = "agent@email.com",
              expiryDate = LocalDate.now(),
              clientType = Some("personal")
            ).futureValue

          val result = doAgentPutRequest(getRequestPath(pendingInvitation.invitationId))
          result.status shouldBe 204

          val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(TestData.arn.value).futureValue
          invitations.size shouldBe 1
          invitations.head.status shouldBe Accepted

          // ETMP relationship created
          HipStub.verifyAgentCanBeAllocatedCalled(regimeData.clientId, TestData.arn)

          // EACD relationship created
          EnrolmentStoreProxyStubs.verifyEnrolmentAllocationAttempt(
            groupId = TestData.groupId,
            clientUserId = TestData.adminUser,
            enrolmentKey = regimeData.enrolment,
            agentCode = TestData.agentCode
          )

          EmailStubs.verifyInvitationEmailInfoSent(testEmail)
          DataStreamStub.verifyRespondToInvitationAuditSent(
            getRequestPath(pendingInvitation.invitationId),
            invitations.head,
            accepted = true,
            isStride = true,
            Some(regimeData.enrolment)
          )
        }

        "return 403 when agent tries to accept invitation" in {
          // Authorised as agent
          AuthStub.givenUserIsSubscribedAgent(TestData.arn)

          // Lookup clientId if different from suppliedClientId
          regimeData.clientIdLookupStubs()

          // Lookup for secondary enrolment identifier if needed
          regimeData.clientKnownFactCheckStubs()

          val pendingInvitation =
            invitationRepo.create(
              arn = TestData.arn.value,
              service = Service.forId(regimeData.service.id),
              suppliedClientId = regimeData.suppliedClientId,
              clientName = "Erling Haal",
              agencyName = "testAgentName",
              agencyEmail = "agent@email.com",
              expiryDate = LocalDate.now(),
              clientType = Some("personal")
            ).futureValue

          val result = doAgentPutRequest(getRequestPath(pendingInvitation.invitationId))
          result.status shouldBe 403

          val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(TestData.arn.value).futureValue
          invitations.size shouldBe 1
          invitations.head.status shouldBe Pending

          // ETMP relationship not created
          HipStub.verifyAgentCanBeAllocatedCalled(
            regimeData.clientId,
            TestData.arn,
            count = 0
          )

          // EACD relationship not created
          EnrolmentStoreProxyStubs.verifyEnrolmentAllocationAttempt(
            groupId = TestData.groupId,
            clientUserId = TestData.adminUser,
            enrolmentKey = regimeData.enrolment,
            agentCode = TestData.agentCode,
            count = 0
          )

          DataStreamStub.verifyAuditRequestNotSent(AgentClientRelationshipEvent.RespondToInvitation)
        }

        "return 204 when invitation is already Accepted" in {
          // Authorised as client
          regimeData.clientAuthStubs()

          // Lookup clientId if different from suppliedClientId
          regimeData.clientIdLookupStubs()

          // Lookup for secondary enrolment identifier if needed
          regimeData.clientKnownFactCheckStubs()

          val pendingInvitation =
            invitationRepo.create(
              arn = TestData.arn.value,
              service = Service.forId(regimeData.service.id),
              suppliedClientId = regimeData.suppliedClientId,
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
          result.status shouldBe 204

          val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(TestData.arn.value).futureValue
          invitations.size shouldBe 1
          invitations.head.status shouldBe Accepted

          // ETMP relationship not created
          HipStub.verifyAgentCanBeAllocatedCalled(
            regimeData.clientId,
            TestData.arn,
            count = 0
          )

          // EACD relationship not created
          EnrolmentStoreProxyStubs.verifyEnrolmentAllocationAttempt(
            groupId = TestData.groupId,
            clientUserId = TestData.adminUser,
            enrolmentKey = regimeData.enrolment,
            agentCode = TestData.agentCode,
            count = 0
          )

          DataStreamStub.verifyAuditRequestNotSent(AgentClientRelationshipEvent.RespondToInvitation)
        }

        "return 500 when invitation is in an unexpected state" in {
          // Authorised as client
          regimeData.clientAuthStubs()

          // Lookup clientId if different from suppliedClientId
          regimeData.clientIdLookupStubs()

          // Lookup for secondary enrolment identifier if needed
          regimeData.clientKnownFactCheckStubs()

          val pendingInvitation =
            invitationRepo.create(
              arn = TestData.arn.value,
              service = Service.forId(regimeData.service.id),
              suppliedClientId = regimeData.suppliedClientId,
              clientName = "Erling Haal",
              agencyName = "testAgentName",
              agencyEmail = "agent@email.com",
              expiryDate = LocalDate.now(),
              clientType = Some("personal")
            ).futureValue

          val acceptedInvitation =
            invitationRepo.updateStatus(
              invitationId = pendingInvitation.invitationId,
              status = Expired
            ).futureValue

          val result = doAgentPutRequest(getRequestPath(acceptedInvitation.invitationId))
          result.status shouldBe 500

          val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(TestData.arn.value).futureValue
          invitations.size shouldBe 1
          invitations.head.status shouldBe Expired

          // ETMP relationship not created
          HipStub.verifyAgentCanBeAllocatedCalled(
            regimeData.clientId,
            TestData.arn,
            count = 0
          )

          // EACD relationship not created
          EnrolmentStoreProxyStubs.verifyEnrolmentAllocationAttempt(
            groupId = TestData.groupId,
            clientUserId = TestData.adminUser,
            enrolmentKey = regimeData.enrolment,
            agentCode = TestData.agentCode,
            count = 0
          )

          DataStreamStub.verifyAuditRequestNotSent(AgentClientRelationshipEvent.RespondToInvitation)
        }

        "return 404 when invitation does not exist" in {
          // Authorised as client
          regimeData.clientAuthStubs()

          // Lookup clientId if different from suppliedClientId
          regimeData.clientIdLookupStubs()

          // Lookup for secondary enrolment identifier if needed
          regimeData.clientKnownFactCheckStubs()

          val result = doAgentPutRequest(getRequestPath("XX0XXXX000XXXX"))
          result.status shouldBe 404

          val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(arn.value).futureValue
          invitations.size shouldBe 0
        }

        "return 503 when client accepts invitation but EACD returns error" in {
          // Authorised as client
          regimeData.clientAuthStubs()

          // Lookup clientId if different from suppliedClientId
          regimeData.clientIdLookupStubs()

          // Lookup for secondary enrolment identifier if needed
          regimeData.clientKnownFactCheckStubs()

          // Remove existing relationships for supporting services if needed
          regimeData.supportingService.foreach { supp =>
            val suppEnrolment = regimeData.enrolment.copy(service = supp.id)
            HipStub.givenAgentCanBeDeallocated(regimeData.clientId, TestData.arn)
            RelationshipStubs.givenPrincipalAgentUser(TestData.arn, TestData.groupId)
            RelationshipStubs.givenDelegatedGroupIdsExistFor(suppEnrolment, Set(TestData.groupId))
            RelationshipStubs.givenEnrolmentDeallocationSucceeds(TestData.groupId, suppEnrolment)
          }

          // Fetch agent details
          RelationshipStubs.givenPrincipalAgentUser(TestData.arn, TestData.groupId)
          UsersGroupsSearchStubs.givenGroupInfo(TestData.groupId, TestData.agentCode)
          UsersGroupsSearchStubs.givenAdminUser(TestData.groupId, TestData.adminUser)

          // Create ETMP relationship
          HipStub.givenAgentCanBeAllocated(regimeData.clientId, TestData.arn)

          // Check existing EACD relationship with other agent
          if (!regimeData.multipleAgents) {
            RelationshipStubs.givenDelegatedGroupIdsNotExistFor(regimeData.enrolment)
          }

          // Create EACD relationship fails
          EnrolmentStoreProxyStubs.givenEnrolmentAllocationFailsWith(502)(
            groupId = TestData.groupId,
            clientUserId = TestData.adminUser,
            enrolmentKey = regimeData.enrolment,
            agentCode = TestData.agentCode
          )

          val pendingInvitation =
            invitationRepo.create(
              arn = TestData.arn.value,
              service = Service.forId(regimeData.service.id),
              suppliedClientId = regimeData.suppliedClientId,
              clientName = "Erling Haal",
              agencyName = "testAgentName",
              agencyEmail = "agent@email.com",
              expiryDate = LocalDate.now(),
              clientType = Some("personal")
            ).futureValue

          val result = doAgentPutRequest(getRequestPath(pendingInvitation.invitationId))
          result.status shouldBe 502

          val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(TestData.arn.value).futureValue
          invitations.size shouldBe 1
          invitations.head.status shouldBe Pending

          // ETMP relationship created
          HipStub.verifyAgentCanBeAllocatedCalled(regimeData.clientId, TestData.arn)

          // EACD relationship created
          EnrolmentStoreProxyStubs.verifyEnrolmentAllocationAttempt(
            groupId = TestData.groupId,
            clientUserId = TestData.adminUser,
            enrolmentKey = regimeData.enrolment,
            agentCode = TestData.agentCode
          )

          DataStreamStub.verifyAuditRequestNotSent(AgentClientRelationshipEvent.RespondToInvitation)
        }

        "return 502 when client accepts invitation but ETMP returns error" in {
          // Authorised as client
          regimeData.clientAuthStubs()

          // Lookup clientId if different from suppliedClientId
          regimeData.clientIdLookupStubs()

          // Lookup for secondary enrolment identifier if needed
          regimeData.clientKnownFactCheckStubs()

          // Remove existing relationships for supporting services if needed
          regimeData.supportingService.foreach { supp =>
            val suppEnrolment = regimeData.enrolment.copy(service = supp.id)
            HipStub.givenAgentCanBeDeallocated(regimeData.clientId, TestData.arn)
            RelationshipStubs.givenPrincipalAgentUser(TestData.arn, TestData.groupId)
            RelationshipStubs.givenDelegatedGroupIdsExistFor(suppEnrolment, Set(TestData.groupId))
            RelationshipStubs.givenEnrolmentDeallocationSucceeds(TestData.groupId, suppEnrolment)
          }

          // Fetch agent details
          RelationshipStubs.givenPrincipalAgentUser(TestData.arn, TestData.groupId)
          UsersGroupsSearchStubs.givenGroupInfo(TestData.groupId, TestData.agentCode)
          UsersGroupsSearchStubs.givenAdminUser(TestData.groupId, TestData.adminUser)

          // Create ETMP relationship fails
          HipStub.givenAgentCanNotBeAllocated(502)

          val pendingInvitation =
            invitationRepo.create(
              arn = TestData.arn.value,
              service = Service.forId(regimeData.service.id),
              suppliedClientId = regimeData.suppliedClientId,
              clientName = "Erling Haal",
              agencyName = "testAgentName",
              agencyEmail = "agent@email.com",
              expiryDate = LocalDate.now(),
              clientType = Some("personal")
            ).futureValue

          val result = doAgentPutRequest(getRequestPath(pendingInvitation.invitationId))
          result.status shouldBe 502

          val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(TestData.arn.value).futureValue
          invitations.size shouldBe 1
          invitations.head.status shouldBe Pending

          // ETMP relationship created
          HipStub.verifyAgentCanBeAllocatedCalled(regimeData.clientId, TestData.arn)

          // EACD relationship created
          EnrolmentStoreProxyStubs.verifyEnrolmentAllocationAttempt(
            groupId = TestData.groupId,
            clientUserId = TestData.adminUser,
            enrolmentKey = regimeData.enrolment,
            agentCode = TestData.agentCode,
            count = 0
          )

          DataStreamStub.verifyAuditRequestNotSent(AgentClientRelationshipEvent.RespondToInvitation)
        }
      }
  }

  Seq(ItsaTestData, ItsaSuppTestData).foreach { regimeData =>
    val testEmail = EmailInformation(
      to = Seq("agent@email.com"),
      templateId = "client_accepted_authorisation_request",
      parameters = Map(
        "agencyName" -> "testAgentName",
        "clientName" -> "Erling Haal",
        "expiryDate" -> LocalDate.now().format(dateFormatter),
        "service" -> messagesApi(s"service.${regimeData.service.id}")(lang)
      )
    )
    s"PUT /agent-client-relationships/authorisation-response/accept/:invitationId" +
      s" for ITSA partial auth" should {
        s"return 201 when client accepts a partial auth ${regimeData.service} invitation and ${regimeData.supportingService} relationship does not exist" in {
          // Authorised as client
          regimeData.clientAuthStubs()

          // Lookup clientId fails
          regimeData.clientIdLookupFailureStubs()

          EnrolmentStoreProxyStubs.givenUpdateEnrolmentFriendlyNameResponse(
            TestData.groupId,
            regimeData.enrolment.tag,
            NO_CONTENT
          )
          AucdStubs.givenCacheRefresh(TestData.arn)
          EmailStubs.givenEmailSent(testEmail)

          val pendingInvitation =
            invitationRepo.create(
              arn = TestData.arn.value,
              service = Service.forId(regimeData.service.id),
              suppliedClientId = regimeData.suppliedClientId,
              clientName = "Erling Haal",
              agencyName = "testAgentName",
              agencyEmail = "agent@email.com",
              expiryDate = LocalDate.now(),
              clientType = Some("personal")
            ).futureValue

          val result = doAgentPutRequest(getRequestPath(pendingInvitation.invitationId))
          result.status shouldBe 204

          val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(TestData.arn.value).futureValue
          invitations.size shouldBe 1
          invitations.head.status shouldBe PartialAuth

          val partialAuthInvitations: Option[PartialAuthRelationship] = partialAuthRepository.findActiveForAgent(TestData.nino, TestData.arn).futureValue
          partialAuthInvitations.size shouldBe 1
          partialAuthInvitations.head.active shouldBe true

          // ETMP relationship not created
          HipStub.verifyAgentCanBeAllocatedCalled(
            regimeData.clientId,
            TestData.arn,
            count = 0
          )

          // EACD relationship not created
          EnrolmentStoreProxyStubs.verifyEnrolmentAllocationAttempt(
            groupId = TestData.groupId,
            clientUserId = TestData.adminUser,
            enrolmentKey = regimeData.enrolment,
            agentCode = TestData.agentCode,
            count = 0
          )

          EmailStubs.verifyInvitationEmailInfoSent(testEmail)
          DataStreamStub.verifyRespondToInvitationAuditSent(
            getRequestPath(pendingInvitation.invitationId),
            invitations.head,
            accepted = true,
            isStride = false,
            Some(regimeData.partialAuthEnrolment)
          )
        }

        s"return 201 when client accepts a partial auth ${regimeData.service} invitation and ${regimeData.supportingService} relationship exists" in {
          // Authorised as client
          regimeData.clientAuthStubs()

          // Lookup clientId fails
          regimeData.clientIdLookupFailureStubs()

          EnrolmentStoreProxyStubs.givenUpdateEnrolmentFriendlyNameResponse(
            TestData.groupId,
            regimeData.enrolment.tag,
            NO_CONTENT
          )
          AucdStubs.givenCacheRefresh(TestData.arn)
          EmailStubs.givenEmailSent(testEmail)

          // insert existing PartialAuth invitation
          invitationRepo.create(
            arn = TestData.arn.value,
            service = Service.forId(regimeData.supportingService.get.id),
            suppliedClientId = regimeData.suppliedClientId,
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
              regimeData.supportingService.get.id,
              nino
            ).futureValue

          val pendingInvitation =
            invitationRepo.create(
              arn = TestData.arn.value,
              service = Service.forId(regimeData.service.id),
              suppliedClientId = regimeData.suppliedClientId,
              clientName = "Erling Haal",
              agencyName = "testAgentName",
              agencyEmail = "agent@email.com",
              expiryDate = LocalDate.now(),
              clientType = Some("personal")
            ).futureValue

          val result = doAgentPutRequest(getRequestPath(pendingInvitation.invitationId))
          result.status shouldBe 204

          val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(TestData.arn.value).futureValue
          invitations.size shouldBe 2
          invitations.exists(_.status == PartialAuth) shouldBe true
          invitations.exists(_.status == DeAuthorised) shouldBe true

          val partialAuthInvitationsAccepted: Option[PartialAuthRelationship] =
            partialAuthRepository.findActive(
              regimeData.service.id,
              TestData.nino,
              TestData.arn
            ).futureValue
          partialAuthInvitationsAccepted.size shouldBe 1
          partialAuthInvitationsAccepted.head.active shouldBe true

          val partialAuthInvitationsDeAuth: Option[PartialAuthRelationship] =
            partialAuthRepository.findActive(
              regimeData.supportingService.get.id,
              TestData.nino,
              TestData.arn
            ).futureValue
          partialAuthInvitationsDeAuth.size shouldBe 0

          // ETMP relationship not created
          HipStub.verifyAgentCanBeAllocatedCalled(
            regimeData.clientId,
            TestData.arn,
            count = 0
          )

          // EACD relationship not created
          EnrolmentStoreProxyStubs.verifyEnrolmentAllocationAttempt(
            groupId = TestData.groupId,
            clientUserId = TestData.adminUser,
            enrolmentKey = regimeData.enrolment,
            agentCode = TestData.agentCode,
            count = 0
          )

          EmailStubs.verifyInvitationEmailInfoSent(testEmail)
          DataStreamStub.verifyRespondToInvitationAuditSent(
            getRequestPath(pendingInvitation.invitationId),
            invitations.find(_.status == PartialAuth).get,
            accepted = true,
            isStride = false,
            Some(regimeData.partialAuthEnrolment)
          )
        }
      }
  }

  s"PUT /agent-client-relationships/authorisation-response/accept/:invitationId for IRV" should {
    val testEmail = EmailInformation(
      to = Seq("agent@email.com"),
      templateId = "client_accepted_authorisation_request",
      parameters = Map(
        "agencyName" -> "testAgentName",
        "clientName" -> "Erling Haal",
        "expiryDate" -> LocalDate.now().format(dateFormatter),
        "service" -> messagesApi(s"service.${IrvTestData.service.id}")(lang)
      )
    )
    "return 201 when client accepts invitation successfully" in {
      // Authorised as client
      IrvTestData.clientAuthStubs()

      // Create IRV relationship
      AfiRelationshipStub.givenCreateAfiRelationshipSucceeds(
        arn = TestData.arn,
        service = IrvTestData.service.id,
        clientId = IrvTestData.clientId.value
      )

      EnrolmentStoreProxyStubs.givenUpdateEnrolmentFriendlyNameResponse(
        TestData.groupId,
        IrvTestData.enrolment.tag,
        NO_CONTENT
      )
      AucdStubs.givenCacheRefresh(TestData.arn)
      EmailStubs.givenEmailSent(testEmail)

      val pendingInvitation =
        invitationRepo.create(
          arn = TestData.arn.value,
          service = Service.forId(IrvTestData.service.id),
          suppliedClientId = IrvTestData.suppliedClientId,
          clientName = "Erling Haal",
          agencyName = "testAgentName",
          agencyEmail = "agent@email.com",
          expiryDate = LocalDate.now(),
          clientType = Some("personal")
        ).futureValue

      val result = doAgentPutRequest(getRequestPath(pendingInvitation.invitationId))
      result.status shouldBe 204

      val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(TestData.arn.value).futureValue
      invitations.size shouldBe 1
      invitations.head.status shouldBe Accepted

      EmailStubs.verifyInvitationEmailInfoSent(testEmail)
      DataStreamStub.verifyRespondToInvitationAuditSent(
        getRequestPath(pendingInvitation.invitationId),
        invitations.head,
        accepted = true,
        isStride = false,
        Some(IrvTestData.enrolment)
      )
    }

    "return 500 when AFI responds with an error" in {
      // Authorised as client
      IrvTestData.clientAuthStubs()

      // Create IRV relationship
      AfiRelationshipStub.givenCreateAfiRelationshipFails(
        arn = TestData.arn,
        service = IrvTestData.service.id,
        clientId = IrvTestData.clientId.value
      )

      val pendingInvitation =
        invitationRepo.create(
          arn = TestData.arn.value,
          service = Service.forId(IrvTestData.service.id),
          suppliedClientId = IrvTestData.suppliedClientId,
          clientName = "Erling Haal",
          agencyName = "testAgentName",
          agencyEmail = "agent@email.com",
          expiryDate = LocalDate.now(),
          clientType = Some("personal")
        ).futureValue

      val result = doAgentPutRequest(getRequestPath(pendingInvitation.invitationId))
      result.status shouldBe 500

      val invitations: Seq[Invitation] = invitationRepo.findAllForAgent(TestData.arn.value).futureValue
      invitations.size shouldBe 1
      invitations.head.status shouldBe Pending

      DataStreamStub.verifyAuditRequestNotSent(AgentClientRelationshipEvent.RespondToInvitation)
    }
  }

}
