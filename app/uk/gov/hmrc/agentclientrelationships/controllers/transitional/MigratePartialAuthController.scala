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

package uk.gov.hmrc.agentclientrelationships.controllers.transitional

import org.mongodb.scala.MongoWriteException
import play.api.Logging
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.services.InvitationService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class MigratePartialAuthController @Inject() (
  invitationService: InvitationService,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
extends BackendController(cc)
with Logging {

  def migratePartialAuth: Action[AnyContent] = Action.async { implicit request =>
    val invitation = request.body.asJson.get.as[Invitation](Invitation.acaReads)
    if (invitation.isAltItsa)
      invitationService
        .migratePartialAuth(invitation)
        .map(_ => NoContent)
        .recoverWith {
          case e: MongoWriteException if e.getError.getCode.equals(11000) =>
            logger.warn(s"Duplicate found for invitationId ${invitation.invitationId} so record already there and continuing with deletion")
            Future(NoContent)
          case other =>
            Future.failed(other)
        }
    else
      Future.successful(BadRequest)
  }

}
