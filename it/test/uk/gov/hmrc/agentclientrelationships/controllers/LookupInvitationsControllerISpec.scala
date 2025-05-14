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

import play.api.libs.json.Json
import play.api.test.Helpers.await
import play.api.test.Helpers.defaultAwaitTimeout
import uk.gov.hmrc.agentclientrelationships.model.Accepted
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.PartialAuth
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthRelationship
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentmtdidentifiers.model.Service.MtdIt
import uk.gov.hmrc.agentmtdidentifiers.model.Service.MtdItSupp
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentmtdidentifiers.model.MtdItId
import uk.gov.hmrc.domain.Nino

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LookupInvitationsControllerISpec
extends BaseControllerISpec {

  val invitationRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val partialAuthRepo: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]

  val invitationUrl = s"/agent-client-relationships/lookup-invitation"
  val invitationsUrl = "/agent-client-relationships/lookup-invitations"
  val testDate: LocalDate = LocalDate.now()
  val testTime: Instant = testDate.atStartOfDay(ZoneId.systemDefault()).toInstant
  val testArn: Arn = Arn("ARN1234567890")
  val testArn2: Arn = Arn("ARN1234567899")
  val testName = "testClientName"
  val testAgentName = "testAgentName"
  val testAgentEmail = "agent@email.com"
  val testNino: Nino = Nino("AB123456A")
  val testMtdItId1: MtdItId = MtdItId("XAIT0000111122")
  val testMtdItId2: MtdItId = MtdItId("XAIT0000111123")

  val itsaInvitation: Invitation = Invitation
    .createNew(
      testArn.value,
      MtdIt,
      testMtdItId1,
      testNino,
      testName,
      testAgentName,
      testAgentEmail,
      LocalDate.now(),
      Some("personal")
    )
    .copy(created = testTime, lastUpdated = testTime)
  val suppItsaInvitation: Invitation = Invitation
    .createNew(
      testArn2.value,
      MtdItSupp,
      testMtdItId1,
      testNino,
      testName,
      testAgentName,
      testAgentEmail,
      LocalDate.now(),
      Some("personal")
    )
    .copy(created = testTime, lastUpdated = testTime)
  val acceptedItsaInvitation: Invitation = Invitation
    .createNew(
      testArn.value,
      MtdIt,
      testMtdItId2,
      testNino,
      testName,
      testAgentName,
      testAgentEmail,
      LocalDate.now(),
      Some("personal")
    )
    .copy(
      created = testTime,
      lastUpdated = testTime,
      status = Accepted
    )

  val altItsaInvitation: Invitation = Invitation
    .createNew(
      testArn.value,
      MtdIt,
      testNino,
      testNino,
      testName,
      testAgentName,
      testAgentEmail,
      LocalDate.now(),
      Some("personal")
    )
    .copy(
      created = testTime,
      lastUpdated = testTime,
      status = PartialAuth
    )
  val partialAuth: PartialAuthRelationship = PartialAuthRelationship(
    testTime,
    testArn.value,
    MtdIt.id,
    testNino.value,
    active = true,
    testTime
  )

  s"GET $invitationsUrl" should {
    "return BadRequest" when {
      "query has no parameters" in {
        givenAuditConnector()
        givenAuthorised()

        val result = doGetRequest(invitationsUrl)

        result.status shouldBe 400
      }
    }
    "return NotFound" when {
      "query matches nothing" in {
        givenAuditConnector()
        givenAuthorised()

        val result = doGetRequest(invitationsUrl + s"?arn=${testArn.value}")

        result.status shouldBe 404
      }
    }
    "return OK with invitations" when {
      "queried with arn that matches some data" in {
        givenAuditConnector()
        givenAuthorised()

        await(invitationRepo.collection.insertOne(itsaInvitation).toFuture())
        await(invitationRepo.collection.insertOne(suppItsaInvitation).toFuture())
        await(invitationRepo.collection.insertOne(acceptedItsaInvitation).toFuture())

        val result = doGetRequest(invitationsUrl + s"?arn=${testArn.value}")

        result.status shouldBe 200
        result.json shouldBe Json.toJson(Seq(itsaInvitation, acceptedItsaInvitation))
      }
      "queried with clientId that matches some data" in {
        givenAuditConnector()
        givenAuthorised()

        await(invitationRepo.collection.insertOne(itsaInvitation).toFuture())
        await(invitationRepo.collection.insertOne(suppItsaInvitation).toFuture())
        await(invitationRepo.collection.insertOne(acceptedItsaInvitation).toFuture())

        val result = doGetRequest(invitationsUrl + s"?clientIds=${testMtdItId1.value}")

        result.status shouldBe 200
        result.json shouldBe Json.toJson(Seq(itsaInvitation, suppItsaInvitation))
      }
      "queried with multiple clientIds that match some data" in {
        givenAuditConnector()
        givenAuthorised()

        await(invitationRepo.collection.insertOne(itsaInvitation).toFuture())
        await(invitationRepo.collection.insertOne(suppItsaInvitation).toFuture())
        await(invitationRepo.collection.insertOne(acceptedItsaInvitation).toFuture())

        val result = doGetRequest(invitationsUrl + s"?clientIds=${testMtdItId1.value}&clientIds=${testMtdItId2.value}")

        result.status shouldBe 200
        result.json shouldBe Json.toJson(
          Seq(
            itsaInvitation,
            suppItsaInvitation,
            acceptedItsaInvitation
          )
        )
      }
      "queried with clientId and services that match some data" in {
        givenAuditConnector()
        givenAuthorised()

        await(invitationRepo.collection.insertOne(itsaInvitation).toFuture())
        await(invitationRepo.collection.insertOne(suppItsaInvitation).toFuture())
        await(invitationRepo.collection.insertOne(acceptedItsaInvitation).toFuture())

        val result = doGetRequest(
          invitationsUrl + s"?services=${MtdIt.id}&services=${MtdItSupp.id}&clientIds=${testMtdItId1.value}"
        )

        result.status shouldBe 200
        result.json shouldBe Json.toJson(Seq(itsaInvitation, suppItsaInvitation))
      }
      "queried with multiple params that match some data" in {
        givenAuditConnector()
        givenAuthorised()

        await(invitationRepo.collection.insertOne(itsaInvitation).toFuture())
        await(invitationRepo.collection.insertOne(suppItsaInvitation).toFuture())
        await(invitationRepo.collection.insertOne(acceptedItsaInvitation).toFuture())

        val result = doGetRequest(
          invitationsUrl + s"?arn=${testArn.value}&services=${MtdIt.id}&services=${MtdItSupp.id}" +
            s"&clientIds=${testMtdItId1.value}&clientIds=${testMtdItId2.value}"
        )

        result.status shouldBe 200
        result.json shouldBe Json.toJson(Seq(itsaInvitation, acceptedItsaInvitation))
      }
    }
    "return OK with invitations combined with partial auths" when {
      "queried for alt itsa invitations before invitation docs expire" in {
        givenAuditConnector()
        givenAuthorised()

        await(invitationRepo.collection.insertOne(altItsaInvitation).toFuture())
        await(partialAuthRepo.collection.insertOne(partialAuth).toFuture())

        val result = doGetRequest(
          invitationsUrl + s"?arn=${testArn.value}&services=${MtdIt.id}" +
            s"&clientIds=${testNino.value}&status=$PartialAuth"
        )

        result.status shouldBe 200
        result.json shouldBe Json.toJson(Seq(altItsaInvitation))
      }
      "queried for alt itsa invitations after invitation docs expire" in {
        givenAuditConnector()
        givenAuthorised()

        await(partialAuthRepo.collection.insertOne(partialAuth).toFuture())

        val result = doGetRequest(
          invitationsUrl + s"?arn=${testArn.value}&services=${MtdIt.id}" +
            s"&clientIds=${testNino.value}&status=$PartialAuth"
        )

        result.status shouldBe 200
        result.json shouldBe Json.toJson(Seq(partialAuth.asInvitation))
      }
      "queried for all agent invitations" in {
        givenAuditConnector()
        givenAuthorised()

        await(invitationRepo.collection.insertOne(itsaInvitation).toFuture())
        await(partialAuthRepo.collection.insertOne(partialAuth).toFuture())

        val result = doGetRequest(invitationsUrl + s"?arn=${testArn.value}")

        result.status shouldBe 200
        result.json shouldBe Json.toJson(Seq(itsaInvitation, partialAuth.asInvitation))
      }
    }
  }

  s"GET $invitationUrl" should {
    "return NotFound" when {
      "queried with invitationId that does not match any data" in {
        givenAuditConnector()
        givenAuthorised()

        val result = doGetRequest(invitationUrl + s"/${itsaInvitation.invitationId}")

        result.status shouldBe 404
      }
    }
    "return OK with invitations" when {
      "queried with invitationId that matches some data" in {
        givenAuditConnector()
        givenAuthorised()

        await(invitationRepo.collection.insertOne(itsaInvitation).toFuture())
        await(invitationRepo.collection.insertOne(suppItsaInvitation).toFuture())

        val result = doGetRequest(invitationUrl + s"/${itsaInvitation.invitationId}")

        result.status shouldBe 200
        result.json shouldBe Json.toJson(itsaInvitation)
      }
    }
  }

}
