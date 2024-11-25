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
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, ControllerComponents}
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.invitation.CreateInvitationInputData
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.InvitationFailureResponse.{ClientRegistrationNotFound, DuplicateInvitationError, InvalidClientId, UnsupportedClientIdType, UnsupportedService}
import uk.gov.hmrc.agentclientrelationships.services.InvitationService
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Service}
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class InvitationController @Inject() (
  invitationService: InvitationService,
  val authConnector: AuthConnector,
  val appConfig: AppConfig,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServices

  def createInvitation(arn: Arn): Action[JsValue] = Action.async(parse.json) { implicit request =>
    request.body
      .validate[CreateInvitationInputData]
      .fold(
        errs => Future.successful(BadRequest(s"Invalid payload: $errs")),
        createInvitationInputData => {
          val originHeader: Option[String] = request.headers.get("Origin")
          invitationService.createInvitation(arn, createInvitationInputData, originHeader).map { response =>
            response.fold(
              {
                case UnsupportedService =>
                  val msg = s"""Unsupported service "${createInvitationInputData.inputService}""""
                  Logger(getClass).warn(msg)
                  UnsupportedService.getResult(msg)

                case InvalidClientId =>
                  val msg =
                    s"""Invalid clientId "${createInvitationInputData.inputSuppliedClientId}", for service type "${createInvitationInputData.inputService}""""
                  Logger(getClass).warn(msg)
                  InvalidClientId.getResult(msg)

                case UnsupportedClientIdType =>
                  val msg =
                    s"""Unsupported clientIdType "${createInvitationInputData.inputSuppliedClientIdType}", for service type "${createInvitationInputData.inputService}"""".stripMargin
                  Logger(getClass).warn(msg)
                  UnsupportedClientIdType.getResult(msg)

                case ClientRegistrationNotFound =>
                  val msg = s"""The Client's MTDfB registration or SAUTR (if alt-itsa is enabled) was not found.
                               | for clientId "${createInvitationInputData.inputSuppliedClientId}",
                               | for clientIdType "${createInvitationInputData.inputSuppliedClientIdType}",
                               | for service type "${createInvitationInputData.inputService}"""".stripMargin
                  Logger(getClass).warn(msg)
                  ClientRegistrationNotFound.getResult(msg)

                case DuplicateInvitationError =>
                  val msg = s"""An authorisation request for this service has already been created
                               | and is awaiting the client’s response.
                               | for clientId "${createInvitationInputData.inputSuppliedClientId}",
                               | for clientIdType "${createInvitationInputData.inputSuppliedClientIdType}",
                               | for service type "${createInvitationInputData.inputService}"""".stripMargin
                  Logger(getClass).warn(msg)
                  DuplicateInvitationError.getResult(msg)
              },
              invitation => Created(Json.toJson(CreateInvitationResponse(invitation.invitationId)))
            )
          }
        }
      )

  }

}