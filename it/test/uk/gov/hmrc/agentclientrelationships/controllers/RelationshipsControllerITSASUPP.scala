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

import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub

trait RelationshipsControllerITSASUPP {
  this: RelationshipsBaseControllerISpec
    with HipStub =>

  def relationshipControllerITSASUPPBehaviours(): Unit = {
    val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT-SUPP/client/MTDITID/${mtdItId.value}"

    def doRequest = doGetRequest(requestPath)

    "GET /agent/:arn/service/HMRC-MTD-IT-SUPP/client/MTDITID/:mtdItId" should {

      "return 404 when agent not allocated to client in es nor identifier not found in des" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistFor(mtdItSuppEnrolmentKey)
        givenNinoIsUnknownFor(mtdItId)
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        val result = doRequest
        result.status shouldBe 404
        (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
      }

      "return 404 when agent not allocated to client in es and no alt-itsa" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistFor(mtdItSuppEnrolmentKey)
        givenNinoIsKnownFor(mtdItId, nino)
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        val result = doRequest
        result.status shouldBe 404
        (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
      }

      "return 200 when agent not allocated to client in es but partial auth exists" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistFor(mtdItSuppEnrolmentKey)
        givenNinoIsKnownFor(mtdItId, nino)
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenAgentCanBeAllocated(mtdItId, arn)
        givenMTDITSUPPEnrolmentAllocationSucceeds(mtdItId, "bar")
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        val result = doRequest
        result.status shouldBe 200

        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.CreateRelationship,
          detail = Map(
            "agentReferenceNumber" -> arn.value,
            "credId" -> "any",
            "agentCode" -> "bar",
            "nino" -> nino.value,
            "saAgentRef" -> "foo",
            "service" -> "HMRC-MTD-IT-SUPP",
            "clientId" -> mtdItId.value,
            "clientIdType" -> "mtditid",
            "etmpRelationshipCreated" -> "true",
            "enrolmentDelegated" -> "true",
            "howRelationshipCreated" -> "Alt ITSA"
          ),
          tags = Map("transactionName" -> "create-relationship", "path" -> requestPath)
        )
      }
    }
  }
}
