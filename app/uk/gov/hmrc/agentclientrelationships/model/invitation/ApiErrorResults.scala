/*
 * Copyright 2023 HM Revenue & Customs
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

import play.api.libs.json.Json.toJson
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.Writes
import play.api.mvc.Result
import play.api.mvc.Results._

object ApiErrorResults {

  case class ErrorBody(
    code: String,
    invitationId: Option[String] = None
  )

  implicit val errorBodyWrites: Writes[ErrorBody] =
    new Writes[ErrorBody] {
      override def writes(body: ErrorBody): JsValue = body.invitationId
        .map(id =>
          Json.obj(
            "code" -> body.code,
            "invitationId" -> id
          )
        )
        .getOrElse(Json.obj("code" -> body.code))
    }

  val UnsupportedService: Result = UnprocessableEntity(
    toJson(
      ErrorBody(
        "SERVICE_NOT_SUPPORTED"
      )
    )
  )

  val UnsupportedClientType: Result = UnprocessableEntity(
    toJson(
      ErrorBody(
        "CLIENT_TYPE_NOT_SUPPORTED"
      )
    )
  )

  val UnsupportedAgentType: Result = UnprocessableEntity(
    toJson(
      ErrorBody(
        "AGENT_TYPE_NOT_SUPPORTED"
      )
    )
  )

  val ClientIdDoesNotMatchService: Result = UnprocessableEntity(
    toJson(
      ErrorBody(
        "CLIENT_ID_DOES_NOT_MATCH_SERVICE"
      )
    )
  )

  val ClientIdInvalidFormat: Result = UnprocessableEntity(
    toJson(
      ErrorBody(
        "CLIENT_ID_FORMAT_INVALID"
      )
    )
  )

  val PostcodeFormatInvalid: Result = UnprocessableEntity(
    toJson(
      ErrorBody(
        "POSTCODE_FORMAT_INVALID"
      )
    )
  )

  val VatRegDateFormatInvalid: Result = UnprocessableEntity(
    toJson(
      ErrorBody(
        "VAT_REG_DATE_FORMAT_INVALID"
      )
    )
  )

  val PostcodeDoesNotMatch: Result = UnprocessableEntity(
    toJson(ErrorBody("POSTCODE_DOES_NOT_MATCH"))
  )

  val VatRegDateDoesNotMatch: Result = UnprocessableEntity(
    toJson(
      ErrorBody(
        "VAT_REG_DATE_DOES_NOT_MATCH"
      )
    )
  )

  val VatClientInsolvent: Result = UnprocessableEntity(
    toJson(
      ErrorBody(
        "VAT_CLIENT_INSOLVENT"
      )
    )
  )

  val ClientRegistrationNotFound: Result = UnprocessableEntity(
    toJson(
      ErrorBody("CLIENT_REGISTRATION_NOT_FOUND")
    )
  )

  val AgentSuspended: Result = UnprocessableEntity(
    toJson(
      ErrorBody(
        "AGENT_SUSPENDED"
      )
    )
  )

  val InvitationNotFound: Result = UnprocessableEntity(
    toJson(ErrorBody("INVITATION_NOT_FOUND"))
  )

  val RelationshipNotFound: Result = UnprocessableEntity(
    toJson(
      ErrorBody("RELATIONSHIP_NOT_FOUND")
    )
  )

  val InvalidPayload: Result = BadRequest(toJson(ErrorBody("INVALID_PAYLOAD")))

  def DuplicateAuthorisationRequest(invitationId: Option[String]): Result = UnprocessableEntity(
    toJson(
      ErrorBody(
        "DUPLICATE_AUTHORISATION_REQUEST",
        invitationId = invitationId
      )
    )
  )

  val AlreadyAuthorised: Result = UnprocessableEntity(
    toJson(
      ErrorBody(
        "ALREADY_AUTHORISED"
      )
    )
  )

  val NoPermissionOnAgency: Result = UnprocessableEntity(
    toJson(
      ErrorBody(
        "NO_PERMISSION_ON_AGENCY"
      )
    )
  )

}
