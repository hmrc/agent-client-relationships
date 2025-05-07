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

import cats.data.EitherT
import play.api.Logger
import play.api.libs.json.JsValue
import play.api.mvc.{Action, ControllerComponents, Result}
import uk.gov.hmrc.agentclientrelationships.model.CleanUpInvitationStatusRequest
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.{InvalidClientId, InvitationNotFound, UnsupportedService, UpdateStatusFailed}
import uk.gov.hmrc.agentclientrelationships.services.CleanUpInvitationStatusService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CleanUpInvitationStatusController @Inject() (
  setRelationshipEndedService: CleanUpInvitationStatusService,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
extends BackendController(cc) {

  def deauthoriseInvitation: Action[JsValue] =
    Action.async(parse.json) { implicit request =>
      request
        .body
        .validate[CleanUpInvitationStatusRequest]
        .fold(
          errs => Future.successful(BadRequest(s"Invalid payload: $errs")),
          payload => {
            val responseT =
              for {
                service <- EitherT.fromEither[Future](setRelationshipEndedService.validateService(payload.service))
                clientId <- EitherT.fromEither[Future](
                              setRelationshipEndedService.validateClientId(service, payload.clientId)
                            )
                result <- EitherT(
                            setRelationshipEndedService.deauthoriseInvitation(
                              arn = payload.arn,
                              clientId = clientId.value,
                              service = service.id,
                              relationshipEndedBy = "HMRC"
                            )
                          )
              } yield result

            responseT
              .value
              .map(
                _.fold(
                  failureResponse =>
                    invitationErrorHandler(
                      invitationFailureResponse = failureResponse,
                      service = payload.service,
                      clientId = payload.clientId
                    ),
                  _ => NoContent
                )
              )
          }
        )
    }

  private def invitationErrorHandler(
    invitationFailureResponse: InvitationFailureResponse,
    service: String,
    clientId: String
  ): Result =
    invitationFailureResponse match {
      case UnsupportedService =>
        val msg = s"""Unsupported service "$service""""
        Logger(getClass).warn(msg)
        UnsupportedService.getResult(msg)

      case InvalidClientId =>
        val msg = s"""Invalid clientId "$clientId", for service type "$service""""
        Logger(getClass).warn(msg)
        InvalidClientId.getResult(msg)

      case InvitationNotFound => InvitationNotFound.getResult("")

      case updateStatusFailed @ UpdateStatusFailed(_) => updateStatusFailed.getResult("")

      case _ => BadRequest
    }

}
