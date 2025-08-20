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

import play.api.Logger
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.agentclientrelationships.audit.AuditService
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.Pending
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse._
import uk.gov.hmrc.agentclientrelationships.model.invitation._
import uk.gov.hmrc.agentclientrelationships.services.InvitationService
import uk.gov.hmrc.agentclientrelationships.services.ValidationService
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Trust
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.TrustNT
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class InvitationController @Inject() (
  invitationService: InvitationService,
  auditService: AuditService,
  validationService: ValidationService,
  val authConnector: AuthConnector,
  val appConfig: AppConfig,
  cc: ControllerComponents
)(implicit val executionContext: ExecutionContext)
extends BackendController(cc)
with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir

  private val strideRoles = Seq(appConfig.oldAuthStrideRole, appConfig.newAuthStrideRole)

  def createInvitation(arn: Arn): Action[JsValue] =
    Action.async(parse.json) { implicit request =>
      authorised() {
        request.body
          .validate[CreateInvitationRequest]
          .fold(
            errs => Future.successful(BadRequest(s"Invalid payload: $errs")),
            createInvitationRequest =>
              invitationService
                .createInvitation(arn, createInvitationRequest)
                .map { response =>
                  response.fold(
                    {
                      case UnsupportedService =>
                        val msg = s"""Unsupported service "${createInvitationRequest.service}""""
                        Logger(getClass).warn(msg)
                        UnsupportedService.getResult(msg)

                      case InvalidClientId =>
                        val msg = s"""Invalid clientId "${createInvitationRequest.clientId}", for service type "${createInvitationRequest.service}""""
                        Logger(getClass).warn(msg)
                        InvalidClientId.getResult(msg)

                      case UnsupportedClientIdType =>
                        val msg =
                          s"""Unsupported clientIdType "${createInvitationRequest.suppliedClientIdType}", for service type "${createInvitationRequest.service}"""".stripMargin
                        Logger(getClass).warn(msg)
                        UnsupportedClientIdType.getResult(msg)

                      case UnsupportedClientType =>
                        val msg = s"""Unsupported clientType "${createInvitationRequest.clientType}""""
                        Logger(getClass).warn(msg)
                        UnsupportedClientType.getResult(msg)

                      case ClientRegistrationNotFound =>
                        val msg =
                          s"""The Client's MTDfB registration or SAUTR (if alt-itsa is enabled) was not found.
                             | for clientId "${createInvitationRequest.clientId}",
                             | for clientIdType "${createInvitationRequest.suppliedClientIdType}",
                             | for service type "${createInvitationRequest.service}"""".stripMargin
                        Logger(getClass).warn(msg)
                        ClientRegistrationNotFound.getResult(msg)

                      case DuplicateInvitationError =>
                        val msg =
                          s"""An authorisation request for this service has already been created
                             | and is awaiting the client’s response.
                             | for clientId "${createInvitationRequest.clientId}",
                             | for clientIdType "${createInvitationRequest.suppliedClientIdType}",
                             | for service type "${createInvitationRequest.service}"""".stripMargin
                        Logger(getClass).warn(msg)
                        DuplicateInvitationError.getResult(msg)

                      case _ => BadRequest
                    },
                    invitation => {
                      auditService.sendCreateInvitationAuditEvent(invitation)
                      Created(Json.toJson(CreateInvitationResponse(invitation.invitationId)))
                    }
                  )
                }
          )
      }
    }

  def rejectInvitation(invitationId: String): Action[AnyContent] = Action.async { implicit request =>
    invitationService
      .findInvitation(invitationId)
      .flatMap {
        case Some(invitation) if invitation.status == Pending =>
          for {
            enrolment <- validationService
              .validateForEnrolmentKey(
                invitation.service,
                ClientIdType.forId(invitation.clientIdType).enrolmentId,
                invitation.clientId
              )
              .map(either =>
                either.getOrElse(
                  throw new RuntimeException(
                    s"Could not parse invitation details into enrolment reason: ${either.left}"
                  )
                )
              )
            result <-
              authorisedUser(
                None,
                enrolment.oneTaxIdentifier(),
                strideRoles
              ) { currentUser =>
                invitationService
                  .rejectInvitation(invitationId)
                  .map { _ =>
                    auditService.sendRespondToInvitationAuditEvent(
                      invitation,
                      accepted = false,
                      isStride = currentUser.isStride
                    )
                    NoContent
                  }
              }
          } yield result

        case _ =>
          val msg = s"Pending Invitation not found for invitationId '$invitationId'"
          Logger(getClass).warn(msg)
          Future.successful(NoPendingInvitation.getResult(msg))
      }
  }

  def replaceUrnWithUtr(urn: String): Action[JsValue] =
    Action.async(parse.json) { implicit request =>
      authorised() {
        val utr = (request.body \ "utr").as[String]
        invitationService
          .updateInvitation(
            TrustNT.enrolmentKey,
            urn,
            UrnType.id,
            Trust.enrolmentKey,
            utr,
            UtrType.id
          )
          .map {
            case true => NoContent
            case false => NotFound
          }
      }
    }

  def cancelInvitation(invitationId: String): Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsAgent { authArn =>
      invitationService.cancelInvitation(authArn, invitationId).map { response =>
        response match {
          case Left(response) => response.getResult
          case Right(_) => NoContent
        }
      }
    }
  }

}
