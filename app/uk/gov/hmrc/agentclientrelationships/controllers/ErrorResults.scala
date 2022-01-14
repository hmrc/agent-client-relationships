/*
 * Copyright 2022 HM Revenue & Customs
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

import play.api.libs.json.Json.toJson
import play.api.libs.json._
import play.api.mvc.Results.{Forbidden, Unauthorized}

object ErrorResults {

  case class ErrorBody(code: String, message: String)

  implicit val errorBodyWrites = new Writes[ErrorBody] {
    override def writes(body: ErrorBody): JsValue = Json.obj("code" -> body.code, "message" -> body.message)
  }

  val GenericUnauthorized = Unauthorized(
    toJson(ErrorBody("UNAUTHENTICATED", "Bearer token is missing or not authorized.")))
  val NoPermissionToPerformOperation = Forbidden(
    toJson(ErrorBody("NO_PERMISSION", "The logged in user is not permitted to perform the operation.")))
}
