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

import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.DeleteRecord
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentmtdidentifiers.model.CbcId
import uk.gov.hmrc.agentmtdidentifiers.model.Identifier
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.mongo.lock.Lock

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// TODO. All of the following tests should be rewritten directly against a RelationshipsController instance (with appropriate mocks/stubs)
// rather than instantiating a whole app and sending a real HTTP request. It makes test setup and debug very difficult.
// We should also consider only testing logic that is not already tested as part of testing dependent services/connectors elsewhere.
// For this reason, only legacy non-Granular Permissions logic is tested here.
// The new Granular Permissions behaviours are tested directly in CheckRelationshipServiceSpec.

trait RelationshipsControllerGenericBehaviours {
  this: RelationshipsBaseControllerISpec
    with HipStub =>

  def relationshipsControllerISpec(
    serviceId: String,
    clientId: TaxIdentifier,
    clientIdType: String
  ): Unit = {
    relationshipsControllerGetISpec(
      serviceId,
      clientId,
      clientIdType
    )
    relationshipsControllerPutISpec(
      serviceId,
      clientId,
      clientIdType
    )
    relationshipsControllerDeleteISpec(
      serviceId,
      clientId,
      clientIdType
    )
    strideEndpointISpec(
      serviceId,
      clientId,
      clientIdType
    )
  }

  def now = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime

  def isItsaNino(
    clientIdType: String,
    serviceId: String
  ): Boolean =
    clientIdType.toUpperCase == "NI" &&
      (serviceId == Service.MtdIt.id || serviceId == Service.MtdItSupp.id)

  // noinspection ScalaStyle
  def relationshipsControllerGetISpec(
    serviceId: String,
    clientId: TaxIdentifier,
    clientIdType: String
  ): Unit = {
    val enrolmentKey =
      if (serviceId == Service.Cbc.id) {
        EnrolmentKey(s"${Service.Cbc.id}~$clientIdType~${clientId.value}~UTR~1234567890")
      }
      else if (isItsaNino(clientIdType, serviceId)) {
        EnrolmentKey(Service.forId(serviceId), mtdItId)
      }
      else {
        EnrolmentKey(Service.forId(serviceId), clientId)
      }
    def extraSetup(
      serviceId: String,
      clientIdType: String
    ): Unit = {
      if (serviceId == Service.Cbc.id)
        givenCbcUkExistsInES(CbcId(clientId.value), enrolmentKey.oneIdentifier(Some("UTR")).value)
      if (isItsaNino(clientIdType, serviceId)) {
        givenMtdItIdIsKnownFor(Nino(clientId.value), mtdItId)
        getActiveRelationshipsViaClient(mtdItId, arn)
      }
      ()
    }

    s"GET  /agent/:arn/service/$serviceId/client/$clientIdType/:clientId" should {

      val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/service/$serviceId/client/$clientIdType/${clientId.value}"

      def doRequest = doGetRequest(requestPath)

      // HAPPY PATH :-)

      "return 200 when relationship exists in es" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo")
        extraSetup(serviceId, clientIdType)
        await(repo.findBy(arn, enrolmentKey)) shouldBe None
        val result = doRequest
        result.status shouldBe 200
        await(repo.findBy(arn, enrolmentKey)) shouldBe empty
      }

      // UNHAPPY PATHS

      "return 404 when credentials are not found in es" in {
        givenPrincipalGroupIdNotExistsFor(agentEnrolmentKey(arn))
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
        givenDelegatedGroupIdsNotExistFor(EnrolmentKey(s"$serviceId~$clientIdType~${clientId.value}"))
        givenPrincipalGroupIdNotExistsFor(agentEnrolmentKey(arn))
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo")
        extraSetup(serviceId, clientIdType)

        val result = doRequest
        result.status shouldBe 404
      }

      "return 404 when delete is pending" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
        givenAdminUser("foo", "any")
        givenEnrolmentDeallocationFailsWith(404)("foo", enrolmentKey)
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo")
        extraSetup(serviceId, clientIdType)

        await(
          deleteRecordRepository.create(
            DeleteRecord(
              arn.value,
              Some(enrolmentKey),
              syncToETMPStatus = Some(SyncStatus.Success),
              syncToESStatus = Some(SyncStatus.Failed)
            )
          )
        )

        val result = doRequest
        result.status shouldBe 404
        (result.json \ "code").as[String] shouldBe "RELATIONSHIP_DELETE_PENDING"

        await(deleteRecordRepository.remove(arn, enrolmentKey))
      }

      // FAILURE CASES

      "return 502 when ES1/delegated returns 5xx" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo")
        givenDelegatedGroupIdRequestFailsWith(500)
        extraSetup(serviceId, clientIdType)

        val result = doRequest
        result.status shouldBe 500
      }

      "return 400 when ES1/delegated returns 4xx" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdRequestFailsWith(400)
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo")
        extraSetup(serviceId, clientIdType)

        val result = doRequest
        result.status shouldBe 400
      }
    }
  }

  // noinspection ScalaStyle
  def relationshipsControllerPutISpec(
    serviceId: String,
    clientId: TaxIdentifier,
    clientIdType: String
  ): Unit = {
    val enrolmentKey =
      if (serviceId == Service.Cbc.id) {
        EnrolmentKey(s"${Service.Cbc.id}~$clientIdType~${clientId.value}~UTR~1234567890")
      }
      else
        EnrolmentKey(Service.forId(serviceId), clientId)
    def extraSetup(
      serviceId: String,
      clientIdType: String
    ): Unit = {
      if (serviceId == Service.Cbc.id)
        givenCbcUkExistsInES(CbcId(clientId.value), enrolmentKey.oneIdentifier(Some("UTR")).value)
      if (isItsaNino(clientIdType, serviceId)) {
        givenMtdItIdIsKnownFor(Nino(clientId.value), mtdItId)
        getActiveRelationshipsViaClient(mtdItId, arn)
      }
      ()
    }

    s"PUT /agent/:arn/service/$serviceId/client/$clientIdType/:clientId" should {

      val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/service/$serviceId/client/$clientIdType/${clientId.value}"

      trait StubsForThisScenario {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenEnrolmentExistsForGroupId("bar", agentEnrolmentKey(Arn("barArn")))
        givenEnrolmentExistsForGroupId("foo", agentEnrolmentKey(Arn("fooArn")))
        givenDelegatedGroupIdsExistForEnrolmentKey(enrolmentKey)
        givenAgentCanBeAllocated(clientId, arn)
        givenEnrolmentDeallocationSucceeds("foo", enrolmentKey)
        givenEnrolmentDeallocationSucceeds("bar", enrolmentKey)
        givenServiceEnrolmentAllocationSucceeds(enrolmentKey, "bar")
        givenAdminUser("foo", "any")
        givenCacheRefresh(arn)
        extraSetup(serviceId, clientIdType)
      }

      "return 201 when the relationship exists and de-allocation of previous relationship fails" in new StubsForThisScenario {
        givenUserIsSubscribedClient(clientId)
        givenDelegatedGroupIdsExistForEnrolmentKey(enrolmentKey, "zoo")
        givenEnrolmentExistsForGroupId("zoo", agentEnrolmentKey(Arn("zooArn")))
        givenEnrolmentDeallocationFailsWith(502)("zoo", enrolmentKey)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 201
        if (enrolmentKey.service == Service.HMRCMTDITSUPP)
          verifyNoEnrolmentHasBeenDeallocated()
        else
          verifyEnrolmentDeallocationAttempt(groupId = "zoo", enrolmentKey = enrolmentKey)

        verifyCreateRelationshipAuditSent(
          requestPath,
          arn.value,
          clientId.value,
          clientIdType,
          serviceId,
          "ClientAcceptedInvitation"
        )
      }

      "return 201 when the relationship exists and the clientId matches that of current Client user" in new StubsForThisScenario {
        givenUserIsSubscribedClient(clientId)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 201

        verifyCreateRelationshipAuditSent(
          requestPath,
          arn.value,
          clientId.value,
          clientIdType,
          serviceId,
          "ClientAcceptedInvitation"
        )

        if (enrolmentKey.service == Service.HMRCMTDITSUPP) {
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
        else {
          verifyTerminateRelationshipAuditSent(
            requestPath,
            "barArn",
            clientId.value,
            clientIdType,
            enrolmentKey.service,
            "AgentReplacement",
            credId = None,
            agentCode = None
          )
          verifyTerminateRelationshipAuditSent(
            requestPath,
            "fooArn",
            clientId.value,
            clientIdType,
            enrolmentKey.service,
            "AgentReplacement",
            credId = None,
            agentCode = None
          )
        }
      }

      "return 201 when the relationship exists and the user is authenticated with Stride" in new StubsForThisScenario {
        givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-983283")

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 201

        verifyCreateRelationshipAuditSent(
          requestPath,
          arn.value,
          clientId.value,
          clientIdType,
          serviceId,
          "HMRCAcceptedInvitation"
        )
      }

      "return 201 when an agent tries to create a relationship" in {
        givenUserIsSubscribedAgent(arn)
        givenAgentCanBeAllocated(clientId, arn)
        givenAgentGroupExistsFor("foo")
        givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
        givenAdminUser("foo", "any")
        givenDelegatedGroupIdsNotExistFor(enrolmentKey) // no previous relationships to deallocate
        givenEnrolmentAllocationSucceeds(
          "foo",
          "any",
          enrolmentKey,
          "NQJUEJCWT14"
        )
        givenCacheRefresh(arn)
        extraSetup(serviceId, clientIdType)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 201

        verifyCreateRelationshipAuditSent(
          requestPath,
          arn.value,
          clientId.value,
          clientIdType,
          serviceId,
          "ClientAcceptedInvitation",
          agentCode = Some("NQJUEJCWT14")
        )
      }

      "return 423 Locked if there is a record in the lock repository" in {
        givenUserIsSubscribedClient(clientId)
        extraSetup(serviceId, clientIdType)

        await(
          mongoLockRepository.collection
            .insertOne(
              Lock(
                id = s"recovery-${arn.value}-${enrolmentKey.tag}",
                owner = "86515a24-1a37-4a40-9117-4a117d8dd42e",
                expiryTime = Instant.now().plusSeconds(2),
                timeCreated = Instant.now().minusMillis(500)
              )
            )
            .toFuture()
        )

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe LOCKED

        verifyAuditRequestNotSent(AgentClientRelationshipEvent.CreateRelationship)
      }

      "return 201 when the relationship exists and previous relationships too but ARNs not found" in new StubsForThisScenario {
        givenUserIsSubscribedClient(clientId)
        givenEnrolmentNotExistsForGroupId("zoo")
        givenEnrolmentDeallocationSucceeds("zoo", enrolmentKey)
        extraSetup(serviceId, clientIdType)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 201

        verifyCreateRelationshipAuditSent(
          requestPath,
          arn.value,
          clientId.value,
          clientIdType,
          serviceId,
          "ClientAcceptedInvitation"
        )
      }

      "return 201 when there are no previous relationships to deallocate" in {
        givenUserIsSubscribedClient(clientId)
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistFor(enrolmentKey)
        givenAgentCanBeAllocated(clientId, arn)
        givenServiceEnrolmentAllocationSucceeds(enrolmentKey, "bar")
        givenAdminUser("foo", "any")
        givenCacheRefresh(arn)
        extraSetup(serviceId, clientIdType)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 201

        verifyCreateRelationshipAuditSent(
          requestPath,
          arn.value,
          clientId.value,
          clientIdType,
          serviceId,
          "ClientAcceptedInvitation"
        )
      }

      "return 500 when ES1 is unavailable" in new StubsForThisScenario {
        givenUserIsSubscribedClient(clientId)
        givenPrincipalAgentUser(
          arn,
          "foo",
          userId = "user1"
        )
        givenGroupInfo(groupId = "foo", agentCode = "bar")
        givenDelegatedGroupIdRequestFailsWith(503)
        extraSetup(serviceId, clientIdType)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 500

        verifyAuditRequestNotSent(AgentClientRelationshipEvent.CreateRelationship)
      }

      "return 500 when ES8 is unavailable" in {
        givenUserIsSubscribedClient(clientId)
        givenPrincipalAgentUser(
          arn,
          "foo",
          userId = "user1"
        )
        givenGroupInfo(groupId = "foo", agentCode = "bar")
        givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKey)
        givenAgentCanBeAllocated(clientId, arn)
        givenEnrolmentAllocationFailsWith(503)(
          groupId = "foo",
          clientUserId = "user1",
          enrolmentKey,
          agentCode = "bar"
        )
        givenAdminUser("foo", "user1")
        extraSetup(serviceId, clientIdType)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 500
        (result.json \ "message").asOpt[String] shouldBe None

        verifyAuditRequestNotSent(AgentClientRelationshipEvent.CreateRelationship)
      }

      "return 500 when DES/IF is unavailable" in {
        givenUserIsSubscribedClient(clientId)
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKey)
        givenDesReturnsServiceUnavailable()
        givenReturnsServiceUnavailable()
        givenAdminUser("foo", "any")
        extraSetup(serviceId, clientIdType)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 500
        (result.json \ "message").asOpt[String] shouldBe None

        verifyAuditRequestNotSent(AgentClientRelationshipEvent.CreateRelationship)
      }

      "return 500 if DES/IF returns 404" in {
        givenUserIsSubscribedClient(clientId)
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKey)
        givenAgentCanNotBeAllocated(status = 404)
        givenAdminUser("foo", "any")
        extraSetup(serviceId, clientIdType)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 500
        (result.json \ "code").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_IF")

        verifyAuditRequestNotSent(AgentClientRelationshipEvent.CreateRelationship)
      }

      "return 403 for a client with a mismatched clientId" in {
        val dummyClientId: TaxIdentifier = Service.forId(serviceId).supportedClientIdType.createUnderlying("unmatched")
        givenUserIsSubscribedClient(dummyClientId)
        extraSetup(serviceId, clientIdType)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 403

        verifyAuditRequestNotSent(AgentClientRelationshipEvent.CreateRelationship)
      }

      "return 403 for a client with no client enrolments" in {
        givenUserHasNoClientEnrolments
        extraSetup(serviceId, clientIdType)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 403

        verifyAuditRequestNotSent(AgentClientRelationshipEvent.CreateRelationship)
      }
    }
  }

  // noinspection ScalaStyle
  def relationshipsControllerDeleteISpec(
    serviceId: String,
    clientId: TaxIdentifier,
    clientIdType: String
  ): Unit = {
    val enrolmentKey =
      if (serviceId == Service.Cbc.id) {
        EnrolmentKey(s"${Service.Cbc.id}~$clientIdType~${clientId.value}~UTR~1234567890")
      }
      else
        EnrolmentKey(Service.forId(serviceId), clientId)
    def extraSetup(serviceId: String): Unit = {
      if (serviceId == Service.Cbc.id)
        givenCbcUkExistsInES(CbcId(clientId.value), enrolmentKey.oneIdentifier(Some("UTR")).value)
      ()
    }

    s"DELETE /agent/:arn/service/$serviceId/client/$clientIdType/:clientId" when {

      val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/service/$serviceId/client/$clientIdType/${clientId.value}"

      "the relationship exists and the clientId matches that of current Agent user" should {

        trait StubsForThisScenario {
          givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenPrincipalGroupIdExistsFor(enrolmentKey, "foo")
          givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
          givenAgentCanBeDeallocated(clientId, arn)
          givenEnrolmentDeallocationSucceeds("foo", enrolmentKey)
          givenAdminUser("foo", "any")
          givenCacheRefresh(arn)
          extraSetup(serviceId)
        }

        "return 204 and send an audit event called TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 204
          verifyTerminateRelationshipAuditSent(
            requestPath,
            arn.value,
            clientId.value,
            clientIdType,
            serviceId,
            "AgentLedTermination"
          )
        }
      }

      "the relationship exists and the clientId matches that of current Client user" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedClient(clientId, withThisGgUserId = "ggUserId-client")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
          givenAgentCanBeDeallocated(clientId, arn)
          givenEnrolmentDeallocationSucceeds("foo", enrolmentKey)
          givenAdminUser("foo", "any")
          givenCacheRefresh(arn)
          extraSetup(serviceId)
        }

        "return 204 and send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 204
          verifyTerminateRelationshipAuditSent(
            requestPath,
            arn.value,
            clientId.value,
            clientIdType,
            serviceId,
            "ClientLedTermination"
          )
        }
      }

      "the relationship exists and the user is authenticated with Stride" should {
        trait StubsForThisScenario {
          givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-1234456")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
          givenAgentCanBeDeallocated(clientId, arn)
          givenEnrolmentDeallocationSucceeds("foo", enrolmentKey)
          givenAdminUser("foo", "any")
          extraSetup(serviceId)
        }

        "return 204 and send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 204
          verifyTerminateRelationshipAuditSent(
            requestPath,
            arn.value,
            clientId.value,
            clientIdType,
            serviceId,
            "HMRCLedTermination"
          )
        }
      }

      "the relationship exists in ETMP and not exist in ES" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedClient(clientId, withThisGgUserId = "ggUserId-client")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenPrincipalGroupIdExistsFor(enrolmentKey, "clientGroupId")
          givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKey)
          givenAgentCanBeDeallocated(clientId, arn)
          givenAdminUser("foo", "any")
          extraSetup(serviceId)
        }

        "return 500 and send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 500
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }

      "the relationship does not exist in either ETMP or in ES" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedClient(clientId, withThisGgUserId = "ggUserId-client")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenPrincipalGroupIdExistsFor(enrolmentKey, "clientGroupId")
          givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKey)
          givenAgentHasNoActiveRelationship(clientId, arn)
          givenAdminUser("foo", "any")
          extraSetup(serviceId)
        }

        "return 500 and not send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 500
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }

      "the relationship does not exist in ETMP but does exist in ES" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedClient(clientId, withThisGgUserId = "ggUserId-client")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
          givenAgentHasNoActiveRelationship(clientId, arn)
          givenEnrolmentDeallocationSucceeds("foo", enrolmentKey)
          givenAdminUser("foo", "any")
          givenCacheRefresh(arn)
          extraSetup(serviceId)
        }

        "return 204 and send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 204
          verifyTerminateRelationshipAuditSent(
            requestPath,
            arn.value,
            clientId.value,
            clientIdType,
            serviceId,
            "ClientLedTermination"
          )
        }
      }

      /** Agent's Unhappy paths
        */
      "agent has a mismatched arn" should {
        "return 403 and not send the audit event TerminateRelationship" in {
          givenUserIsSubscribedAgent(Arn("unmatched"))
          extraSetup(serviceId)
          doAgentDeleteRequest(requestPath).status shouldBe 403
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }

      "agent has no agent enrolments" should {
        "return 403 and not send the audit event TerminateRelationship" in {
          givenUserHasNoAgentEnrolments(arn)
          extraSetup(serviceId)

          doAgentDeleteRequest(requestPath).status shouldBe 403
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }

      "es is unavailable" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedAgent(arn)
          givenEsIsUnavailable()
          givenAgentCanBeDeallocated(clientId, arn)
          extraSetup(serviceId)
        }

        "return 500 and not send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 500
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }

      "DES/IF is unavailable" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedAgent(arn)
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenPrincipalGroupIdExistsFor(enrolmentKey, "clientGroupId")
          givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
          givenReturnsServiceUnavailable()
          extraSetup(serviceId)
        }

        "return 500 and not send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 500
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }

      "DES/IF responds with 404" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedAgent(arn)
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenPrincipalGroupIdExistsFor(enrolmentKey, "clientGroupId")
          givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
          givenAgentCanNotBeDeallocated(status = 404)
          extraSetup(serviceId)
        }

        "return 500 and not send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 500
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }

      /** Client's Unhappy paths
        */

      "client has a mismatched clientId" should {
        val dummyClientId: TaxIdentifier = Service.forId(serviceId).supportedClientIdType.createUnderlying("unmatched")

        "return 403 and not send the audit event TerminateRelationship" in {
          givenUserIsSubscribedClient(dummyClientId)
          extraSetup(serviceId)

          doAgentDeleteRequest(requestPath).status shouldBe 403
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }

      "client has no client enrolments" should {
        "return 403 and not send the audit event TerminateRelationship" in {
          givenUserHasNoClientEnrolments
          extraSetup(serviceId)

          doAgentDeleteRequest(requestPath).status shouldBe 403
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }

      "client has no groupId" should {
        trait StubsForScenario {
          givenUserIsSubscribedClient(clientId)
          givenAgentCanBeDeallocated(clientId, arn)
          givenAuditConnector()
          extraSetup(serviceId)
          // givenPrincipalGroupIdNotExistsFor(clientId)
        }

        "return 500 and not send the audit event TerminateRelationship" in new StubsForScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 500
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }
    }

    s"DELETE /test-only/db/agent/:arn/service/$serviceId/client/$clientIdType/:clientId" should {

      val requestPath: String = s"/test-only/db/agent/${arn.value}/service/$serviceId/client/$clientIdType/$clientId"

      "return 404 for any call" in {

        await(
          repo.create(
            RelationshipCopyRecord(
              arn.value,
              Some(EnrolmentKey(serviceId, Seq(Identifier(clientIdType, clientId.value))))
            )
          )
        ) shouldBe 1
        val result = doAgentDeleteRequest(requestPath)
        result.status shouldBe 404
      }
    }

  }

  // noinspection ScalaStyle
  def strideEndpointISpec(
    serviceId: String,
    clientId: TaxIdentifier,
    clientIdType: String
  ) =
    s"GET /relationships/service/$serviceId/client/$clientIdType/:clientId" should {

      val requestPath: String = s"/agent-client-relationships/relationships/service/$serviceId/client/$clientIdType/${clientId.value}"

      def doRequest = doGetRequest(requestPath)
      val req = FakeRequest()

      val enrolmentKey =
        if (serviceId == Service.Cbc.id) {
          EnrolmentKey(s"${Service.Cbc.id}~UTR~1234567890~$clientIdType~${clientId.value}")
        }
        else
          EnrolmentKey(Service.forId(serviceId), clientId)
      def extraSetup(serviceId: String): Unit = {
        if (serviceId == Service.Cbc.id)
          givenCbcUkExistsInES(CbcId(clientId.value), enrolmentKey.oneIdentifier(Some("UTR")).value)
        ()
      }

      "find relationship and send back Json" in {
        givenAuthorisedAsStrideUser(req, "someStrideId")
        extraSetup(serviceId)

        if (clientIdType == "NI") {
          givenMtdItIdIsKnownFor(Nino(clientId.value), mtdItId)
          getActiveRelationshipsViaClient(mtdItId, arn)
        }
        else
          getActiveRelationshipsViaClient(clientId, arn)

        val result = doRequest
        result.status shouldBe 200

        (result.json \ "arn").get.as[String] shouldBe arn.value
        (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
      }

      "find relationship but filter out if the end date has been changed from 9999-12-31" in {
        givenAuthorisedAsStrideUser(req, "someStrideId")
        extraSetup(serviceId)

        if (clientIdType == "NI") {
          givenMtdItIdIsKnownFor(Nino(clientId.value), mtdItId)
          getInactiveRelationshipViaClient(mtdItId, arn.value)
        }
        else
          getInactiveRelationshipViaClient(clientId, arn.value)

        val result = doRequest
        result.status shouldBe 404
      }

      "find multiple relationships but filter out active and ended relationships" in {
        givenAuthorisedAsStrideUser(req, "someStrideId")
        extraSetup(serviceId)

        if (clientIdType == "NI") {
          givenMtdItIdIsKnownFor(Nino(clientId.value), mtdItId)
          getSomeActiveRelationshipsViaClient(
            mtdItId,
            arn.value,
            arn2.value,
            arn3.value
          )
        }
        else
          getSomeActiveRelationshipsViaClient(
            clientId,
            arn.value,
            arn2.value,
            arn3.value
          )

        val result = doRequest
        result.status shouldBe 200
        (result.json \ "arn").get.as[String] shouldBe arn3.value
        (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
      }

      "return 404 when DES returns 404 relationship not found" in {
        givenAuthorisedAsStrideUser(req, "someStrideId")
        extraSetup(serviceId)

        if (clientIdType == "NI") {
          givenMtdItIdIsKnownFor(Nino(clientId.value), mtdItId)
          getActiveRelationshipFailsWith(mtdItId, status = 404)
        }
        else
          getActiveRelationshipFailsWith(clientId, status = 404)

        val result = doRequest
        result.status shouldBe 404
      }

      "return 404 when DES returns 400 (treated as relationship not found)" in {
        givenAuthorisedAsStrideUser(req, "someStrideId")
        extraSetup(serviceId)

        if (clientIdType == "NI") {
          givenMtdItIdIsKnownFor(Nino(clientId.value), mtdItId)
          getActiveRelationshipFailsWith(mtdItId, status = 400)
        }
        else
          getActiveRelationshipFailsWith(clientId, status = 400)

        val result = doRequest
        result.status shouldBe 404
      }
    }

}
