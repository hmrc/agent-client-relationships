package uk.gov.hmrc.agentclientrelationships.controllers

import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, CgtRef, MtdItId}

import scala.concurrent.ExecutionContext.Implicits.global

class RelationshipsControllerCGTISpec extends RelationshipsBaseControllerISpec {

  "GET  /agent/:arn/service/HMRC-CGT-PD/client/CGTPDRef/:cgtRef" should {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-CGT-PD/client/CGTPDRef/${cgtRef.value}"

    def doRequest = doAgentGetRequest(requestPath)

    //HAPPY PATH :-)

    "return 200 when relationship exists in es" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(cgtRef, "bar")
      givenAdminUser("foo", "any")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> cgtRef.value, "clientIdentifierType" -> "CGTPDRef")

      await(query()) shouldBe empty
      val result = doRequest
      result.status shouldBe 200
      await(query()) shouldBe empty
    }

    //UNHAPPY PATHS

    "return 404 when credentials are not found in es" in {
      givenPrincipalGroupIdNotExistsFor(arn)

      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "UNKNOWN_ARN"
    }

    "return 404 when agent code is not found in ugs (and no relationship in old world)" in {
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

      val result = doRequest
      result.status shouldBe 500
    }

    "return 5xx when UGS returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(500)
      //givenAgentIsAllocatedAndAssignedToClient(cgtRef, "bar")

      val result = doRequest
      result.status shouldBe 500
    }

    "return 5xx when ES1/delegated returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAdminUser("foo", "any")

      givenDelegatedGroupIdRequestFailsWith(500)
      val result = doRequest
      result.status shouldBe 500
    }

    "return 400 when ES1/principal returns 400" in {
      givenPrincipalGroupIdRequestFailsWith(400)

      val result = doRequest
      result.status shouldBe 400
    }

    "return 400 when UGS returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(400)
      //givenAgentIsAllocatedAndAssignedToClient(cgtRef, "bar")

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


  "PUT /agent/:arn/service/HMRC-CGT-PD/client/CGTPDRef/:cgtRef" should {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-CGT-PD/client/CGTPDRef/${cgtRef.value}"

    trait StubsForThisScenario {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenEnrolmentExistsForGroupId("bar", Arn("barArn"))
      givenEnrolmentExistsForGroupId("foo", Arn("fooArn"))
      givenDelegatedGroupIdsExistForCgt(cgtRef)
      givenAgentCanBeAllocatedInIF(cgtRef, arn)
      givenEnrolmentDeallocationSucceeds("foo", cgtRef)
      givenEnrolmentDeallocationSucceeds("bar", cgtRef)
      givenCGTEnrolmentAllocationSucceeds(cgtRef, "bar")
      givenAdminUser("foo", "any")
    }

    "return 201 when the relationship exists and de-allocation of previous relationship fails" in new StubsForThisScenario {
      givenUserIsSubscribedClient(cgtRef)
      givenDelegatedGroupIdsExistForCgt(cgtRef, "zoo")
      givenEnrolmentExistsForGroupId("zoo", Arn("zooArn"))
      givenEnrolmentDeallocationFailsWith(502, "zoo", cgtRef)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the CgtRef matches that of current Client user" in new StubsForThisScenario {
      givenUserIsSubscribedClient(cgtRef)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the user is authenticated with Stride" in new StubsForThisScenario {
      givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-983283")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and previous relationships too but ARNs not found" in new StubsForThisScenario {
      givenUserIsSubscribedClient(cgtRef)
      givenEnrolmentNotExistsForGroupId("zoo")
      givenEnrolmentDeallocationSucceeds("zoo", cgtRef)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when there are no previous relationships to deallocate" in {
      givenUserIsSubscribedClient(cgtRef)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistFor(cgtRef)
      givenAgentCanBeAllocatedInIF(cgtRef, arn)
      givenCGTEnrolmentAllocationSucceeds(cgtRef, "bar")
      givenAdminUser("foo", "any")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 503 when ES1 is unavailable" in new StubsForThisScenario {
      givenUserIsSubscribedClient(cgtRef)
      givenPrincipalUser(arn, "foo", userId = "user1")
      givenGroupInfo(groupId = "foo", agentCode = "bar")
      givenDelegatedGroupIdRequestFailsWith(503)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 503
    }

    "return 502 when ES8 is unavailable" in {
      givenUserIsSubscribedClient(cgtRef)
      givenPrincipalUser(arn, "foo", userId = "user1")
      givenGroupInfo(groupId = "foo", agentCode = "bar")
      givenDelegatedGroupIdsNotExistForCgt(cgtRef)
      givenAgentCanBeAllocatedInIF(cgtRef, arn)
      givenEnrolmentAllocationFailsWith(503)(
        groupId = "foo",
        clientUserId = "user1",
        key = "HMRC-CGT-PD",
        identifier = "CGTPDRef",
        value = cgtRef.value,
        agentCode = "bar")
      givenAdminUser("foo", "user1")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 503
      (result.json \ "message").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_ES")
    }

    "return 404 when DES is unavailable" in {
      givenUserIsSubscribedClient(cgtRef)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForCgt(cgtRef)
      givenIFReturnsServiceUnavailable()
      givenAdminUser("foo", "any")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 404
      (result.json \ "message").asOpt[String] shouldBe None
    }

    "return 404 if DES returns 404" in {
      givenUserIsSubscribedClient(cgtRef)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForCgt(cgtRef)
      givenAgentCanNotBeAllocatedInIF(status = 404)
      givenAdminUser("foo", "any")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 404
      (result.json \ "code").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_ES")
    }

    "return 403 for a client with a mismatched CgtRef" in {
      givenUserIsSubscribedClient(CgtRef("unmatched"))

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 403
    }

    "return 403 for a client with no client enrolments" in {
      givenUserHasNoClientEnrolments

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 403
    }

  }

  "DELETE /agent/:arn/service/HMRC-CGT-PD/client/CGTPDRef/:cgtRef" when {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-CGT-PD/client/CGTPDRef/${cgtRef.value}"

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

    "the relationship exists and the CgtRef matches that of current Agent user" should {

      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(cgtRef, "bar")
        givenAgentCanBeDeallocatedInIF(cgtRef, arn)
        givenEnrolmentDeallocationSucceeds("foo", cgtRef)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
      }

      "send an audit event called ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          cgtRef.value,
          "CgtRef",
          "HMRC-CGT-PD",
          "Agent",
          "ggUserId-agent",
          "GovernmentGateway")
      }
    }

    "the relationship exists and the CgtRef matches that of current Client user" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(cgtRef, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(cgtRef, "bar")
        givenAgentCanBeDeallocatedInIF(cgtRef, arn)
        givenEnrolmentDeallocationSucceeds("foo", cgtRef)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          cgtRef.value,
          "CgtRef",
          "HMRC-CGT-PD",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship exists and the user is authenticated with Stride" should {
      trait StubsForThisScenario {
        givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-1234456")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(cgtRef, "bar")
        givenAgentCanBeDeallocatedInIF(cgtRef, arn)
        givenEnrolmentDeallocationSucceeds("foo", cgtRef)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
      }

      "send the audit event HmrcRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyHmrcRemovedAgentServiceAuthorisation(
          arn.value,
          cgtRef.value,
          "HMRC-CGT-PD",
          "strideId-1234456",
          "PrivilegedApplication")
      }
    }

    "the relationship exists in ETMP and not exist in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(cgtRef, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(cgtRef, "clientGroupId")
        givenDelegatedGroupIdsNotExistForCgt(cgtRef)
        givenAgentCanBeDeallocatedInIF(cgtRef, arn)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          cgtRef.value,
          "CgtRef",
          "HMRC-CGT-PD",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship does not exist in either ETMP or in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(cgtRef, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(cgtRef, "clientGroupId")
        givenDelegatedGroupIdsNotExistForCgt(cgtRef)
        givenAgentHasNoActiveRelationshipInIF(cgtRef, arn)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          cgtRef.value,
          "CgtRef",
          "HMRC-CGT-PD",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship does not exist in ETMP but does exist in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(cgtRef, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(cgtRef, "bar")
        givenAgentHasNoActiveRelationshipInIF(cgtRef, arn)
        givenEnrolmentDeallocationSucceeds("foo", cgtRef)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          cgtRef.value,
          "CgtRef",
          "HMRC-CGT-PD",
          "Individual",
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
        givenAgentCanBeDeallocatedInIF(cgtRef, arn)
      }

      "return 502" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 503
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "DES is unavailable" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(cgtRef, "clientGroupId")
        givenAgentIsAllocatedAndAssignedToClient(cgtRef, "bar")
        givenIFReturnsServiceUnavailable()
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "DES responds with 404" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(cgtRef, "clientGroupId")
        givenAgentIsAllocatedAndAssignedToClient(cgtRef, "bar")
        givenAgentCanNotBeDeallocatedInIF(status = 404)
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
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
        givenUserIsSubscribedClient(cgtRef)
        givenAgentCanBeDeallocatedInIF(cgtRef, arn)
        givenAuditConnector()
        //givenPrincipalGroupIdNotExistsFor(cgtRef)
      }

      "return 404" in new StubsForScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 404
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForScenario {
        doAgentDeleteRequest(requestPath)
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }
  }

}
