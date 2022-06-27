package uk.gov.hmrc.agentclientrelationships.controllers

import org.joda.time.{DateTime, LocalDate}
import play.api.libs.json.JodaReads._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Urn}
import uk.gov.hmrc.lock.LockFormats.Lock

import scala.concurrent.ExecutionContext.Implicits.global

class RelationshipsControllerTrustNTISpec extends RelationshipsBaseControllerISpec {

  "GET  /agent/:arn/service/HMRC-TERSNT-ORG/client/URN/:urn" should {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-TERSNT-ORG/client/URN/${urn.value}"

    def doRequest = doAgentGetRequest(requestPath)

    //HAPPY PATH :-)

    "return 200 when relationship exists in es" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(urn, "bar")
      givenAdminUser("foo", "any")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> urn.value, "clientIdentifierType" -> urnType)

      await(query()) shouldBe empty
      val result = doRequest
      result.status shouldBe 200
      await(query()) shouldBe empty
    }

    //UNHAPPY PATHS

    "return 404 when credentials are not found in es" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenGroupInfo("foo", "bar")

      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "UNKNOWN_ARN"
    }

    "return 404 when agent code is not found in ugs" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoNoAgentCode("foo")
      givenAdminUser("foo", "any")

      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "NO_AGENT_CODE"
    }

    //FAILURE CASES

    "return 5xx when ES1/principal returns 5xx" in {
      givenPrincipalGroupIdRequestFailsWith(500)
      givenGroupInfo("foo", "bar")

      val result = doRequest
      result.status shouldBe 500
    }

    "return 5xx when UGS returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(500)
      givenAgentIsAllocatedAndAssignedToClient(urn, "bar")

      val result = doRequest
      result.status shouldBe 500
    }

    "return 5xx when ES1/delegated returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")

      givenDelegatedGroupIdRequestFailsWith(500)
      val result = doRequest
      result.status shouldBe 500
    }

    "return 400 when ES1/principal returns 4xx" in {
      givenPrincipalGroupIdRequestFailsWith(400)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(urn, "bar")

      val result = doRequest
      result.status shouldBe 400
    }

    "return 400 when UGS returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(400)
      givenAgentIsAllocatedAndAssignedToClient(urn, "bar")

      val result = doRequest
      result.status shouldBe 400
    }

    "return 400 when ES1/delegated returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdRequestFailsWith(400)

      val result = doRequest
      result.status shouldBe 400
    }
  }

  "PUT /agent/:arn/service/HMRC-TERSNT-ORG/client/URN/:urn" should {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-TERSNT-ORG/client/URN/${urn.value}"

    trait StubsForThisScenario {
      givenAgentCanBeAllocatedInIF(urn, arn)

      givenPrincipalUser(arn, "foo")
      givenAdminUser("foo", "any")
      givenGroupInfo("foo", "bar")

      givenDelegatedGroupIdsExistForTrustNT(urn) // allocated to groupId's "foo" and "bar"
      givenEnrolmentExistsForGroupId("bar", Arn("barArn"))
      givenEnrolmentExistsForGroupId("foo", Arn("fooArn"))

      givenTrustNTEnrolmentAllocationSucceeds(urn, "bar")

    }

    "return 201 when the relationship exists and de-allocation of previous relationship fails" in new StubsForThisScenario {
      givenUserIsSubscribedClient(urn)
      //givenDelegatedGroupIdsExistForTrustNT(urn, "zoo")
      //givenEnrolmentExistsForGroupId("zoo", Arn("zooArn"))
      givenEnrolmentDeallocationFailsWith(502, "foo", urn)
      givenEnrolmentDeallocationFailsWith(502, "bar", urn)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the URN matches that of current Client user" in new StubsForThisScenario {
      givenUserIsSubscribedClient(urn)
      givenEnrolmentDeallocationSucceeds("foo", urn)
      givenEnrolmentDeallocationSucceeds("bar", urn)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the user is authenticated with Stride" in new StubsForThisScenario {
      givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-983283")
      givenEnrolmentDeallocationSucceeds("foo", urn)
      givenEnrolmentDeallocationSucceeds("bar", urn)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when there are no previous relationships to deallocate" in {
      givenUserIsSubscribedClient(urn)
      givenPrincipalUser(arn, "foo")
      givenAdminUser("foo", "any")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistFor(urn)
      givenAgentCanBeAllocatedInIF(urn, arn)
      givenTrustNTEnrolmentAllocationSucceeds(urn, "bar")


      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when an agent tries to create a relationship" in {
      givenUserIsSubscribedAgent(arn)
      givenAgentCanBeAllocatedInIF(urn, arn)
      givenPrincipalGroupIdExistsFor(arn, "foo")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 423 Locked if there is a record in the lock repository" in {
      givenUserIsSubscribedClient(urn)
      await(recoveryLockRepository.insert(
        Lock(
          id =s"recovery-${arn.value}-${urn.value}",
          owner="86515a24-1a37-4a40-9117-4a117d8dd42e",
          expiryTime= DateTime.now().plusSeconds(5),
          timeCreated= DateTime.now().minusSeconds(5))))

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe LOCKED

    }

    "return 500 when ES1 is unavailable" in new StubsForThisScenario {
      givenUserIsSubscribedClient(urn)
      givenDelegatedGroupIdRequestFailsWith(503)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 500
    }

    "return 500 when ES8 is unavailable" in {
      givenUserIsSubscribedClient(urn)
      givenPrincipalUser(arn, "foo", userId = "user1")
      givenGroupInfo(groupId = "foo", agentCode = "bar")
      givenDelegatedGroupIdsNotExistForTrustNT(urn)
      givenAgentCanBeAllocatedInIF(urn, arn)
      givenEnrolmentAllocationFailsWith(503)(
        groupId = "foo",
        clientUserId = "user1",
        key = "HMRC-TERSNT-ORG",
        identifier = "URN",
        value = urn.value,
        agentCode = "bar")
      givenAdminUser("foo", "user1")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 500
      (result.json \ "message").asOpt[String] shouldBe None
    }

    "return 500 when IF is unavailable" in {
      givenUserIsSubscribedClient(urn)
      givenDelegatedGroupIdsNotExistForTrustNT(urn)
      givenDesReturnsServiceUnavailable()

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 500
      (result.json \ "message").asOpt[String] shouldBe None
    }

    "return 500 if IF returns 404" in {
      givenUserIsSubscribedClient(urn)
      givenDelegatedGroupIdsNotExistForTrustNT(urn)
      givenAgentCanNotBeAllocatedInIF(status = 404)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 500
      (result.json \ "code").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_IF")
    }

    "return 403 for a client with a mismatched MtdItId" in {
      givenUserIsSubscribedClient(Urn("unmatched"))

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 403
    }

    "return 403 for a client with no client enrolments" in {
      givenUserHasNoClientEnrolments

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 403
    }

  }

  "DELETE /agent/:arn/service/HMRC-TERSNT-ORG/client/URN/:urn" when {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-TERSNT-ORG/client/URN/${urn.value}"

    def verifyClientRemovedAgentServiceAuthorisationAuditSent(
                                                               arn: String,
                                                               clientId: String,
                                                               clientIdType: String,
                                                               service: String,
                                                               currentUserAffinityGroup: String,
                                                               authProviderId: String,
                                                               authProviderIdType: String): Unit =
      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation,
        detail = Map(
          "agentReferenceNumber"     -> arn,
          "clientId"                 -> clientId,
          "clientIdType"             -> clientIdType,
          "service"                  -> service,
          "currentUserAffinityGroup" -> currentUserAffinityGroup,
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

    "the relationship exists and the Urn matches that of current Agent user" should {

      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(urn, "bar")
        givenAgentCanBeDeallocatedInIF(urn, arn)
        givenEnrolmentDeallocationSucceeds("foo", urn)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
      }

      "send an audit event called ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          urn.value,
          "Urn",
          "HMRC-TERSNT-ORG",
          "Agent",
          "ggUserId-agent",
          "GovernmentGateway")
      }
    }

    "the relationship exists and the Urn matches that of current Client user" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(urn, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(urn, "bar")
        givenAgentCanBeDeallocatedInIF(urn, arn)
        givenEnrolmentDeallocationSucceeds("foo", urn)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          urn.value,
          "Urn",
          "HMRC-TERSNT-ORG",
          "Organisation",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship exists and the user is authenticated with Stride" should {
      trait StubsForThisScenario {
        givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-1234456")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(urn, "bar")
        givenAgentCanBeDeallocatedInIF(urn, arn)
        givenEnrolmentDeallocationSucceeds("foo", urn)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
      }

      "send the audit event HmrcRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyHmrcRemovedAgentServiceAuthorisation(
          arn.value,
          urn.value,
          "HMRC-TERSNT-ORG",
          "strideId-1234456",
          "PrivilegedApplication")
      }
    }

    "the relationship exists in ETMP and not exist in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(urn, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(urn, "clientGroupId")
        givenDelegatedGroupIdsNotExistForTrustNT(urn)
        givenAgentCanBeDeallocatedInIF(urn, arn)
        givenAdminUser("foo", "any")
      }

      "return 500" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 500
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          urn.value,
          "Urn",
          "HMRC-TERSNT-ORG",
          "Organisation",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship does not exist in either ETMP or in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(urn, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(vrn, "clientGroupId")
        givenDelegatedGroupIdsNotExistForTrustNT(urn)
        givenAgentHasNoActiveRelationshipInIF(urn, arn)
        givenAdminUser("foo", "any")
      }

      "return 500" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 500
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          urn.value,
          "Urn",
          "HMRC-TERSNT-ORG",
          "Organisation",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship does not exist in ETMP but does exist in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(urn, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(urn, "bar")
        givenAgentHasNoActiveRelationshipInIF(urn, arn)
        givenEnrolmentDeallocationSucceeds("foo", urn)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          urn.value,
          "Urn",
          "HMRC-TERSNT-ORG",
          "Organisation",
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
        givenAgentCanBeDeallocatedInIF(urn, arn)
      }

      "return 500" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 500
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "IF is unavailable" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(urn, "clientGroupId")
        givenAgentIsAllocatedAndAssignedToClient(urn, "bar")
        givenDesReturnsServiceUnavailable()
      }

      "return 500" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 500
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "IF responds with 404" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(urn, "clientGroupId")
        givenAgentIsAllocatedAndAssignedToClient(urn, "bar")
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
    "client has a mismatched MtdItId" should {

      "return 403" in {
        givenUserIsSubscribedClient(MtdItId("unmatched"))
        doAgentDeleteRequest(requestPath).status shouldBe 403
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserIsSubscribedClient(MtdItId("unmatched"))
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
        givenUserIsSubscribedClient(urn)
        givenAgentCanBeDeallocatedInIF(urn, arn)
        givenPrincipalGroupIdExistsFor(arn, "foo")
        givenAdminUser("foo", "any")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdRequestFailsWith(404)
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

  "GET /relationships/service/HMRC-TERSNT-ORG/client/URN/:urn" should {

    val requestPath: String = s"/agent-client-relationships/relationships/service/HMRC-TERSNT-ORG/client/URN/${urn.value}"

    def doRequest = doAgentGetRequest(requestPath)
    val req = FakeRequest()

    "find relationship and send back Json" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      getActiveRelationshipsViaClient(urn, arn)

      val result = doRequest
      result.status shouldBe 200

      (result.json \ "arn").get.as[String] shouldBe arn.value
      (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
    }

    "find relationship but filter out if the end date has been changed from 9999-12-31" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      getInactiveRelationshipViaClient(urn, arn.value)

      val result = doRequest
      result.status shouldBe 404
    }

    "find multiple relationships but filter out active and ended relationships" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      getSomeActiveRelationshipsViaClient(urn, arn.value, arn2.value, arn3.value)

      val result = doRequest
      result.status shouldBe 200
      (result.json \ "arn").get.as[String] shouldBe arn3.value
      (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
    }

    "return 404 when IF returns 404 relationship not found" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      getActiveRelationshipFailsWith(urn, status = 404)

      val result = doRequest
      result.status shouldBe 404
    }

    "return 404 when IF returns 400 (treated as relationship not found)" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      getActiveRelationshipFailsWith(urn, status = 400)

      val result = doRequest
      result.status shouldBe 404
    }
  }

}
