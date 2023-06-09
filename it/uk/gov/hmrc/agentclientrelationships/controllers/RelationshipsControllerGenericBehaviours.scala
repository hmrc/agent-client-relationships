package uk.gov.hmrc.agentclientrelationships.controllers

import org.mongodb.scala.model.Filters
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.{DeleteRecord, RelationshipCopyRecord, SyncStatus}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Service}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.mongo.lock.Lock

import java.time.{Instant, LocalDate, ZoneOffset}

// TODO. All of the following tests should be rewritten directly against a RelationshipsController instance (with appropriate mocks/stubs)
// rather than instantiating a whole app and sending a real HTTP request. It makes test setup and debug very difficult.
// We should also consider only testing logic that is not already tested as part of testing dependent services/connectors elsewhere.
// For this reason, only legacy non-Granular Permissions logic is tested here.
// The new Granular Permissions behaviours are tested directly in CheckRelationshipServiceSpec.

trait RelationshipsControllerGenericBehaviours { this: RelationshipsBaseControllerISpec =>

  def relationshipsControllerISpec(serviceId: String, clientId: TaxIdentifier, clientIdType: String): Unit = {
    relationshipsControllerGetISpec(serviceId, clientId, clientIdType)
    relationshipsControllerPutISpec(serviceId, clientId, clientIdType)
    relationshipsControllerDeleteISpec(serviceId, clientId, clientIdType)
    strideEndpointISpec(serviceId, clientId, clientIdType)
  }

  def now = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime

  def relationshipsControllerGetISpec(serviceId: String, clientId: TaxIdentifier, clientIdType: String): Unit = {
    val enrolmentKey = EnrolmentKey(Service.forId(serviceId), clientId)
    s"GET  /agent/:arn/service/$serviceId/client/$clientIdType/:clientId" should {

      val requestPath: String =
        s"/agent-client-relationships/agent/${arn.value}/service/$serviceId/client/$clientIdType/${clientId.value}"

      def doRequest = doAgentGetRequest(requestPath)

      //HAPPY PATH :-)

      "return 200 when relationship exists in es" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo")

        def query() =
          repo.collection.find(Filters.and(
            Filters.equal("arn", arn.value),
            Filters.equal( "clientIdentifier",clientId.value),
            Filters.equal("clientIdentifierType", clientIdType))).toFuture()

        await(query()) shouldBe empty
        val result = doRequest
        result.status shouldBe 200
        await(query()) shouldBe empty
      }

      //UNHAPPY PATHS

      "return 404 when credentials are not found in es" in {
        givenPrincipalGroupIdNotExistsFor(agentEnrolmentKey(arn))
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
        givenDelegatedGroupIdsNotExistFor(EnrolmentKey(s"$serviceId~$clientIdType~${clientId.value}"))
        givenPrincipalGroupIdNotExistsFor(agentEnrolmentKey(arn))
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo")

        val result = doRequest
        result.status shouldBe 404
      }

      "return 404 when delete is pending" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(mtdItEnrolmentKey, "bar")
        givenAdminUser("foo", "any")
        givenEnrolmentDeallocationFailsWith(404)("foo", mtdItEnrolmentKey)
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo")

        await(
          deleteRecordRepository.create(
            DeleteRecord(
              arn.value,
              Some(Service.MtdIt.id),
              clientId.value,
              clientIdType,
              now,
              Some(SyncStatus.Success),
              Some(SyncStatus.Failed))))

        val result = doRequest
        result.status shouldBe 404
        (result.json \ "code").as[String] shouldBe "RELATIONSHIP_DELETE_PENDING"

        await(deleteRecordRepository.remove(arn, clientId))
      }

      //FAILURE CASES

      "return 502 when ES1/delegated returns 5xx" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo")

        givenDelegatedGroupIdRequestFailsWith(500)
        val result = doRequest
        result.status shouldBe 500
      }

      "return 400 when ES1/delegated returns 4xx" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdRequestFailsWith(400)
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo")

        val result = doRequest
        result.status shouldBe 400
      }
    }
  }

  def relationshipsControllerPutISpec(serviceId: String, clientId: TaxIdentifier, clientIdType: String): Unit = {
    val enrolmentKey = EnrolmentKey(Service.forId(serviceId), clientId)
    s"PUT /agent/:arn/service/$serviceId/client/$clientIdType/:clientId" should {

      val requestPath: String =
        s"/agent-client-relationships/agent/${arn.value}/service/$serviceId/client/$clientIdType/${clientId.value}"

      trait StubsForThisScenario {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenEnrolmentExistsForGroupId("bar", agentEnrolmentKey(Arn("barArn")))
        givenEnrolmentExistsForGroupId("foo", agentEnrolmentKey(Arn("fooArn")))
        givenDelegatedGroupIdsExistForEnrolmentKey(enrolmentKey)
        givenAgentCanBeAllocatedInIF(clientId, arn)
        givenEnrolmentDeallocationSucceeds("foo", enrolmentKey)
        givenEnrolmentDeallocationSucceeds("bar", enrolmentKey)
        givenServiceEnrolmentAllocationSucceeds(enrolmentKey, "bar")
        givenAdminUser("foo", "any")
        givenCacheRefresh(arn)
      }

      "return 201 when the relationship exists and de-allocation of previous relationship fails" in new StubsForThisScenario {
        givenUserIsSubscribedClient(clientId)
        givenDelegatedGroupIdsExistForEnrolmentKey(enrolmentKey, "zoo")
        givenEnrolmentExistsForGroupId("zoo", agentEnrolmentKey(Arn("zooArn")))
        givenEnrolmentDeallocationFailsWith(502)("zoo", enrolmentKey)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 201
      }

      "return 201 when the relationship exists and the clientId matches that of current Client user" in new StubsForThisScenario {
        givenUserIsSubscribedClient(clientId)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 201
      }

      "return 201 when the relationship exists and the user is authenticated with Stride" in new StubsForThisScenario {
        givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-983283")

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 201
      }

      "return 201 when an agent tries to create a relationship" in {
        givenUserIsSubscribedAgent(arn)
        givenAgentCanBeAllocatedInIF(clientId, arn)
        givenAgentGroupExistsFor("foo")
        givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
        givenAdminUser("foo", "any")
        givenDelegatedGroupIdsNotExistFor(enrolmentKey) // no previous relationships to deallocate
        givenEnrolmentAllocationSucceeds("foo", "any", enrolmentKey, "NQJUEJCWT14")
        givenCacheRefresh(arn)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 201
      }

      "return 423 Locked if there is a record in the lock repository" in {
        givenUserIsSubscribedClient(clientId)

        await(mongoLockRepository.collection.insertOne(
          Lock(
            id = s"recovery-${arn.value}-${clientId.value}",
            owner = "86515a24-1a37-4a40-9117-4a117d8dd42e",
            expiryTime = Instant.now().plusSeconds(2),
            timeCreated = Instant.now().minusMillis(500))).toFuture())

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe LOCKED

      }

      "return 201 when the relationship exists and previous relationships too but ARNs not found" in new StubsForThisScenario {
        givenUserIsSubscribedClient(clientId)
        givenEnrolmentNotExistsForGroupId("zoo")
        givenEnrolmentDeallocationSucceeds("zoo", enrolmentKey)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 201
      }

      "return 201 when there are no previous relationships to deallocate" in {
        givenUserIsSubscribedClient(clientId)
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistFor(enrolmentKey)
        givenAgentCanBeAllocatedInIF(clientId, arn)
        givenServiceEnrolmentAllocationSucceeds(enrolmentKey, "bar")
        givenAdminUser("foo", "any")
        givenCacheRefresh(arn)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 201
      }

      "return 500 when ES1 is unavailable" in new StubsForThisScenario {
        givenUserIsSubscribedClient(clientId)
        givenPrincipalAgentUser(arn, "foo", userId = "user1")
        givenGroupInfo(groupId = "foo", agentCode = "bar")
        givenDelegatedGroupIdRequestFailsWith(503)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 500
      }

      "return 500 when ES8 is unavailable" in {
        givenUserIsSubscribedClient(clientId)
        givenPrincipalAgentUser(arn, "foo", userId = "user1")
        givenGroupInfo(groupId = "foo", agentCode = "bar")
        givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKey)
        givenAgentCanBeAllocatedInIF(clientId, arn)
        givenEnrolmentAllocationFailsWith(503)(
          groupId = "foo",
          clientUserId = "user1",
          enrolmentKey,
          agentCode = "bar")
        givenAdminUser("foo", "user1")

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 500
        (result.json \ "message").asOpt[String] shouldBe None
      }

      "return 500 when DES/IF is unavailable" in {
        givenUserIsSubscribedClient(clientId)
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKey)
        givenDesReturnsServiceUnavailable()
        givenIFReturnsServiceUnavailable()
        givenAdminUser("foo", "any")

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 500
        (result.json \ "message").asOpt[String] shouldBe None
      }

      "return 500 if DES/IF returns 404" in {
        givenUserIsSubscribedClient(clientId)
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKey)
        givenAgentCanNotBeAllocatedInIF(status = 404)
        givenAdminUser("foo", "any")

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 500
        (result.json \ "code").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_IF")
      }

      "return 403 for a client with a mismatched clientId" in {
        val dummyClientId: TaxIdentifier = Service.forId(serviceId).supportedClientIdType.createUnderlying("unmatched")
        givenUserIsSubscribedClient(dummyClientId)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 403
      }

      "return 403 for a client with no client enrolments" in {
        givenUserHasNoClientEnrolments

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 403
      }
    }
  }

  def relationshipsControllerDeleteISpec(serviceId: String, clientId: TaxIdentifier, clientIdType: String): Unit = {
    val enrolmentKey = EnrolmentKey(Service.forId(serviceId), clientId)
    s"DELETE /agent/:arn/service/$serviceId/client/$clientIdType/:clientId" when {

      val requestPath: String =
        s"/agent-client-relationships/agent/${arn.value}/service/$serviceId/client/$clientIdType/${clientId.value}"

      def verifyClientRemovedAgentServiceAuthorisationAuditSent(
                                                                 arn: String,
                                                                 clientId: String,
                                                                 service: String,
                                                                 authProviderId: String,
                                                                 authProviderIdType: String): Unit =
        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation,
          detail = Map(
            "agentReferenceNumber"     -> arn,
            "clientId"                 -> clientId,
            "service"                  -> service,
            "authProviderId"           -> authProviderId,
            "authProviderIdType"       -> authProviderIdType
          ),
          tags = Map("transactionName" -> "client terminated agent:service authorisation", "path" -> requestPath)
        )

      def verifyHmrcRemovedAgentServiceAuthorisation(
                                                      arn: String,
                                                      clientId: String,
                                                      service: String,
                                                      authProviderId: String,
                                                      authProviderIdType: String):Unit =
        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.HmrcRemovedAgentServiceAuthorisation,
          detail = Map(
            "authProviderId"       -> authProviderId,
            "authProviderIdType"   -> authProviderIdType,
            "agentReferenceNumber" -> arn,
            "clientId"             -> clientId,
            "service"              -> service
          ),
          tags = Map("transactionName" -> "hmrc remove agent:service authorisation", "path" -> requestPath)
        )

      "the relationship exists and the clientId matches that of current Agent user" should {

        trait StubsForThisScenario {
          givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenPrincipalGroupIdExistsFor(enrolmentKey,"foo")
          givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
          givenAgentCanBeDeallocatedInIF(clientId, arn)
          givenEnrolmentDeallocationSucceeds("foo", enrolmentKey)
          givenAdminUser("foo", "any")
          givenCacheRefresh(arn)
        }

        "return 204" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 204
        }

        "send an audit event called ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath)
          verifyClientRemovedAgentServiceAuthorisationAuditSent(
            arn.value,
            clientId.value,
            serviceId,
            "ggUserId-agent",
            "GovernmentGateway")
        }
      }

      "the relationship exists and the clientId matches that of current Client user" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedClient(clientId, withThisGgUserId = "ggUserId-client")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
          givenAgentCanBeDeallocatedInIF(clientId, arn)
          givenEnrolmentDeallocationSucceeds("foo", enrolmentKey)
          givenAdminUser("foo", "any")
          givenCacheRefresh(arn)
        }

        "return 204" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 204
        }

        "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath)
          verifyClientRemovedAgentServiceAuthorisationAuditSent(
            arn.value,
            clientId.value,
            serviceId,
            "ggUserId-client",
            "GovernmentGateway")
        }
      }

      "the relationship exists and the user is authenticated with Stride" should {
        trait StubsForThisScenario {
          givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-1234456")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
          givenAgentCanBeDeallocatedInIF(clientId, arn)
          givenEnrolmentDeallocationSucceeds("foo", enrolmentKey)
          givenAdminUser("foo", "any")
        }

        "return 204" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 204
        }

        "send the audit event HmrcRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath)
          verifyHmrcRemovedAgentServiceAuthorisation(
            arn.value,
            clientId.value,
            serviceId,
            "strideId-1234456",
            "PrivilegedApplication")
        }
      }

      "the relationship exists in ETMP and not exist in ES" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedClient(clientId, withThisGgUserId = "ggUserId-client")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenPrincipalGroupIdExistsFor(enrolmentKey, "clientGroupId")
          givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKey)
          givenAgentCanBeDeallocatedInIF(clientId, arn)
          givenAdminUser("foo", "any")
        }

        "return 500" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 500
        }

        "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath)
          verifyClientRemovedAgentServiceAuthorisationAuditSent(
            arn.value,
            clientId.value,
            serviceId,
            "ggUserId-client",
            "GovernmentGateway")
        }
      }

      "the relationship does not exist in either ETMP or in ES" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedClient(clientId, withThisGgUserId = "ggUserId-client")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenPrincipalGroupIdExistsFor(enrolmentKey, "clientGroupId")
          givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKey)
          givenAgentHasNoActiveRelationshipInIF(clientId, arn)
          givenAdminUser("foo", "any")
        }

        "return 500" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 500
        }

        "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath)
          verifyClientRemovedAgentServiceAuthorisationAuditSent(
            arn.value,
            clientId.value,
            serviceId,
            "ggUserId-client",
            "GovernmentGateway")
        }
      }

      "the relationship does not exist in ETMP but does exist in ES" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedClient(clientId, withThisGgUserId = "ggUserId-client")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
          givenAgentHasNoActiveRelationshipInIF(clientId, arn)
          givenEnrolmentDeallocationSucceeds("foo", enrolmentKey)
          givenAdminUser("foo", "any")
          givenCacheRefresh(arn)
        }

        "return 204" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 204
        }

        "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath)
          verifyClientRemovedAgentServiceAuthorisationAuditSent(
            arn.value,
            clientId.value,
            serviceId,
            "ggUserId-client",
            "GovernmentGateway")
        }
      }

      /**
       * Agent's Unhappy paths
       */
      "agent has a mismatched arn" should {
        "return 403" in {
          givenUserIsSubscribedAgent(Arn("unmatched"))
          doAgentDeleteRequest(requestPath).status shouldBe 403
        }

        "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
          givenUserIsSubscribedAgent(Arn("unmatched"))
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
        }
      }

      "agent has no agent enrolments" should {
        "return 403" in {
          givenUserHasNoAgentEnrolments(arn)
          doAgentDeleteRequest(requestPath).status shouldBe 403
        }

        "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
          givenUserHasNoAgentEnrolments(arn)
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
        }
      }

      "es is unavailable" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedAgent(arn)
          givenEsIsUnavailable()
          givenAgentCanBeDeallocatedInIF(clientId, arn)
        }

        "return 500" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 500
        }

        "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
        }
      }

      "DES/IF is unavailable" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedAgent(arn)
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenPrincipalGroupIdExistsFor(enrolmentKey, "clientGroupId")
          givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
          givenIFReturnsServiceUnavailable()
        }

        "return 500" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 500
        }

        "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
        }
      }

      "DES/IF responds with 404" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedAgent(arn)
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenPrincipalGroupIdExistsFor(enrolmentKey, "clientGroupId")
          givenAgentIsAllocatedAndAssignedToClient(enrolmentKey, "bar")
          givenAgentCanNotBeDeallocatedInIF(status = 404)
        }

        "return 500" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 500
        }

        "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
        }
      }

      /**
       * Client's Unhappy paths
       */

      "client has a mismatched clientId" should {
        val dummyClientId: TaxIdentifier = Service.forId(serviceId).supportedClientIdType.createUnderlying("unmatched")

        "return 403" in {
          givenUserIsSubscribedClient(dummyClientId)
          doAgentDeleteRequest(requestPath).status shouldBe 403
        }

        "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
          givenUserIsSubscribedClient(dummyClientId)
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
        }
      }

      "client has no client enrolments" should {
        "return 403" in {
          givenUserHasNoClientEnrolments
          doAgentDeleteRequest(requestPath).status shouldBe 403
        }

        "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
          givenUserHasNoClientEnrolments
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
        }
      }

      "client has no groupId" should {
        trait StubsForScenario {
          givenUserIsSubscribedClient(clientId)
          givenAgentCanBeDeallocatedInIF(clientId, arn)
          givenAuditConnector()
          //givenPrincipalGroupIdNotExistsFor(clientId)
        }

        "return 500" in new StubsForScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 500
        }

        "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForScenario {
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
        }
      }
    }

    s"DELETE /test-only/db/agent/:arn/service/$serviceId/client/$clientIdType/:clientId" should {

      val requestPath: String = s"/test-only/db/agent/${arn.value}/service/$serviceId/client/$clientIdType/$clientId"

      "return 404 for any call" in {

        await(repo.create(RelationshipCopyRecord(arn.value, Some(serviceId), clientId.value, clientIdType))) shouldBe 1
        val result = doAgentDeleteRequest(requestPath)
        result.status shouldBe 404
      }
    }

  }

  def strideEndpointISpec(serviceId: String, clientId: TaxIdentifier, clientIdType: String) = {
    s"GET /relationships/service/$serviceId/client/$clientIdType/:clientId" should {

      val requestPath: String = s"/agent-client-relationships/relationships/service/$serviceId/client/$clientIdType/${clientId.value}"

      def doRequest = doAgentGetRequest(requestPath)
      val req = FakeRequest()

      "find relationship and send back Json" in {
        givenAuthorisedAsStrideUser(req, "someStrideId")

        if (clientIdType == "NI") {
          givenMtdItIdIsKnownFor(Nino(clientId.value), mtdItId)
          getActiveRelationshipsViaClient(mtdItId, arn)
        } else
          getActiveRelationshipsViaClient(clientId, arn)

        val result = doRequest
        result.status shouldBe 200

        (result.json \ "arn").get.as[String] shouldBe arn.value
        (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
      }

      "find relationship but filter out if the end date has been changed from 9999-12-31" in {
        givenAuthorisedAsStrideUser(req, "someStrideId")

        if (clientIdType == "NI") {
          givenMtdItIdIsKnownFor(Nino(clientId.value), mtdItId)
          getInactiveRelationshipViaClient(mtdItId, arn.value)
        } else
          getInactiveRelationshipViaClient(clientId, arn.value)

        val result = doRequest
        result.status shouldBe 404
      }

      "find multiple relationships but filter out active and ended relationships" in {
        givenAuthorisedAsStrideUser(req, "someStrideId")

        if (clientIdType == "NI") {
          givenMtdItIdIsKnownFor(Nino(clientId.value), mtdItId)
          getSomeActiveRelationshipsViaClient(mtdItId, arn.value, arn2.value, arn3.value)
        } else
          getSomeActiveRelationshipsViaClient(clientId, arn.value, arn2.value, arn3.value)

        val result = doRequest
        result.status shouldBe 200
        (result.json \ "arn").get.as[String] shouldBe arn3.value
        (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
      }

      "return 404 when DES returns 404 relationship not found" in {
        givenAuthorisedAsStrideUser(req, "someStrideId")

        if (clientIdType == "NI") {
          givenMtdItIdIsKnownFor(Nino(clientId.value), mtdItId)
          getActiveRelationshipFailsWith(mtdItId, status = 404)
        } else
          getActiveRelationshipFailsWith(clientId, status = 404)

        val result = doRequest
        result.status shouldBe 404
      }

      "return 404 when DES returns 400 (treated as relationship not found)" in {
        givenAuthorisedAsStrideUser(req, "someStrideId")

        if (clientIdType == "NI") {
          givenMtdItIdIsKnownFor(Nino(clientId.value), mtdItId)
          getActiveRelationshipFailsWith(mtdItId, status = 400)
        } else
          getActiveRelationshipFailsWith(clientId, status = 400)

        val result = doRequest
        result.status shouldBe 404
      }
    }
  }
}
