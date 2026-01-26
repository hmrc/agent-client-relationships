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

package uk.gov.hmrc.agentclientrelationships.controllers

import cats.implicits._
import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.services.AgentTerminationService
import uk.gov.hmrc.agentclientrelationships.services.CheckRelationshipFound
import uk.gov.hmrc.agentclientrelationships.services.CheckRelationshipInvalidRequest
import uk.gov.hmrc.agentclientrelationships.services.CheckRelationshipNotFound
import uk.gov.hmrc.agentclientrelationships.services.CheckRelationshipsOrchestratorService
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext

@Singleton
class RelationshipsController @Inject() (
  override val authConnector: AuthConnector,
  val appConfig: AppConfig,
  checkOrchestratorService: CheckRelationshipsOrchestratorService,
  agentTerminationService: AgentTerminationService,
  override val controllerComponents: ControllerComponents
)(implicit val executionContext: ExecutionContext)
extends BackendController(controllerComponents)
with AuthActions
with RequestAwareLogging {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir

  def checkForRelationship(
    arn: Arn,
    service: String,
    clientIdType: String,
    clientId: String,
    userId: Option[String]
  ): Action[AnyContent] = Action.async { implicit request =>
    authorised() {
      checkOrchestratorService
        .checkForRelationship(
          arn,
          service,
          clientIdType,
          clientId,
          userId
        )
        .map {
          case CheckRelationshipFound => Ok
          case CheckRelationshipNotFound(message) => NotFound(Json.obj("code" -> message))
          case CheckRelationshipInvalidRequest => BadRequest
        }
    }
  }

  def terminateAgent(arn: Arn): Action[AnyContent] = Action.async { implicit request =>
    withBasicAuth(appConfig.expectedAuth) {
      agentTerminationService
        .terminateAgent(arn)
        .fold(
          error => {
            logger.warn(s"unexpected error during agent termination: $arn, error = $error")
            InternalServerError
          },
          result => Ok(Json.toJson(result))
        )
    }
  }

}
