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

package uk.gov.hmrc.agentclientrelationships.controllers.transitional

import cats.data.EitherT
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.InvitationNotFound
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.UnsupportedStatusChange
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.UpdateStatusFailed
import uk.gov.hmrc.agentclientrelationships.services.transitional.ChangeInvitationStatusByIdService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class ChangeInvitationStatusByIdController @Inject() (
  changeInvitationStatusByIdService: ChangeInvitationStatusByIdService,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
extends BackendController(cc) {

  def changeInvitationStatusById(
    invitationId: String,
    action: String
  ): Action[AnyContent] = Action.async { _ =>
    val responseT =
      for {
        invitationStatusAction <- EitherT.fromEither[Future](changeInvitationStatusByIdService.validateAction(action))
        result <- EitherT(changeInvitationStatusByIdService.changeStatusById(invitationId, invitationStatusAction))
      } yield result

    responseT
      .value
      .map(
        _.fold(
          {
            case InvitationFailureResponse.UnsupportedStatusChange => UnsupportedStatusChange.getResult("")
            case updateStatusFailed @ UpdateStatusFailed(_) => updateStatusFailed.getResult("")
            case InvitationFailureResponse.InvitationNotFound => InvitationNotFound.getResult("")
            case _ => BadRequest
          },
          _ => NoContent
        )
      )
  }

}
