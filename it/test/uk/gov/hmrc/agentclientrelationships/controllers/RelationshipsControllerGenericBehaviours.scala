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
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.identifiers.CbcId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.repository.DeleteRecord
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.domain.TaxIdentifier

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
              enrolmentKey,
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
