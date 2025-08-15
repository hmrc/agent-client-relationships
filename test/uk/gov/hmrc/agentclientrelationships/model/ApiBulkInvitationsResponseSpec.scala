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

package models

import play.api.libs.json._
import uk.gov.hmrc.agentclientrelationships.model.Accepted
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiBulkInvitationResponse
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiBulkInvitationsResponse
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.MtdItId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.domain.Nino

import java.time.Instant
import java.time.LocalDate

class ApiBulkInvitationsResponseSpec
extends UnitSpec {

  val testArn: Arn = Arn("ARN1234567890")
  val testName = "testClientName"
  val testAgentName = "test Agent Name"
  val testNormalizedAgentName = "testagentname"
  val testClientName = "testClientName"
  val testAgentEmail = "agent@email.com"
  val testNino: Nino = Nino("AB123456A")
  val testMtdItId: MtdItId = MtdItId("XAIT0000111122")
  val testUId: String = "testUID"
  val now = Instant.now
  val expiry = LocalDate.now.plusDays(14)

  val invitation1 = Invitation
    .createNew(
      testArn.value,
      Service.MtdIt,
      testMtdItId,
      testNino,
      testClientName,
      testAgentName,
      testAgentEmail,
      expiry,
      None
    )
    .copy(status = Accepted)

  val expectedApiBulkInvitationResponse1 = ApiBulkInvitationResponse(
    created = invitation1.created,
    service = invitation1.service,
    status = invitation1.status,
    expiresOn = invitation1.expiryDate,
    invitationId = invitation1.invitationId,
    lastUpdated = invitation1.lastUpdated
  )

  val invitation2 = Invitation
    .createNew(
      testArn.value,
      Service.Vat,
      testMtdItId,
      testNino,
      testClientName,
      testAgentName,
      testAgentEmail,
      expiry,
      None
    )

  val expectedApiBulkInvitationResponse2 = ApiBulkInvitationResponse(
    created = invitation2.created,
    service = invitation2.service,
    status = invitation2.status,
    expiresOn = invitation2.expiryDate,
    invitationId = invitation2.invitationId,
    lastUpdated = invitation2.lastUpdated
  )

  val expectedApiBulkInvitationsResponse1 = ApiBulkInvitationsResponse(
    uid = testUId,
    normalizedAgentName = testNormalizedAgentName,
    invitations = Seq(expectedApiBulkInvitationResponse1)
  )

  val expectedApiBulkInvitationsResponse2 = ApiBulkInvitationsResponse(
    uid = testUId,
    normalizedAgentName = testNormalizedAgentName,
    invitations = Seq(expectedApiBulkInvitationResponse1, expectedApiBulkInvitationResponse1)
  )

  "ApiBulkInvitationResponse" should {

    "serialize and deserialize correctly" in {

      val json = Json.toJson(expectedApiBulkInvitationResponse1)
      val parsed = json.validate[ApiBulkInvitationResponse]

      parsed.isSuccess shouldBe true
      parsed.get shouldBe expectedApiBulkInvitationResponse1
    }

    "correctly construct from Invitation" in {
      ApiBulkInvitationResponse.createApiBulkInvitationResponse(invitation1) shouldBe expectedApiBulkInvitationResponse1
    }
  }

  "ApiBulkInvitationsResponse" should {

    "serialize and deserialize correctly" in {

      val json = Json.toJson(expectedApiBulkInvitationsResponse1)
      val parsed = json.validate[ApiBulkInvitationsResponse]

      parsed.isSuccess shouldBe true
      parsed.get shouldBe expectedApiBulkInvitationsResponse1
    }

    "correctly construct from a sequence of Invitation" in {

      val result = ApiBulkInvitationsResponse.createApiBulkInvitationsResponse(
        invitations = Seq(invitation1, invitation2),
        uid = testUId,
        normalizedAgentName = testNormalizedAgentName
      )
      result.uid shouldBe testUId
      result.normalizedAgentName shouldBe testNormalizedAgentName
      result.invitations.length shouldBe 2
      result.invitations.map(_.invitationId) should contain allOf (invitation1.invitationId, invitation2.invitationId)
    }
  }

}
