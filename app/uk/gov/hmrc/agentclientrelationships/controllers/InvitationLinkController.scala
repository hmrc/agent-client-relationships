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

import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.InvitationLinkFailureResponse
import uk.gov.hmrc.agentclientrelationships.services.InvitationLinkService
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class InvitationLinkController @Inject() (
  agentReferenceService: InvitationLinkService,
  val authConnector: AuthConnector,
  val appConfig: AppConfig,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServices

  def validateLink(uid: String, normalizedAgentName: String): Action[AnyContent] = Action.async { implicit request =>
    agentReferenceService.validateLink(uid, normalizedAgentName).map { response =>
      response.fold(
        {
          case InvitationLinkFailureResponse.AgentReferenceDataNotFound |
              InvitationLinkFailureResponse.NormalizedAgentNameNotMatched =>
            Logger(getClass).warn(s"Agent Reference Record not found for uid: $uid")
            NotFound
          case InvitationLinkFailureResponse.AgentSuspended =>
            Logger(getClass).warn(s"Agent is suspended for uid: $uid")
            Forbidden
        },
        validLinkResponse => Ok(Json.toJson(validLinkResponse))
      )
    }
  }

  def createLink: Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsAgent { arn =>
      agentReferenceService
        .createLink(arn)
        .map(createLinkResponse => Ok(Json.toJson(createLinkResponse)))
    }
  }

}
