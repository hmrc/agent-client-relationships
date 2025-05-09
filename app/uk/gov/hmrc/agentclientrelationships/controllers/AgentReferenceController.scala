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

import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.repository.AgentReferenceRepository
import uk.gov.hmrc.agentclientrelationships.services.InvitationLinkService
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.auth.core.AuthorisedFunctions
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class AgentReferenceController @Inject() (
  appConfig: AppConfig,
  invitationLinkService: InvitationLinkService,
  agentReferenceRepository: AgentReferenceRepository,
  val authConnector: AuthConnector,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
extends BackendController(cc)
with AuthorisedFunctions {

  def fetchOrCreateRecord(arn: Arn): Action[JsValue] =
    Action.async(parse.json) { implicit request =>
      authorised() {
        (request.body \ "normalisedAgentName")
          .validate[String]
          .fold(
            error => Future.successful(BadRequest),
            normalisedAgentName =>
              agentReferenceRepository
                .findByArn(arn)
                .flatMap {
                  case Some(record) if record.normalisedAgentNames.contains(normalisedAgentName) => Future.successful(Ok(Json.toJson(record)))
                  case Some(record) =>
                    agentReferenceRepository
                      .updateAgentName(record.uid, normalisedAgentName)
                      .map(_ =>
                        Ok(
                          Json.toJson(
                            record.copy(normalisedAgentNames = record.normalisedAgentNames ++ Seq(normalisedAgentName))
                          )
                        )
                      )
                  case None =>
                    invitationLinkService
                      .createAgentReferenceRecord(arn = arn, normalisedAgentNames = normalisedAgentName)
                      .map(record => Ok(Json.toJson(record)))
                }
          )
      }
    }

  def fetchRecordByUid(uid: String): Action[AnyContent] = Action.async { _ =>
    agentReferenceRepository
      .findBy(uid)
      .map {
        case Some(record) => Ok(Json.toJson(record))
        case None => NotFound
      }
  }

}
