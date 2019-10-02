package uk.gov.hmrc.agentrelationships.controllers

import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, CgtRef}

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

}
