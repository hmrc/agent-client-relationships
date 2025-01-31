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

package uk.gov.hmrc.agentclientrelationships.controllers

import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.services.{StrideClientDetailsService, ValidationService}
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class StrideClientDetailsController @Inject() (
  val authConnector: AuthConnector,
  validationService: ValidationService,
  strideClientDetailsService: StrideClientDetailsService,
  cc: ControllerComponents,
  appConfig: AppConfig
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir
  val strideRole: String = appConfig.newAuthStrideRole

  def get(service: String, clientIdType: String, clientId: String): Action[AnyContent] = Action.async {
    implicit request =>
      validationService.validateForEnrolmentKey(service, clientIdType, clientId).flatMap {
        case Left(error) => Future.successful(BadRequest(error))
        case Right(validKey) =>
          authorisedWithStride(appConfig.oldAuthStrideRole, appConfig.newAuthStrideRole) { _ =>
            strideClientDetailsService.getClientDetailsWithChecks(validKey).map {
              case Some(clientDetails) => Ok(Json.toJson(clientDetails))
              case None                => NotFound
            }
          }
      }
  }

}
