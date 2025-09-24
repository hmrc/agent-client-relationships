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

package uk.gov.hmrc.agentclientrelationships.testOnly.controllers

import org.apache.pekko.Done
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.controllers.BaseControllerISpec
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.CbcId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Identifier
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.mongo.lock.Lock

import java.time.Instant
import java.time.ZoneOffset

trait TestOnlyRelationshipsControllerGenericBehaviours {
  this: BaseControllerISpec
    with HipStub =>

  def relationshipsControllerISpec(
    serviceId: String,
    clientId: TaxIdentifier,
    clientIdType: String
  ): Unit = {

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
  }

  def now = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime

  def isItsaNino(
    clientIdType: String,
    serviceId: String
  ): Boolean =
    clientIdType.toUpperCase == "NI" &&
      (serviceId == Service.MtdIt.id || serviceId == Service.MtdItSupp.id)

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

    s"PUT /test-only/agent/:arn/service/$serviceId/client/$clientIdType/:clientId" should {

      val requestPath: String = s"/test-only/agent/${arn.value}/service/$serviceId/client/$clientIdType/${clientId.value}"

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

      }

      "return 201 when the relationship exists and previous relationships too but ARNs not found" in new StubsForThisScenario {
        givenUserIsSubscribedClient(clientId)
        givenEnrolmentNotExistsForGroupId("zoo")
        givenEnrolmentDeallocationSucceeds("zoo", enrolmentKey)
        extraSetup(serviceId, clientIdType)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 201

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

      }

      "return 503 when ES1 is unavailable" in new StubsForThisScenario {
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
        result.status shouldBe 503

      }

      "return 503 when ES8 is unavailable" in {
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
        result.status shouldBe 503
        (result.json \ "message").asOpt[String] shouldBe Some("")

      }

      "return 502 when DES/IF is unavailable" in {
        givenUserIsSubscribedClient(clientId)
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKey)
        givenDesReturnsServiceUnavailable()
        givenReturnsServiceUnavailable()
        givenAdminUser("foo", "any")
        extraSetup(serviceId, clientIdType)

        val result = doAgentPutRequest(requestPath)
        result.status shouldBe 502
        result.body should include("returned 503")

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
        result.body should include("returned 404")

      }

    }
  }

  // noinspection ScalaStyle
  def relationshipsControllerDeleteISpec(
    serviceId: String,
    clientId: TaxIdentifier,
    clientIdType: String
  ): Unit = {

    s"DELETE /test-only/db/agent/:arn/service/$serviceId/client/$clientIdType/:clientId" should {

      val requestPath: String = s"/test-only/db/agent/${arn.value}/service/$serviceId/client/$clientIdType/$clientId"

      "return 404 for any call" in {

        await(
          repo.create(
            RelationshipCopyRecord(
              arn.value,
              EnrolmentKey(serviceId, Seq(Identifier(clientIdType, clientId.value)))
            )
          )
        ) shouldBe Done
        val result = doAgentDeleteRequest(requestPath)
        result.status shouldBe 404
      }
    }
  }

}
