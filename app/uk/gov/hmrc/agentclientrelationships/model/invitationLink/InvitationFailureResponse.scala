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

package uk.gov.hmrc.agentclientrelationships.model.invitationLink

import play.api.libs.json.{JsValue, Json, Writes}
import play.api.libs.json.Json.toJson
import play.api.mvc.Result
import play.api.mvc.Results.{BadRequest, Forbidden, NotImplemented}

sealed trait InvitationFailureResponse {
  def getResult(message: String): Result
}

object InvitationFailureResponse {

  case class ErrorBody(code: String, message: String)

  implicit val errorBodyWrites: Writes[ErrorBody] = new Writes[ErrorBody] {
    override def writes(body: ErrorBody): JsValue = Json.obj("code" -> body.code, "message" -> body.message)
  }

  case object UnsupportedService extends InvitationFailureResponse {
    def getResult(message: String): Result = NotImplemented(toJson(ErrorBody("UNSUPPORTED_SERVICE", message)))
  }

  case object InvalidClientId extends InvitationFailureResponse {
    def getResult(message: String): Result = BadRequest
  }

  case object UnsupportedClientIdType extends InvitationFailureResponse {
    def getResult(message: String): Result = BadRequest(toJson(ErrorBody("UNSUPPORTED_CLIENT_ID_TYPE", message)))
  }

  case object ClientRegistrationNotFound extends InvitationFailureResponse {
    def getResult(message: String): Result = Forbidden(
      toJson(
        ErrorBody(
          "CLIENT_REGISTRATION_NOT_FOUND",
          "The Client's MTDfB registration or SAUTR (if alt-itsa is enabled) was not found."
        )
      )
    )
  }

  case object DuplicateInvitationError extends InvitationFailureResponse {
    def getResult(message: String): Result = Forbidden(
      toJson(
        ErrorBody(
          "DUPLICATE_AUTHORISATION_REQUEST",
          "An authorisation request for this service has already been created and is awaiting the client’s response."
        )
      )
    )
  }
}
