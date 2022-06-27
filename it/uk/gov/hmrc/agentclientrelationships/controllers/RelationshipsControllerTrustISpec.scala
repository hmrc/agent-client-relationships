/*
 * Copyright 2017 HM Revenue & Customs
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

import org.joda.time.{DateTime, LocalDate}
import play.api.libs.json.JodaReads._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Utr}
import uk.gov.hmrc.lock.LockFormats.Lock

import scala.concurrent.ExecutionContext.Implicits.global

//noinspection ScalaStyle
class RelationshipsControllerTrustISpec extends RelationshipsBaseControllerISpec {

  "GET  /agent/:arn/service/HMRC-TERS-ORG/client/SAUTR/:utr" should {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-TERS-ORG/client/SAUTR/${utr.value}"

    def doRequest = doAgentGetRequest(requestPath)

    //HAPPY PATH :-)

    "return 200 when relationship exists in es" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(utr, "bar")
      givenAdminUser("foo", "any")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> utr.value, "clientIdentifierType" -> saUtrType)

      await(query()) shouldBe empty
      val result = doRequest
      result.status shouldBe 200
      await(query()) shouldBe empty
    }

    //UNHAPPY PATHS

    "return 404 when credentials are not found in es" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(utr, "bar")
      givenDelegatedGroupIdsNotExistForKey(s"HMRC-TERS-ORG~SAUTR~${utr.value}")

      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "UNKNOWN_ARN"
    }

    "return 404 when agent code is not found in ugs (and no relationship in old world)" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoNoAgentCode("foo")
      givenDelegatedGroupIdsExistFor(utr, Set("foo"))
      givenDelegatedGroupIdsNotExistForKey(s"HMRC-TERS-ORG~SAUTR~${utr.value}")
      givenAdminUser("foo", "any")

      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "NO_AGENT_CODE"
    }

    //FAILURE CASES

    "return 502 when ES1/principal returns 5xx" in {
      givenPrincipalGroupIdRequestFailsWith(500)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(utr, "bar")

      val result = doRequest
      result.status shouldBe 500
    }

    "return 502 when UGS returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(500)
      givenAgentIsAllocatedAndAssignedToClient(utr, "bar")

      val result = doRequest
      result.status shouldBe 500
    }

    "return 502 when ES1/delegated returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")

      givenDelegatedGroupIdRequestFailsWith(500)
      val result = doRequest
      result.status shouldBe 500
    }

    "return 400 when ES1/principal returns 4xx" in {
      givenPrincipalGroupIdRequestFailsWith(400)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(utr, "bar")

      val result = doRequest
      result.status shouldBe 400
    }

    "return 400 when UGS returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(400)
      givenAgentIsAllocatedAndAssignedToClient(utr, "bar")

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

  "PUT /agent/:arn/service/HMRC-TERS-ORG/client/SAUTR/:utr" should {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-TERS-ORG/client/SAUTR/${utr.value}"

    trait StubsForThisScenario {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenEnrolmentExistsForGroupId("bar", Arn("barArn"))
      givenEnrolmentExistsForGroupId("foo", Arn("fooArn"))
      givenDelegatedGroupIdsExistForTrust(utr)
      givenAgentCanBeAllocatedInIF(utr, arn)
      givenEnrolmentDeallocationSucceeds("foo", utr)
      givenEnrolmentDeallocationSucceeds("bar", utr)
      givenTrustEnrolmentAllocationSucceeds(utr, "bar")
      givenAdminUser("foo", "any")
    }

    "return 201 when the relationship exists and de-allocation of previous relationship fails" in new StubsForThisScenario {
      givenUserIsSubscribedClient(utr)
      givenDelegatedGroupIdsExistForTrust(utr, "zoo")
      givenEnrolmentExistsForGroupId("zoo", Arn("zooArn"))
      givenEnrolmentDeallocationFailsWith(502, "zoo", utr)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the UTR matches that of current Client user" in new StubsForThisScenario {
      givenUserIsSubscribedClient(utr)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the user is authenticated with Stride" in new StubsForThisScenario {
      givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-983283")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when there are no previous relationships to deallocate" in {
      givenUserIsSubscribedClient(utr)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistFor(utr)
      givenAgentCanBeAllocatedInIF(utr, arn)
      givenTrustEnrolmentAllocationSucceeds(utr, "bar")
      givenAdminUser("foo", "any")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when an agent tries to create a relationship" in {
      givenUserIsSubscribedAgent(arn)
      givenAgentCanBeAllocatedInIF(utr, arn)
      givenPrincipalGroupIdExistsFor(arn, "foo")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 423 Locked if there is a record in the lock repository" in {
      givenUserIsSubscribedClient(utr)
      await(recoveryLockRepository.insert(
        Lock(
          id =s"recovery-${arn.value}-${utr.value}",
          owner="86515a24-1a37-4a40-9117-4a117d8dd42e",
          expiryTime= DateTime.now().plusSeconds(5),
          timeCreated= DateTime.now().minusSeconds(5))))

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe LOCKED

    }

    "return 500 when ES1 is unavailable" in new StubsForThisScenario {
      givenUserIsSubscribedClient(utr)
      givenDelegatedGroupIdRequestFailsWith(503)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 500
    }

    "return 500 when ES8 is unavailable" in {
      givenUserIsSubscribedClient(utr)
      givenPrincipalUser(arn, "foo", userId = "user1")
      givenGroupInfo(groupId = "foo", agentCode = "bar")
      givenDelegatedGroupIdsNotExistForTrust(utr)
      givenAgentCanBeAllocatedInIF(utr, arn)
      givenEnrolmentAllocationFailsWith(503)(
        groupId = "foo",
        clientUserId = "user1",
        key = "HMRC-TERS-ORG",
        identifier = "SAUTR",
        value = utr.value,
        agentCode = "bar")
      givenAdminUser("foo", "user1")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 500
      (result.json \ "message").asOpt[String] shouldBe None
    }

    "return 500 when DES is unavailable" in {
      givenUserIsSubscribedClient(utr)
      givenDelegatedGroupIdsNotExistForTrust(utr)
      givenDesReturnsServiceUnavailable()

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 500
      (result.json \ "message").asOpt[String] shouldBe None
    }

    "return 500 if DES returns 404" in {
      givenUserIsSubscribedClient(utr)
      givenDelegatedGroupIdsNotExistForTrust(utr)
      givenAgentCanNotBeAllocatedInIF(status = 404)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 500
      (result.json \ "code").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_IF")
    }

    "return 403 for a client with a mismatched MtdItId" in {
      givenUserIsSubscribedClient(Utr("unmatched"))

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 403
    }

    "return 403 for a client with no client enrolments" in {
      givenUserHasNoClientEnrolments

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 403
    }

  }

  "DELETE /agent/:arn/service/HMRC-TERS-ORG/client/SAUTR/:utr" when {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-TERS-ORG/client/SAUTR/${utr.value}"

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

    "the relationship exists and the Utr matches that of current Agent user" should {

      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(utr, "bar")
        givenAgentCanBeDeallocatedInIF(utr, arn)
        givenEnrolmentDeallocationSucceeds("foo", utr)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
      }

      "send an audit event called ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          utr.value,
          "Utr",
          "HMRC-TERS-ORG",
          "Agent",
          "ggUserId-agent",
          "GovernmentGateway")
      }
    }

    "the relationship exists and the Utr matches that of current Client user" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(utr, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(utr, "bar")
        givenAgentCanBeDeallocatedInIF(utr, arn)
        givenEnrolmentDeallocationSucceeds("foo", utr)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          utr.value,
          "Utr",
          "HMRC-TERS-ORG",
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
        givenAgentIsAllocatedAndAssignedToClient(utr, "bar")
        givenAgentCanBeDeallocatedInIF(utr, arn)
        givenEnrolmentDeallocationSucceeds("foo", utr)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
      }

      "send the audit event HmrcRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyHmrcRemovedAgentServiceAuthorisation(
          arn.value,
          utr.value,
          "HMRC-TERS-ORG",
          "strideId-1234456",
          "PrivilegedApplication")
      }
    }

    "the relationship exists in ETMP and not exist in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(utr, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(utr, "clientGroupId")
        givenDelegatedGroupIdsNotExistForTrust(utr)
        givenAgentCanBeDeallocatedInIF(utr, arn)
        givenAdminUser("foo", "any")
      }

      "return 500" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 500
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          utr.value,
          "Utr",
          "HMRC-TERS-ORG",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship does not exist in either ETMP or in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(utr, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(vrn, "clientGroupId")
        givenDelegatedGroupIdsNotExistForTrust(utr)
        givenAgentHasNoActiveRelationshipInIF(utr, arn)
        givenAdminUser("foo", "any")
      }

      "return 500" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 500
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          utr.value,
          "Utr",
          "HMRC-TERS-ORG",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship does not exist in ETMP but does exist in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(utr, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(utr, "bar")
        givenAgentHasNoActiveRelationshipInIF(utr, arn)
        givenEnrolmentDeallocationSucceeds("foo", utr)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          utr.value,
          "Utr",
          "HMRC-TERS-ORG",
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
        givenAgentCanBeDeallocatedInIF(utr, arn)
      }

      "return 500" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 500
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
        givenPrincipalGroupIdExistsFor(utr, "clientGroupId")
        givenAgentIsAllocatedAndAssignedToClient(utr, "bar")
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

    "DES responds with 404" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(utr, "clientGroupId")
        givenAgentIsAllocatedAndAssignedToClient(utr, "bar")
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
        givenUserIsSubscribedClient(utr)
        givenAgentCanBeDeallocatedInIF(utr, arn)
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

  "GET /relationships/service/HMRC-TERS-ORG/client/SAUTR/:utr" should {

    val requestPath: String = s"/agent-client-relationships/relationships/service/HMRC-TERS-ORG/client/SAUTR/${utr.value}"

    def doRequest = doAgentGetRequest(requestPath)
    val req = FakeRequest()

    "find relationship and send back Json" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      getActiveRelationshipsViaClient(utr, arn)

      val result = doRequest
      result.status shouldBe 200

      (result.json \ "arn").get.as[String] shouldBe arn.value
      (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
    }

    "find relationship but filter out if the end date has been changed from 9999-12-31" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      getInactiveRelationshipViaClient(utr, arn.value)

      val result = doRequest
      result.status shouldBe 404
    }

    "find multiple relationships but filter out active and ended relationships" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      getSomeActiveRelationshipsViaClient(utr, arn.value, arn2.value, arn3.value)

      val result = doRequest
      result.status shouldBe 200
      (result.json \ "arn").get.as[String] shouldBe arn3.value
      (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
    }

    "return 404 when DES returns 404 relationship not found" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      getActiveRelationshipFailsWith(utr, status = 404)

      val result = doRequest
      result.status shouldBe 404
    }

    "return 404 when DES returns 400 (treated as relationship not found)" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      getActiveRelationshipFailsWith(utr, status = 400)

      val result = doRequest
      result.status shouldBe 404
    }
  }
}
