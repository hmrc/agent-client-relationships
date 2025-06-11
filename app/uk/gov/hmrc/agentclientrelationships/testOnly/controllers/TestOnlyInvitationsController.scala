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

package uk.gov.hmrc.agentclientrelationships.testOnly.controllers

import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.agentclientrelationships.services.InvitationService
import uk.gov.hmrc.auth.core.{AuthConnector, AuthorisedFunctions}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class TestOnlyInvitationsController @Inject()(controllerComponents: ControllerComponents,
                                              invitationService: InvitationService,
                                              val authConnector: AuthConnector)
                                             (implicit ec: ExecutionContext)
  extends BackendController(controllerComponents) with AuthorisedFunctions {

  private val invitationIdRegex = "^[A-Z0-9]{13}$".r

  def getInvitation(invitationId: String): Action[AnyContent] = Action.async { implicit request =>
    authorised() {
      if (invitationIdRegex.matches(invitationId)) {
        invitationService.findInvitation(invitationId).map {
          case Some(invitation) => Ok(Json.toJson(invitation))
          case None => NotFound
        }
      } else {
        Future.successful(BadRequest("Invalid invitation ID format"))
      }
    }
  }
}
