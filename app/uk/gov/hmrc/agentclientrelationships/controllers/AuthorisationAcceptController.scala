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

import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.Pending
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.ErrorBody
import uk.gov.hmrc.agentclientrelationships.services.{AuthorisationAcceptService, CreateRelationshipLocked, InvitationService, ValidationService}
import uk.gov.hmrc.agentmtdidentifiers.model.{ClientIdType, Service}
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.http.InternalServerException
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AuthorisationAcceptController @Inject() (
  invitationService: InvitationService,
  authorisationAcceptService: AuthorisationAcceptService,
  validationService: ValidationService,
  auditService: AuditService,
  val authConnector: AuthConnector,
  val appConfig: AppConfig,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with AuthActions
    with Logging {

  val supportedServices: Seq[Service] = appConfig.supportedServices

  private val strideRoles = Seq(appConfig.oldAuthStrideRole, appConfig.newAuthStrideRole)

  def accept(invitationId: String): Action[AnyContent] = Action.async { implicit request =>
    invitationService.findInvitation(invitationId).flatMap {
      case Some(invitation) if invitation.status == Pending =>
        implicit val auditData: AuditData = new AuditData()
        auditData.set("arn", invitation.arn)
        for {
          enrolment <-
            validationService
              .validateForEnrolmentKey(
                invitation.service,
                ClientIdType.forId(invitation.clientIdType).enrolmentId,
                invitation.clientId
              )
              .map(either =>
                either.getOrElse(
                  throw new InternalServerException(
                    s"Could not parse invitation details into enrolment reason: ${either.left}"
                  )
                )
              )
          result <-
            authorisedUser(None, enrolment.oneTaxIdentifier(), strideRoles) { implicit currentUser =>
              authorisationAcceptService
                .accept(invitation, enrolment)
                .map(_ => NoContent)
                .recoverWith {
                  case CreateRelationshipLocked => Future.successful(Locked)
                  case err                      => throw err
                }
            }
          // non blocking email request
          // non blocking friendly name update
          // audit
        } yield result
      case Some(_) =>
        Future.successful(
          Forbidden(
            Json.toJson(
              ErrorBody("INVALID_INVITATION_STATUS", "Invitation cannot be accepted because it is not Pending")
            )
          )
        )
      case _ =>
        Future.successful(
          NotFound(Json.toJson(ErrorBody("INVITATION_NOT_FOUND", "The specified invitation was not found.")))
        )
    }
  }

}
