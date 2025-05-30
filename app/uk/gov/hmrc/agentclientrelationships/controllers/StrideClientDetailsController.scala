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
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.RelationshipFailureResponse
import uk.gov.hmrc.agentclientrelationships.model.stride.ActiveClientsRelationshipResponse
import uk.gov.hmrc.agentclientrelationships.model.stride.ClientsRelationshipsRequest
import uk.gov.hmrc.agentclientrelationships.services.StrideClientDetailsService
import uk.gov.hmrc.agentclientrelationships.services.ValidationService
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class StrideClientDetailsController @Inject() (
  val authConnector: AuthConnector,
  validationService: ValidationService,
  strideClientDetailsService: StrideClientDetailsService,
  cc: ControllerComponents,
  appConfig: AppConfig
)(implicit val executionContext: ExecutionContext)
extends BackendController(cc)
with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir
  val strideRole: String = appConfig.newAuthStrideRole

  def get(
    service: String,
    clientIdType: String,
    clientId: String
  ): Action[AnyContent] = Action.async { implicit request =>
    validationService
      .validateForEnrolmentKey(
        service,
        clientIdType,
        clientId
      )
      .flatMap {
        case Left(error) => Future.successful(BadRequest(error))
        case Right(validKey) =>
          authorisedWithStride(appConfig.oldAuthStrideRole, appConfig.newAuthStrideRole) { _ =>
            strideClientDetailsService
              .getClientDetailsWithChecks(validKey)
              .map {
                case Some(clientDetails) => Ok(Json.toJson(clientDetails))
                case None => NotFound
              }
          }
      }
  }

  def getActiveRelationships: Action[ClientsRelationshipsRequest] =
    Action.async(parse.json[ClientsRelationshipsRequest]) { implicit request =>
      authorisedWithStride(appConfig.oldAuthStrideRole, appConfig.newAuthStrideRole) { _ =>
        strideClientDetailsService
          .findAllActiveRelationship(request.body)
          .map {
            case Right(activeRelationships) => Ok(Json.toJson(ActiveClientsRelationshipResponse(activeRelationships)))

            case Left(error) =>
              error match {
                case RelationshipFailureResponse.RelationshipBadRequest => BadRequest
                case RelationshipFailureResponse.TaxIdentifierError => BadRequest
                case RelationshipFailureResponse.ClientDetailsNotFound => NotFound
                case RelationshipFailureResponse.ErrorRetrievingClientDetails(_, message) => InternalServerError(message)
                case RelationshipFailureResponse.ErrorRetrievingAgentDetails(message) => InternalServerError(message)
                case RelationshipFailureResponse.ErrorRetrievingRelationship(_, message) => InternalServerError(message)
                case _ => InternalServerError
              }

          }
      }
    }

  def getIrvRelationships(nino: String): Action[AnyContent] = Action.async { implicit request =>
    authorisedWithStride(appConfig.oldAuthStrideRole, appConfig.newAuthStrideRole) { _ =>
      strideClientDetailsService.findActiveIrvRelationships(nino).map {
        case Right(irvRelationships) => Ok(Json.toJson(irvRelationships))
        case Left(error) =>
          error match {
            case RelationshipFailureResponse.TaxIdentifierError => BadRequest
            case RelationshipFailureResponse.ClientDetailsNotFound => NotFound
            case RelationshipFailureResponse.ErrorRetrievingClientDetails(_, message) => InternalServerError(message)
            case RelationshipFailureResponse.ErrorRetrievingAgentDetails(message) => InternalServerError(message)
            case RelationshipFailureResponse.ErrorRetrievingRelationship(_, message) => InternalServerError(message)
            case _ => InternalServerError
          }
      }
    }
  }

}
