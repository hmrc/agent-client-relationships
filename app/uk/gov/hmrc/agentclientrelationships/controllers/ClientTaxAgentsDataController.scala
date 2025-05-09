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
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentsWithNino
import uk.gov.hmrc.agentclientrelationships.model.RelationshipFailureResponse
import uk.gov.hmrc.agentclientrelationships.services.ClientTaxAgentsDataService
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext

@Singleton
class ClientTaxAgentsDataController @Inject() (
  carService: ClientTaxAgentsDataService,
  val authConnector: AuthConnector,
  cc: ControllerComponents,
  appConfig: AppConfig
)(implicit val executionContext: ExecutionContext)
extends BackendController(cc)
with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir

  def findClientTaxAgentsData: Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsClientWithNino { authResponse: EnrolmentsWithNino =>
      carService
        .getClientTaxAgentsData(authResponse)
        .map {
          case Right(clientTaxAgentsData) => Ok(Json.toJson(clientTaxAgentsData))
          case Left(error) =>
            // TODO: It takes great effort to return 5xx, which is probably ignored and results in technical difficulties anyway in frontend anyway...
            // Verify if this is really needed and if not then rely on standard JsonErrorHandlder and simplify that and other code
            error match {
              case RelationshipFailureResponse.RelationshipBadRequest => BadRequest
              case RelationshipFailureResponse.ErrorRetrievingAgentDetails(message) => ServiceUnavailable(message)
              case RelationshipFailureResponse.ErrorRetrievingRelationship(_, message) => ServiceUnavailable(message)
              case e =>
                logger.error(s"Error retrieving client tax agents data: $e")
                InternalServerError(e.toString)
            }

        }

    }
  }

}
