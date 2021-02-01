package uk.gov.hmrc.agentrelationships.controllers

import org.joda.time.LocalDate

import play.api.test.FakeRequest
import play.api.libs.json.JodaReads._
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Urn}
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
      val result = await(doRequest)
      result.status shouldBe 200
      await(query()) shouldBe empty
    }

    //UNHAPPY PATHS

    "return 404 when credentials are not found in es" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(urn, "bar")
      givenDelegatedGroupIdsNotExistForKey(s"HMRC-TERSNT-ORG~URN~${urn.value}")

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "UNKNOWN_ARN"
    }

    "return 404 when agent code is not found in ugs (and no relationship in old world)" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoNoAgentCode("foo")
      givenDelegatedGroupIdsExistFor(urn, Set("foo"))
      givenDelegatedGroupIdsNotExistForKey(s"HMRC-TERSNT-ORG~URN~${urn.value}")
      givenAdminUser("foo", "any")

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "NO_AGENT_CODE"
    }

    //FAILURE CASES

    "return 502 when ES1/principal returns 5xx" in {
      givenPrincipalGroupIdRequestFailsWith(500)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(urn, "bar")

      val result = await(doRequest)
      result.status shouldBe 500
    }

    "return 502 when UGS returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(500)
      givenAgentIsAllocatedAndAssignedToClient(urn, "bar")

      val result = await(doRequest)
      result.status shouldBe 500
    }

    "return 502 when ES1/delegated returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")

      givenDelegatedGroupIdRequestFailsWith(500)
      val result = await(doRequest)
      result.status shouldBe 500
    }

    "return 400 when ES1/principal returns 4xx" in {
      givenPrincipalGroupIdRequestFailsWith(400)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(urn, "bar")

      val result = await(doRequest)
      result.status shouldBe 400
    }

    "return 400 when UGS returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(400)
      givenAgentIsAllocatedAndAssignedToClient(urn, "bar")

      val result = await(doRequest)
      result.status shouldBe 400
    }

    "return 400 when ES1/delegated returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdRequestFailsWith(400)

      val result = await(doRequest)
      result.status shouldBe 400
    }
  }

  "PUT /agent/:arn/service/HMRC-TERSNT-ORG/client/URN/:urn" should {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-TERSNT-ORG/client/URN/${urn.value}"

    trait StubsForThisScenario {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenEnrolmentExistsForGroupId("bar", Arn("barArn"))
      givenEnrolmentExistsForGroupId("foo", Arn("fooArn"))
      givenDelegatedGroupIdsExistForTrustNT(urn)
      givenAgentCanBeAllocatedInDes(urn, arn)
      givenEnrolmentDeallocationSucceeds("foo", urn)
      givenEnrolmentDeallocationSucceeds("bar", urn)
      givenTrustNTEnrolmentAllocationSucceeds(urn, "bar")
      givenAdminUser("foo", "any")
    }

    "return 201 when the relationship exists and de-allocation of previous relationship fails" in new StubsForThisScenario {
      givenUserIsSubscribedClient(urn)
      givenDelegatedGroupIdsExistForTrustNT(urn, "zoo")
      givenEnrolmentExistsForGroupId("zoo", Arn("zooArn"))
      givenEnrolmentDeallocationFailsWith(502, "zoo", urn)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the URN matches that of current Client user" in new StubsForThisScenario {
      givenUserIsSubscribedClient(urn)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the user is authenticated with Stride" in new StubsForThisScenario {
      givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-983283")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when there are no previous relationships to deallocate" in {
      givenUserIsSubscribedClient(urn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistFor(urn)
      givenAgentCanBeAllocatedInDes(urn, arn)
      givenTrustNTEnrolmentAllocationSucceeds(urn, "bar")
      givenAdminUser("foo", "any")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 403 when an agent tries to create a relationship" in {
      givenUserIsSubscribedAgent(Arn("arn"))

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }

    "return 502 when ES1 is unavailable" in new StubsForThisScenario {
      givenUserIsSubscribedClient(urn)
      givenDelegatedGroupIdRequestFailsWith(503)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 503
    }

    "return 502 when ES8 is unavailable" in {
      givenUserIsSubscribedClient(urn)
      givenPrincipalUser(arn, "foo", userId = "user1")
      givenGroupInfo(groupId = "foo", agentCode = "bar")
      givenDelegatedGroupIdsNotExistForTrustNT(urn)
      givenAgentCanBeAllocatedInDes(urn, arn)
      givenEnrolmentAllocationFailsWith(503)(
        groupId = "foo",
        clientUserId = "user1",
        key = "HMRC-TERSNT-ORG",
        identifier = "URN",
        value = urn.value,
        agentCode = "bar")
      givenAdminUser("foo", "user1")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 503
      (result.json \ "message").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_ES")
    }

    "return 502 when DES is unavailable" in {
      givenUserIsSubscribedClient(urn)
      givenDelegatedGroupIdsNotExistForTrustNT(urn)
      givenDesReturnsServiceUnavailable()

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 503
      (result.json \ "message").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_DES")
    }

    "return 404 if DES returns 404" in {
      givenUserIsSubscribedClient(urn)
      givenDelegatedGroupIdsNotExistForTrustNT(urn)
      givenAgentCanNotBeAllocatedInDes(status = 404)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 404
      (result.json \ "code").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_DES")
    }

    "return 403 for a client with a mismatched MtdItId" in {
      givenUserIsSubscribedClient(Urn("unmatched"))

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }

    "return 403 for a client with no client enrolments" in {
      givenUserHasNoClientEnrolments

      val result = await(doAgentPutRequest(requestPath))
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
        givenAgentCanBeDeallocatedInDes(urn, arn)
        givenEnrolmentDeallocationSucceeds("foo", urn)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send an audit event called ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
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
        givenAgentCanBeDeallocatedInDes(urn, arn)
        givenEnrolmentDeallocationSucceeds("foo", urn)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
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
        givenAgentCanBeDeallocatedInDes(urn, arn)
        givenEnrolmentDeallocationSucceeds("foo", urn)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send the audit event HmrcRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
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
        givenAgentCanBeDeallocatedInDes(urn, arn)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
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
        givenAgentHasNoActiveRelationshipInDes(urn, arn)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
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
        givenAgentHasNoActiveRelationshipInDes(urn, arn)
        givenEnrolmentDeallocationSucceeds("foo", urn)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
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
        givenAgentCanBeDeallocatedInDes(urn, arn)
      }

      "return 502" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 503
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
        givenPrincipalGroupIdExistsFor(urn, "clientGroupId")
        givenAgentIsAllocatedAndAssignedToClient(urn, "bar")
        givenDesReturnsServiceUnavailable()
      }

      "return 502" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 503
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
        givenPrincipalGroupIdExistsFor(urn, "clientGroupId")
        givenAgentIsAllocatedAndAssignedToClient(urn, "bar")
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
        givenUserIsSubscribedClient(urn)
        givenPrincipalGroupIdNotExistsFor(urn)
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

  "GET /relationships/service/HMRC-TERSNT-ORG/client/URN/:urn" should {

    val requestPath: String = s"/agent-client-relationships/relationships/service/HMRC-TERSNT-ORG/client/URN/${urn.value}"

    def doRequest = doAgentGetRequest(requestPath)
    val req = FakeRequest()

    "find relationship and send back Json" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      getActiveRelationshipsViaClient(urn, arn)

      val result = await(doRequest)
      result.status shouldBe 200

      (result.json \ "arn").get.as[String] shouldBe arn.value
      (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
    }

    "find relationship but filter out if the end date has been changed from 9999-12-31" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      getInactiveRelationshipViaClient(urn, arn.value)

      val result = await(doRequest)
      result.status shouldBe 404
    }

    "find multiple relationships but filter out active and ended relationships" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      getSomeActiveRelationshipsViaClient(urn, arn.value, arn2.value, arn3.value)

      val result = await(doRequest)
      result.status shouldBe 200
      (result.json \ "arn").get.as[String] shouldBe arn3.value
      (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
    }

    "return 404 when DES returns 404 relationship not found" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      getActiveRelationshipFailsWith(urn, status = 404)

      val result = await(doRequest)
      result.status shouldBe 404
    }

    "return 404 when DES returns 400 (treated as relationship not found)" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      getActiveRelationshipFailsWith(urn, status = 400)

      val result = await(doRequest)
      result.status shouldBe 404
    }
  }

}
