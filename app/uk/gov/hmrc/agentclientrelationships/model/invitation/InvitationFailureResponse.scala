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

package uk.gov.hmrc.agentclientrelationships.model.invitation

import play.api.libs.json.Json.toJson
import play.api.libs.json.{JsValue, Json, Writes}
import play.api.mvc.Result
import play.api.mvc.Results.{BadRequest, Forbidden, InternalServerError, NotFound, NotImplemented}

sealed trait InvitationFailureResponse {
  def getResult(message: String): Result
}

object InvitationFailureResponse {

  case class ErrorBody(code: String, message: String)

  implicit val errorBodyWrites: Writes[ErrorBody] =
    new Writes[ErrorBody] {
      override def writes(body: ErrorBody): JsValue = Json.obj("code" -> body.code, "message" -> body.message)
    }

  case object UnsupportedService
  extends InvitationFailureResponse {
    def getResult(message: String): Result = NotImplemented(toJson(ErrorBody("UNSUPPORTED_SERVICE", message)))
  }

  case object InvalidClientId
  extends InvitationFailureResponse {
    def getResult(message: String): Result = BadRequest(toJson(ErrorBody("INVALID_CLIENT_ID", message)))
  }

  case object UnsupportedClientIdType
  extends InvitationFailureResponse {
    def getResult(message: String): Result = BadRequest(toJson(ErrorBody("UNSUPPORTED_CLIENT_ID_TYPE", message)))
  }

  case object UnsupportedClientType
  extends InvitationFailureResponse {
    def getResult(message: String): Result = BadRequest(toJson(ErrorBody("UNSUPPORTED_CLIENT_TYPE", message)))
  }

  case object ClientRegistrationNotFound
  extends InvitationFailureResponse {
    def getResult(message: String): Result = Forbidden(
      toJson(
        ErrorBody(
          "CLIENT_REGISTRATION_NOT_FOUND",
          "The Client's MTDfB registration or SAUTR (if alt-itsa is enabled) was not found."
        )
      )
    )
  }

  case object DuplicateInvitationError
  extends InvitationFailureResponse {
    def getResult(message: String): Result = Forbidden(
      toJson(
        ErrorBody(
          "DUPLICATE_AUTHORISATION_REQUEST",
          "An authorisation request for this service has already been created and is awaiting the client’s response."
        )
      )
    )
  }

  case object RelationshipNotFound
  extends InvitationFailureResponse {
    def getResult(message: String): Result = NotFound(
      toJson(ErrorBody("RELATIONSHIP_NOT_FOUND", "The specified relationship was not found."))
    )
  }

  case object EnrolmentKeyNotFound
  extends InvitationFailureResponse {
    def getResult(message: String): Result = BadRequest
  }

  case class RelationshipDeleteFailed(msg: String)
  extends InvitationFailureResponse {
    def getResult(message: String): Result = InternalServerError(toJson(msg))
  }

  case object NoPendingInvitation
  extends InvitationFailureResponse {
    def getResult(message: String): Result = NotFound(message)
  }

  case object UnsupportedStatusChange
  extends InvitationFailureResponse {
    def getResult(message: String): Result = BadRequest(
      toJson(ErrorBody("UNSUPPORTED_STATUS_CHANGE", "Not supported invitation status change"))
    )
  }

  case class UpdateStatusFailed(msg: String)
  extends InvitationFailureResponse {
    def getResult(message: String): Result = InternalServerError(toJson(msg))
  }

  case object InvitationNotFound
  extends InvitationFailureResponse {
    def getResult(message: String): Result = NotFound
  }
}
