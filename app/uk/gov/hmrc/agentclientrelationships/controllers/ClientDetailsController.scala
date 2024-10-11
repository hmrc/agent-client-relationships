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

import play.api.libs.json.Json
import play.api.mvc.{Action, ControllerComponents}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.services.ClientDetailsService
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.http.InternalServerException
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class ClientDetailsController @Inject() (
  clientDetailsService: ClientDetailsService,
  val authConnector: AuthConnector,
  cc: ControllerComponents
)(implicit appConfig: AppConfig, ec: ExecutionContext)
    extends BackendController(cc) {

  val supportedServices: Seq[Service] = appConfig.supportedServices

  def findClientDetails(service: String): Action[ClientDetailsRequest] =
    Action.async(parse.json[ClientDetailsRequest]) { implicit request =>
      clientDetailsService.findClientDetails(service, request.body).map {
        case Right(details)                => Ok(Json.toJson(details))
        case Left(ClientDetailsDoNotMatch) => BadRequest
        case Left(ClientDetailsNotFound)   => NotFound
        case Left(ErrorRetrievingClientDetails(status, message)) =>
          throw new InternalServerException(
            s"Downstream call for service: $service failed with status: $status and error message: $message"
          )
      }
    }
}
