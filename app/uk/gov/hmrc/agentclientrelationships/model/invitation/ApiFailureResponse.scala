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

import play.api.libs.json.Json.obj
import play.api.libs.json.Json.toJson
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.Writes
import play.api.mvc.Result
import play.api.mvc.Results._
sealed trait ApiFailureResponse {
  def getResult: Result
}

object ApiFailureResponse {

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

  case object UnsupportedService
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("SERVICE_NOT_SUPPORTED")))
  }

  case object UnsupportedClientType
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("CLIENT_TYPE_NOT_SUPPORTED")))
  }

  case object ClientIdDoesNotMatchService
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("CLIENT_ID_DOES_NOT_MATCH_SERVICE")))
  }

  case object ClientIdInvalidFormat
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("CLIENT_ID_FORMAT_INVALID")))
  }

  case object PostcodeFormatInvalid
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("POSTCODE_FORMAT_INVALID")))
  }

  case object VatRegDateFormatInvalid
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("VAT_REG_DATE_FORMAT_INVALID")))
  }

  case object PostcodeDoesNotMatch
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("POSTCODE_DOES_NOT_MATCH")))
  }

  case object VatRegDateDoesNotMatch
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("VAT_REG_DATE_DOES_NOT_MATCH")))
  }

  case object VatClientInsolvent
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("VAT_CLIENT_INSOLVENT")))
  }

  case object ClientRegistrationNotFound
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("CLIENT_REGISTRATION_NOT_FOUND")))
  }

  case object AgentSuspended
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("AGENT_SUSPENDED")))
  }

  case object InvitationNotFound
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("INVITATION_NOT_FOUND")))
  }

  case object RelationshipNotFound
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("RELATIONSHIP_NOT_FOUND")))
  }

  case object InvalidPayload
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("INVALID_PAYLOAD")))
  }

  case class DuplicateAuthorisationRequest(invitationId: Option[String])
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("DUPLICATE_AUTHORISATION_REQUEST", invitationId)))
  }

  case object AlreadyAuthorised
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("ALREADY_AUTHORISED")))
  }

  case object NoPermissionOnAgency
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("NO_PERMISSION_ON_AGENCY")))
  }

  case class ApiInternalServerError(msg: String)
  extends ApiFailureResponse {
    def getResult: Result = InternalServerError(toJson(msg))
  }

  case object KnownFactDoesNotMatch
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("KNOWN_FACT_DOES_NOT_MATCH")))
  }

  case object ClientInsolvent
  extends ApiFailureResponse {
    def getResult: Result = UnprocessableEntity(toJson(ErrorBody("CLIENT_INSOLVENT")))
  }

}
