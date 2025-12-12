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

import play.api.http.Status.NOT_FOUND
import play.api.libs.json.Json.toJson
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.api.ApiCheckRelationshipRequest
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiFailureResponse.ErrorBody
import uk.gov.hmrc.agentclientrelationships.stubs._
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service._

class ApiCheckRelationshipControllerISpec
extends BaseControllerISpec
with ClientDetailsStub
with CitizenDetailsStub
with HipStub
with TestData {

  override def additionalConfig: Map[String, Any] = Map(
    "hip.enabled" -> true
  )

  val controller: ApiCheckRelationshipController = app.injector.instanceOf[ApiCheckRelationshipController]

  val url: String = routes.ApiCheckRelationshipController.checkRelationship(arn).url

  s"POST $url" should {
    // ITSA is a special case because it converts nino from request to mtditid
    "return 204 when the relationship is found for ITSA" in {
      givenAuditConnector()
      givenAgentRecordFound(arn, agentRecordResponse)
      givenMtdItsaBusinessDetailsExists(
        nino,
        mtdItId,
        testPostcode
      )
      givenAgentGroupExistsFor("groupId")
      givenAdminUser("groupId", "userId")
      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "groupId")
      givenDelegatedGroupIdsExistFor(EnrolmentKey(HMRCMTDIT, mtdItId), Set("groupId"))
      givenUserAuthorised()

      val testData = ApiCheckRelationshipRequest(
        HMRCMTDIT,
        nino.value,
        testPostcode
      )
      val result = doAgentPostRequest(url, toJson(testData).toString())
      result.status shouldBe 204
      result.body shouldBe ""
    }
    "return 204 when the relationship is found for VAT" in {
      givenAuditConnector()
      givenAgentRecordFound(arn, agentRecordResponse)
      givenVatCustomerInfoExists(
        vrn.value,
        testVatRegDate
      )
      givenAgentGroupExistsFor("groupId")
      givenAdminUser("groupId", "userId")
      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "groupId")
      givenDelegatedGroupIdsExistFor(EnrolmentKey(HMRCMTDVAT, vrn), Set("groupId"))
      givenUserAuthorised()

      val testData = ApiCheckRelationshipRequest(
        HMRCMTDVAT,
        vrn.value,
        testVatRegDate
      )
      val result = doAgentPostRequest(url, toJson(testData).toString())
      result.status shouldBe 204
      result.body shouldBe ""
    }
    "return 422 with AGENT_SUSPENDED when agent is suspended" in {
      givenAuditConnector()
      givenAgentRecordFound(arn, suspendedAgentRecordResponse)
      givenUserAuthorised()

      val testData = ApiCheckRelationshipRequest(
        HMRCMTDIT,
        nino.value,
        testPostcode
      )
      val result = doAgentPostRequest(url, toJson(testData).toString())
      result.status shouldBe 422
      result.json shouldBe toJson(ErrorBody("AGENT_SUSPENDED"))
    }
    "return 422 with CLIENT_REGISTRATION_NOT_FOUND when client details not found" in {
      givenAuditConnector()
      givenAgentRecordFound(arn, agentRecordResponse)
      givenItsaBusinessDetailsError(nino, 404)
      givenUserAuthorised()
      givenCitizenDetailsError(nino, NOT_FOUND)

      val testData = ApiCheckRelationshipRequest(
        HMRCMTDIT,
        nino.value,
        testPostcode
      )
      val result = doAgentPostRequest(url, toJson(testData).toString())
      result.status shouldBe 422
      result.json shouldBe toJson(ErrorBody("CLIENT_REGISTRATION_NOT_FOUND"))
    }
    "return 422 with KNOWN_FACT_DOES_NOT_MATCH when known fact is not found" in {
      givenAuditConnector()
      givenAgentRecordFound(arn, agentRecordResponse)
      givenMtdItsaBusinessDetailsExists(
        nino = nino,
        mtdId = mtdItId,
        postCode = "Z11 11Z"
      )
      givenUserAuthorised()

      val testData = ApiCheckRelationshipRequest(
        HMRCMTDIT,
        nino.value,
        testPostcode
      )
      val result = doAgentPostRequest(url, toJson(testData).toString())
      result.status shouldBe 422
      result.json shouldBe toJson(ErrorBody("KNOWN_FACT_DOES_NOT_MATCH"))
    }
    "return 422 with CLIENT_INSOLVENT when client is insolvent" in {
      givenAuditConnector()
      givenAgentRecordFound(arn, agentRecordResponse)
      givenVatCustomerInfoExists(
        vrn.value,
        testVatRegDate,
        isInsolvent = true
      )
      givenUserAuthorised()

      val testData = ApiCheckRelationshipRequest(
        HMRCMTDVAT,
        vrn.value,
        testVatRegDate
      )
      val result = doAgentPostRequest(url, toJson(testData).toString())
      result.status shouldBe 422
      result.json shouldBe toJson(ErrorBody("CLIENT_INSOLVENT"))
    }
    "return 422 with RELATIONSHIP_NOT_FOUND when relationship is not found" in {
      givenAuditConnector()
      givenAgentRecordFound(arn, agentRecordResponse)
      givenMtdItsaBusinessDetailsExists(
        nino,
        mtdItId,
        testPostcode
      )
      givenAgentGroupExistsFor("groupId")
      givenAdminUser("groupId", "userId")
      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "groupId")
      givenDelegatedGroupIdsNotExistFor(EnrolmentKey(HMRCMTDIT, mtdItId))
      givenNinoItsaBusinessDetailsExists(
        mtdItId,
        nino,
        testPostcode
      )
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
      givenUserAuthorised()

      val testData = ApiCheckRelationshipRequest(
        HMRCMTDIT,
        nino.value,
        testPostcode
      )
      val result = doAgentPostRequest(url, toJson(testData).toString())
      result.status shouldBe 422
      result.json shouldBe toJson(ErrorBody("RELATIONSHIP_NOT_FOUND"))
    }
    "return 500 when relationship check fails unexpectedly" in {
      givenAuditConnector()
      givenAgentRecordFound(arn, agentRecordResponse)
      givenMtdItsaBusinessDetailsExists(
        nino,
        mtdItId,
        testPostcode
      )
      givenAgentGroupExistsFor("groupId")
      givenAdminUser("groupId", "userId")
      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "groupId")
      givenDelegatedGroupIdRequestFailsWith(EnrolmentKey(HMRCMTDIT, mtdItId), 500)
      givenUserAuthorised()

      val testData = ApiCheckRelationshipRequest(
        HMRCMTDIT,
        nino.value,
        testPostcode
      )
      val result = doAgentPostRequest(url, toJson(testData).toString())

      result.status shouldBe 500
      result.body shouldBe """{"statusCode":500,"message":""}"""
    }
  }

}
