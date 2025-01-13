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

package uk.gov.hmrc.agentclientrelationships.controllers

import play.api.test.FakeRequest
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthRelationship
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.stubs.HIPAgentClientRelationshipStub
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP}
import uk.gov.hmrc.domain.SaAgentReference

import java.time.Instant

class ItsaPostSignupControllerISpec
    extends RelationshipsBaseControllerISpec
    with TestData
    with HIPAgentClientRelationshipStub {

  val testUrl = s"/agent-client-relationships/itsa-post-signup/create-relationship/$nino"

  def mongoPartialAuthRepo: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]

  s"POST $testUrl" should {
    s"return 201 Created when partial-auth exists for service $HMRCMTDIT and client is signed up to ITSA" in {

      val fakeRequest = FakeRequest("POST", testUrl)

      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
      givenAgentGroupExistsFor("foo")
      givenAdminUser("foo", "any")
      givenAgentCanBeAllocated(mtdItId, arn)
      givenDelegatedGroupIdsNotExistFor(mtdItEnrolmentKey)
      givenEnrolmentAllocationSucceeds("foo", "any", mtdItEnrolmentKey, "NQJUEJCWT14")
      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
      givenDelegatedGroupIdsNotExistFor(mtdItSuppEnrolmentKey)
      givenCacheRefresh(arn, 404)

      mongoPartialAuthRepo.collection
        .insertOne(PartialAuthRelationship(Instant.now, arn.value, HMRCMTDIT, nino.value, active = true, Instant.now))
        .toFuture()
        .futureValue

      val result = doAgentPostRequest(testUrl, "")

      result.status shouldBe 201
      result.body shouldBe s"""{"service":"$HMRCMTDIT"}"""

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn"                     -> arn.value,
          "Journey"                 -> "PartialAuth",
          "clientId"                -> mtdItId.value,
          "service"                 -> HMRCMTDIT,
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated"      -> "true",
          "nino"                    -> nino.value,
          "clientIdType"            -> "mtditid",
          "CESARelationship"        -> ""
        ),
        tags = Map("transactionName" -> "create-relationship", "path" -> testUrl)
      )

    }

    s"return 201 Created when partial-auth exists for service $HMRCMTDITSUPP and client is signed up to ITSA" in {

      val fakeRequest = FakeRequest("POST", testUrl)

      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
      givenAgentGroupExistsFor("foo")
      givenAdminUser("foo", "any")
      givenAgentCanBeAllocated(mtdItId, arn)
      givenDelegatedGroupIdsNotExistFor(mtdItSuppEnrolmentKey)
      givenEnrolmentAllocationSucceeds("foo", "any", mtdItSuppEnrolmentKey, "NQJUEJCWT14")
      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
      givenDelegatedGroupIdsNotExistFor(mtdItEnrolmentKey)
      givenCacheRefresh(arn, 404)

      mongoPartialAuthRepo.collection
        .insertOne(
          PartialAuthRelationship(Instant.now, arn.value, HMRCMTDITSUPP, nino.value, active = true, Instant.now)
        )
        .toFuture()
        .futureValue

      val result = doAgentPostRequest(testUrl, "")

      result.status shouldBe 201
      result.body shouldBe s"""{"service":"$HMRCMTDITSUPP"}"""

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn"                     -> arn.value,
          "Journey"                 -> "PartialAuth",
          "clientId"                -> mtdItId.value,
          "service"                 -> HMRCMTDITSUPP,
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated"      -> "true",
          "nino"                    -> nino.value,
          "clientIdType"            -> "mtditid",
          "CESARelationship"        -> ""
        ),
        tags = Map("transactionName" -> "create-relationship", "path" -> testUrl)
      )

    }

    "return 201 Created when no partial-auth exists but there is a legacy SA relationship and the client is signed up to ITSA" in {

      val fakeRequest = FakeRequest("POST", testUrl)

      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenPartialAuthNotExistsFor(arn, nino)

      givenClientHasRelationshipWithAgentInCESA(nino, "1234")
      givenArnIsKnownFor(arn, SaAgentReference("1234"))

      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
      givenAgentGroupExistsFor("foo")
      givenAdminUser("foo", "any")
      givenAgentCanBeAllocated(mtdItId, arn)
      givenDelegatedGroupIdsNotExistFor(mtdItEnrolmentKey)
      givenEnrolmentAllocationSucceeds("foo", "any", mtdItEnrolmentKey, "NQJUEJCWT14")
      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
      givenDelegatedGroupIdsNotExistFor(mtdItSuppEnrolmentKey)
      givenCacheRefresh(arn, 404)

      val result = doAgentPostRequest(testUrl, "")

      result.status shouldBe 201
      result.body shouldBe s"""{"service":"$HMRCMTDIT"}"""

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn"              -> arn.value,
          "partialAuth"      -> "",
          "saAgentRef"       -> "1234",
          "nino"             -> nino.value,
          "CESARelationship" -> "true"
        ),
        tags = Map("transactionName" -> "check-cesa", "path" -> testUrl)
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn"                     -> arn.value,
          "Journey"                 -> "CopyExistingCESARelationship",
          "clientId"                -> mtdItId.value,
          "service"                 -> HMRCMTDIT,
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated"      -> "true",
          "nino"                    -> nino.value,
          "clientIdType"            -> "mtditid",
          "CESARelationship"        -> "true"
        ),
        tags = Map("transactionName" -> "create-relationship", "path" -> testUrl)
      )

    }

    "return 404 Not Found when client is not signed up" in {

      val fakeRequest = FakeRequest("POST", testUrl)

      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)
      givenMtdItIdIsUnKnownFor(nino)

      val result = doAgentPostRequest(testUrl, "")

      result.status shouldBe 404
      result.body shouldBe "itsa-post-signup create relationship failed: no MTDITID found for nino"
    }

    "return 201 Created when a legacy SA relationship exists and also MTD-SUPP. Legacy SA relationship takes precedence over MTD-SUPP" in {

      val fakeRequest = FakeRequest("POST", testUrl)

      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenPartialAuthNotExistsFor(arn, nino)

      givenClientHasRelationshipWithAgentInCESA(nino, "1234")
      givenArnIsKnownFor(arn, SaAgentReference("1234"))

      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
      givenAgentGroupExistsFor("foo")
      givenAdminUser("foo", "any")
      givenAgentCanBeAllocated(mtdItId, arn)
      givenDelegatedGroupIdsNotExistFor(mtdItEnrolmentKey)
      givenEnrolmentAllocationSucceeds("foo", "any", mtdItEnrolmentKey, "NQJUEJCWT14")
      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
      givenDelegatedGroupIdsExistFor(mtdItSuppEnrolmentKey, Set("foo"))
      givenEnrolmentDeallocationSucceeds("foo", mtdItSuppEnrolmentKey)
      givenAgentCanBeDeallocated(mtdItId, arn)

      givenCacheRefresh(arn, 404)

      val result = doAgentPostRequest(testUrl, "")

      result.status shouldBe 201
      result.body shouldBe s"""{"service":"$HMRCMTDIT"}"""

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn"              -> arn.value,
          "partialAuth"      -> "",
          "saAgentRef"       -> "1234",
          "nino"             -> nino.value,
          "CESARelationship" -> "true"
        ),
        tags = Map("transactionName" -> "check-cesa", "path" -> testUrl)
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn"                     -> arn.value,
          "Journey"                 -> "CopyExistingCESARelationship",
          "clientId"                -> mtdItId.value,
          "service"                 -> HMRCMTDIT,
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated"      -> "true",
          "nino"                    -> nino.value,
          "clientIdType"            -> "mtditid",
          "CESARelationship"        -> "true"
        ),
        tags = Map("transactionName" -> "create-relationship", "path" -> testUrl)
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation,
        detail = Map(
          "agentReferenceNumber" -> arn.value,
          "clientId"             -> mtdItId.value,
          "service"              -> HMRCMTDITSUPP,
          "deleteStatus"         -> "success"
        ),
        tags = Map("transactionName" -> "client terminated agent:service authorisation", "path" -> testUrl)
      )

    }

    "return 404 Not Found when neither partial-auth nor legacy SA relationship exists" in {

      val fakeRequest = FakeRequest("POST", testUrl)

      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenPartialAuthNotExistsFor(arn, nino)

      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)

      val result = doAgentPostRequest(testUrl, "")

      result.status shouldBe 404
      result.body shouldBe "itsa-post-signup create relationship failed: no partial-auth and no legacy SA relationship"

    }

    "return 500 InternalServerError when there is a problem in ETMP" in {

      val fakeRequest = FakeRequest("POST", testUrl)

      givenAuditConnector()
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
      givenAgentGroupExistsFor("foo")
      givenAdminUser("foo", "any")
      givenAgentCanNotBeAllocated(503)

      mongoPartialAuthRepo.collection
        .insertOne(PartialAuthRelationship(Instant.now, arn.value, HMRCMTDIT, nino.value, active = true, Instant.now))
        .toFuture()
        .futureValue

      val result = doAgentPostRequest(testUrl, "")

      result.status shouldBe 500
      result.body shouldBe """{"statusCode":500,"message":"RELATIONSHIP_CREATE_FAILED_IF"}"""

    }
  }

}
