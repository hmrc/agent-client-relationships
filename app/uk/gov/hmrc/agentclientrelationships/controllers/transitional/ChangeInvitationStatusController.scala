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
import play.api.Logger
import play.api.libs.json.JsValue
import play.api.mvc.{Action, ControllerComponents, Result}
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.{InvalidClientId, InvitationNotFound, UnsupportedService, UnsupportedStatusChange, UpdateStatusFailed}
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse
import uk.gov.hmrc.agentclientrelationships.model.transitional.ChangeInvitationStatusRequest
import uk.gov.hmrc.agentclientrelationships.services.transitional.ChangeInvitationStatusService
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Service}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ChangeInvitationStatusController @Inject() (
  changeInvitationStatusService: ChangeInvitationStatusService,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BackendController(cc) {

  def changeInvitationStatus(arn: Arn, serviceStr: String, clientIdStr: String): Action[JsValue] =
    Action.async(parse.json) { implicit request =>
      request.body
        .validate[ChangeInvitationStatusRequest]
        .fold(
          errs => Future.successful(BadRequest(s"Invalid payload: $errs")),
          changeRequest => {
            val responseT = for {

              service <- EitherT.fromEither[Future](changeInvitationStatusService.validateService(serviceStr))
              suppliedClientId <-
                EitherT.fromEither[Future](changeInvitationStatusService.validateClientId(service, clientIdStr))

              result <- EitherT(navigateToStatusAction(arn, service, suppliedClientId, changeRequest))
            } yield result

            responseT.value
              .map(
                _.fold(
                  err => invitationErrorHandler(err, serviceStr, clientIdStr),
                  _ => NoContent
                )
              )
          }
        )
    }

  private def navigateToStatusAction(
    arn: Arn,
    service: Service,
    suppliedClientId: ClientId,
    changeRequest: ChangeInvitationStatusRequest
  ): Future[Either[InvitationFailureResponse, Unit]] =
    changeRequest.invitationStatus match {
      case DeAuthorised =>
        changeInvitationStatusService.changeStatusInStore(
          arn = arn,
          service = service,
          suppliedClientId = suppliedClientId,
          changeRequest = changeRequest
        )
      case _ => Future.successful(Left(UnsupportedStatusChange))
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
        val msg =
          s"""Invalid clientId "$clientId", for service type "$service""""
        Logger(getClass).warn(msg)
        InvalidClientId.getResult(msg)

      case UnsupportedStatusChange =>
        UnsupportedStatusChange.getResult("")

      case InvitationNotFound =>
        InvitationNotFound.getResult("")

      case updateStatusFailed @ UpdateStatusFailed(_) =>
        updateStatusFailed.getResult("")

      case _ => BadRequest
    }

}
