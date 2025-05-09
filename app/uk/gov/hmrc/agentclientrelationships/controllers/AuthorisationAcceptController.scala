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

import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import play.api.Logger
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys._
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.audit.AuditService
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.NoPendingInvitation
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.Pending
import uk.gov.hmrc.agentclientrelationships.services._
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdType
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class AuthorisationAcceptController @Inject() (
  invitationService: InvitationService,
  authorisationAcceptService: AuthorisationAcceptService,
  validationService: ValidationService,
  friendlyNameService: FriendlyNameService,
  auditService: AuditService,
  val authConnector: AuthConnector,
  val appConfig: AppConfig,
  cc: ControllerComponents
)(implicit val executionContext: ExecutionContext)
extends BackendController(cc)
with AuthActions
with Logging {

  val supportedServices: Seq[Service] = appConfig.supportedServices

  private val strideRoles = Seq(appConfig.oldAuthStrideRole, appConfig.newAuthStrideRole)

  def accept(invitationId: String): Action[AnyContent] = Action.async { implicit request =>
    invitationService
      .findInvitation(invitationId)
      .flatMap {
        case Some(invitation) if invitation.status == Pending =>
          implicit val auditData: AuditData = prepareAuditData(invitation)

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
              ) { implicit currentUser =>
                authorisationAcceptService
                  .accept(invitation, enrolment)
                  .map { _ =>
                    auditService.sendRespondToInvitationAuditEvent(
                      invitation,
                      accepted = true,
                      isStride = currentUser.isStride
                    )
                    NoContent
                  }
                  .recoverWith {
                    case CreateRelationshipLocked =>
                      Future.successful(Locked)
                    case err =>
                      throw err
                  }
              }
            _ <-
              if (result == NoContent)
                friendlyNameService.updateFriendlyName(invitation, enrolment)
              else
                Future.unit
          } yield result
        case _ =>
          val msg = s"Pending Invitation not found for invitationId '$invitationId'"
          Logger(getClass).warn(msg)
          Future.successful(NoPendingInvitation.getResult(msg))
      }
  }

  private def prepareAuditData(invitation: Invitation): AuditData = {
    val auditData: AuditData = new AuditData()
    auditData.set(arnKey, invitation.arn)
    auditData.set(serviceKey, invitation.service)
    auditData.set(clientIdKey, invitation.clientId)
    auditData.set(clientIdTypeKey, invitation.clientIdType)
    auditData.set(invitationIdKey, invitation.invitationId)
    auditData.set(enrolmentDelegatedKey, false)
    auditData.set(etmpRelationshipCreatedKey, false)
    auditData
  }

}
