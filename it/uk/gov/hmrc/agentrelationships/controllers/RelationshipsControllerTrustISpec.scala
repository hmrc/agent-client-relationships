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

package uk.gov.hmrc.agentrelationships.controllers

import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.agentclientrelationships.repository.{RelationshipCopyRecord, SyncStatus}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Utr}

//noinspection ScalaStyle
class RelationshipsControllerTrustISpec extends RelationshipsControllerISpec {

  "GET  /agent/:arn/service/HMRC-TERS-ORG/client/SAUTR/:utr" should {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-TERS-ORG/client/SAUTR/${utr.value}"

    def doRequest = doAgentGetRequest(requestPath)

    //HAPPY PATH :-)

    "return 200 when relationship exists in es" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(utr, "bar")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> utr.value, "clientIdentifierType" -> saUtrType)

      await(query()) shouldBe empty
      val result = await(doRequest)
      result.status shouldBe 200
      await(query()) shouldBe empty
    }

    //UNHAPPY PATHS

    "return 404 when credentials are not found in es" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(utr, "bar")
      givenDelegatedGroupIdsNotExistForKey(s"HMRC-TERS-ORG~SAUTR~${utr.value}")

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent code is not found in ugs" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoNotExists("foo")
      givenDelegatedGroupIdsExistFor(utr, Set("foo"))
      givenDelegatedGroupIdsNotExistForKey(s"HMRC-TERS-ORG~SAUTR~${utr.value}")

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    //FAILURE CASES

    "return 502 when ES1/principal returns 5xx" in {
      givenPrincipalGroupIdRequestFailsWith(500)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(utr, "bar")

      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 502 when UGS returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(500)
      givenAgentIsAllocatedAndAssignedToClient(utr, "bar")

      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 502 when ES1/delegated returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")

      givenDelegatedGroupIdRequestFailsWith(500)
      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 400 when ES1/principal returns 4xx" in {
      givenPrincipalGroupIdRequestFailsWith(400)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(utr, "bar")

      val result = await(doRequest)
      result.status shouldBe 400
    }

    "return 400 when UGS returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(400)
      givenAgentIsAllocatedAndAssignedToClient(utr, "bar")

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

  val relationshipCopiedSuccessfully = RelationshipCopyRecord(
    arn.value,
    utr.value,
    saUtrType,
    syncToETMPStatus = Some(SyncStatus.Success),
    syncToESStatus = Some(SyncStatus.Success))

  "PUT /agent/:arn/service/HMRC-TERS-ORG/client/SAUTR/:utr" should {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-TERS-ORG/client/SAUTR/${utr.value}"

    trait StubsForThisScenario {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenEnrolmentExistsForGroupId("bar", Arn("barArn"))
      givenEnrolmentExistsForGroupId("foo", Arn("fooArn"))
      givenDelegatedGroupIdsExistForTrust(utr)
      givenAgentCanBeAllocatedInDes(utr, arn)
      givenEnrolmentDeallocationSucceeds("foo", utr)
      givenEnrolmentDeallocationSucceeds("bar", utr)
      givenTrustEnrolmentAllocationSucceeds(utr, "bar")
    }

    "return 201 when the relationship exists and the Arn matches that of current Agent user" in new StubsForThisScenario {
      givenUserIsSubscribedAgent(arn)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and de-allocation of previous relationship fails" in new StubsForThisScenario {
      givenUserIsSubscribedAgent(arn)
      givenDelegatedGroupIdsExistForTrust(utr, "zoo")
      givenEnrolmentExistsForGroupId("zoo", Arn("zooArn"))
      givenEnrolmentDeallocationFailsWith(502, "zoo", utr)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the UTR matches that of current Client user" in new StubsForThisScenario {
      givenUserIsSubscribedClient(utr)

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
      givenDelegatedGroupIdsExistForTrust(utr, "zoo")
      givenEnrolmentNotExistsForGroupId("zoo")
      givenEnrolmentDeallocationSucceeds("zoo", utr)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when there are no previous relationships to deallocate" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistFor(utr)
      givenAgentCanBeAllocatedInDes(utr, arn)
      givenTrustEnrolmentAllocationSucceeds(utr, "bar")

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
      givenDelegatedGroupIdsNotExistForTrust(utr)
      givenAgentCanBeAllocatedInDes(utr, arn)
      givenEnrolmentAllocationFailsWith(503)(
        groupId = "foo",
        clientUserId = "user1",
        key = "HMRC-TERS-ORG",
        identifier = "SAUTR",
        value = utr.value,
        agentCode = "bar")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 502
      (result.json \ "message").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_ES")
    }

    "return 502 when DES is unavailable" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForTrust(utr)
      givenDesReturnsServiceUnavailable()

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 502
      (result.json \ "message").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_DES")
    }

    "return 404 if DES returns 404" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForTrust(utr)
      givenAgentCanNotBeAllocatedInDes(status = 404)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 404
      (result.json \ "code").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_DES")
    }

    /**
      * Client's Unhappy paths
      */
    "return 403 for a client with a mismatched MtdItId" in {
      givenUserIsSubscribedClient(Utr("unmatched"))

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }

    "return 403 for a client with no client enrolments" in {
      givenUserHasNoClientEnrolments

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }

  }
}
