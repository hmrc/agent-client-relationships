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

package uk.gov.hmrc.agentclientrelationships.model.invitation

import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.UNPROCESSABLE_ENTITY
import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiFailureResponse._
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

class ApiFailureResponseSpec
extends UnitSpec {

  ".getResult" should {

    "convert the model to a HTTP result with expected status and body" when {

      "the model is UnsupportedService" in {
        val result = UnsupportedService.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "SERVICE_NOT_SUPPORTED")
      }

      "the model is UnsupportedClientType" in {
        val result = UnsupportedClientType.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "CLIENT_TYPE_NOT_SUPPORTED")
      }

      "the model is ClientIdInvalidFormat" in {
        val result = ClientIdInvalidFormat.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "CLIENT_ID_FORMAT_INVALID")
      }

      "the model is PostcodeFormatInvalid" in {
        val result = PostcodeFormatInvalid.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "POSTCODE_FORMAT_INVALID")
      }

      "the model is VatRegDateFormatInvalid" in {
        val result = VatRegDateFormatInvalid.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "VAT_REG_DATE_FORMAT_INVALID")
      }

      "the model is PostcodeDoesNotMatch" in {
        val result = PostcodeDoesNotMatch.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "POSTCODE_DOES_NOT_MATCH")
      }

      "the model is VatRegDateDoesNotMatch" in {
        val result = VatRegDateDoesNotMatch.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "VAT_REG_DATE_DOES_NOT_MATCH")
      }

      "the model is VatClientInsolvent" in {
        val result = VatClientInsolvent.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "VAT_CLIENT_INSOLVENT")
      }

      "the model is ClientRegistrationNotFound" in {
        val result = ClientRegistrationNotFound.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "CLIENT_REGISTRATION_NOT_FOUND")
      }

      "the model is AgentSuspended" in {
        val result = AgentSuspended.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "AGENT_SUSPENDED")
      }

      "the model is InvitationNotFound" in {
        val result = InvitationNotFound.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "INVITATION_NOT_FOUND")
      }

      "the model is RelationshipNotFound" in {
        val result = RelationshipNotFound.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "RELATIONSHIP_NOT_FOUND")
      }

      "the model is InvalidPayload" in {
        val result = InvalidPayload.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "INVALID_PAYLOAD")
      }

      "the model is DuplicateAuthorisationRequest with an invitation ID" in {
        val result = DuplicateAuthorisationRequest(Some("ABC123")).getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "DUPLICATE_AUTHORISATION_REQUEST", "invitationId" -> "ABC123")
      }

      "the model is DuplicateAuthorisationRequest without an invitation ID" in {
        val result = DuplicateAuthorisationRequest(None).getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "DUPLICATE_AUTHORISATION_REQUEST")
      }

      "the model is AlreadyAuthorised" in {
        val result = AlreadyAuthorised.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "ALREADY_AUTHORISED")
      }

      "the model is NoPermissionOnAgency" in {
        val result = NoPermissionOnAgency.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "NO_PERMISSION_ON_AGENCY")
      }

      "the model is InvalidInvitationStatus" in {
        val result = InvalidInvitationStatus.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "INVALID_INVITATION_STATUS")
      }

      "the model is ApiInternalServerError" in {
        val result = ApiInternalServerError.getResult
        status(result) shouldBe INTERNAL_SERVER_ERROR
        contentAsJson(result) shouldBe Json.obj("code" -> "INTERNAL_SERVER_ERROR")
      }

      "the model is KnownFactDoesNotMatch" in {
        val result = KnownFactDoesNotMatch.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "KNOWN_FACT_DOES_NOT_MATCH")
      }

      "the model is ClientInsolvent" in {
        val result = ClientInsolvent.getResult
        status(result) shouldBe UNPROCESSABLE_ENTITY
        contentAsJson(result) shouldBe Json.obj("code" -> "CLIENT_INSOLVENT")
      }
    }
  }
}
