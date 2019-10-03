package uk.gov.hmrc.agentrelationships.controllers

import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, CgtRef, MtdItId}

class RelationshipsControllerCGTISpec extends RelationshipsBaseControllerISpec {

  "PUT /agent/:arn/service/HMRC-CGT-PD/client/CGTPDRef/:cgtRef" should {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-CGT-PD/client/CGTPDRef/${cgtRef.value}"

    trait StubsForThisScenario {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenEnrolmentExistsForGroupId("bar", Arn("barArn"))
      givenEnrolmentExistsForGroupId("foo", Arn("fooArn"))
      givenDelegatedGroupIdsExistForCgt(cgtRef)
      givenAgentCanBeAllocatedInDes(cgtRef, arn)
      givenEnrolmentDeallocationSucceeds("foo", cgtRef)
      givenEnrolmentDeallocationSucceeds("bar", cgtRef)
      givenCGTEnrolmentAllocationSucceeds(cgtRef, "bar")
      givenAdminUser("foo", "any")
    }

    "return 201 when the relationship exists and the Arn matches that of current Agent user" in new StubsForThisScenario {
      givenUserIsSubscribedAgent(arn)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and de-allocation of previous relationship fails" in new StubsForThisScenario {
      givenUserIsSubscribedAgent(arn)
      givenDelegatedGroupIdsExistForCgt(cgtRef, "zoo")
      givenEnrolmentExistsForGroupId("zoo", Arn("zooArn"))
      givenEnrolmentDeallocationFailsWith(502, "zoo", cgtRef)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the CgtRef matches that of current Client user" in new StubsForThisScenario {
      givenUserIsSubscribedClient(cgtRef)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the user is authenticated with Stride" in new StubsForThisScenario {
      givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-983283")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and previous relationships too but ARNs not found" in new StubsForThisScenario {
      givenUserIsSubscribedAgent(arn)
      givenEnrolmentNotExistsForGroupId("zoo")
      givenEnrolmentDeallocationSucceeds("zoo", cgtRef)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when there are no previous relationships to deallocate" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistFor(cgtRef)
      givenAgentCanBeAllocatedInDes(cgtRef, arn)
      givenCGTEnrolmentAllocationSucceeds(cgtRef, "bar")
      givenAdminUser("foo", "any")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    /**
      * Agent's Unhappy paths
      */
    "return 403 for an agent with a mismatched arn" in {
      givenUserIsSubscribedAgent(Arn("unmatched"))

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }

    "return 403 for an agent with no agent enrolments" in {
      givenUserHasNoAgentEnrolments(arn)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }

    "return 502 when ES1 is unavailable" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo", userId = "user1")
      givenGroupInfo(groupId = "foo", agentCode = "bar")
      givenDelegatedGroupIdRequestFailsWith(503)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 502
    }

    "return 502 when ES8 is unavailable" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo", userId = "user1")
      givenGroupInfo(groupId = "foo", agentCode = "bar")
      givenDelegatedGroupIdsNotExistForCgt(cgtRef)
      givenAgentCanBeAllocatedInDes(cgtRef, arn)
      givenEnrolmentAllocationFailsWith(503)(
        groupId = "foo",
        clientUserId = "user1",
        key = "HMRC-CGT-PD",
        identifier = "CGTPDRef",
        value = cgtRef.value,
        agentCode = "bar")
      givenAdminUser("foo", "user1")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 502
      (result.json \ "message").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_ES")
    }

    "return 502 when DES is unavailable" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForCgt(cgtRef)
      givenDesReturnsServiceUnavailable()
      givenAdminUser("foo", "any")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 502
      (result.json \ "message").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_DES")
    }

    "return 404 if DES returns 404" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForCgt(cgtRef)
      givenAgentCanNotBeAllocatedInDes(status = 404)
      givenAdminUser("foo", "any")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 404
      (result.json \ "code").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_DES")
    }

    /**
      * Client's Unhappy paths
      */
    "return 403 for a client with a mismatched CgtRef" in {
      givenUserIsSubscribedClient(CgtRef("unmatched"))

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }

    "return 403 for a client with no client enrolments" in {
      givenUserHasNoClientEnrolments

      val result = await(doAgentPutRequest(requestPath))
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
        givenAgentCanBeDeallocatedInDes(cgtRef, arn)
        givenEnrolmentDeallocationSucceeds("foo", cgtRef)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send an audit event called ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
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
        givenAgentCanBeDeallocatedInDes(cgtRef, arn)
        givenEnrolmentDeallocationSucceeds("foo", cgtRef)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
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
        givenAgentCanBeDeallocatedInDes(cgtRef, arn)
        givenEnrolmentDeallocationSucceeds("foo", cgtRef)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send the audit event HmrcRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
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
        givenAgentCanBeDeallocatedInDes(cgtRef, arn)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
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
        givenAgentHasNoActiveRelationshipInDes(cgtRef, arn)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
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
        givenAgentHasNoActiveRelationshipInDes(cgtRef, arn)
        givenEnrolmentDeallocationSucceeds("foo", cgtRef)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
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
        await(doAgentDeleteRequest(requestPath)).status shouldBe 403
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserIsSubscribedAgent(Arn("unmatched"))
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "agent has no agent enrolments" should {
      "return 403" in {
        givenUserHasNoAgentEnrolments(arn)
        await(doAgentDeleteRequest(requestPath)).status shouldBe 403
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserHasNoAgentEnrolments(arn)
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "es is unavailable" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenEsIsUnavailable()
        givenAgentCanBeDeallocatedInDes(cgtRef, arn)
      }

      "return 502" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 502
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
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
        givenDesReturnsServiceUnavailable()
      }

      "return 502" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 502
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
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
        givenAgentCanNotBeDeallocatedInDes(status = 404)
      }

      "return 404" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 404
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    /**
      * Client's Unhappy paths
      */
    "client has a mismatched MtdItId" should {

      "return 403" in {
        givenUserIsSubscribedClient(MtdItId("unmatched"))
        await(doAgentDeleteRequest(requestPath)).status shouldBe 403
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserIsSubscribedClient(MtdItId("unmatched"))
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "client has no client enrolments" should {
      "return 403" in {
        givenUserHasNoClientEnrolments
        await(doAgentDeleteRequest(requestPath)).status shouldBe 403
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserHasNoClientEnrolments
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "client has no groupId" should {
      trait StubsForScenario {
        givenUserIsSubscribedClient(cgtRef)
        givenPrincipalGroupIdNotExistsFor(cgtRef)
      }

      "return 404" in new StubsForScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 404
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }
  }

}
