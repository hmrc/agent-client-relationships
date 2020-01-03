/*
 * Copyright 2020 HM Revenue & Customs
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

import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful}

object fluentSyntax {

  def returnValue[T](a: T): Future[T] = successful(a)

  def raiseError(exception: Throwable): Future[Nothing] = failed(exception)

  def toJson(code: String): JsObject = Json.obj("code" -> code)

}
