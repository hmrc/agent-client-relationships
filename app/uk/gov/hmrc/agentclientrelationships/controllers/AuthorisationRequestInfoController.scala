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

import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.AgentAssuranceConnector
import uk.gov.hmrc.agentclientrelationships.model.invitation.{AuthorisationRequestInfo, AuthorisationRequestInfoForClient}
import uk.gov.hmrc.agentclientrelationships.services.{InvitationLinkService, InvitationService}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, ClientIdentifier, Service}
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AuthorisationRequestInfoController @Inject() (
  invitationService: InvitationService,
  invitationLinkService: InvitationLinkService,
  agentAssuranceConnector: AgentAssuranceConnector,
  val authConnector: AuthConnector,
  val appConfig: AppConfig,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir

  def get(arn: Arn, invitationId: String): Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsAgent { _ =>
      invitationService.findInvitationForAgent(arn.value, invitationId).flatMap {
        case Some(invitation) =>
          for {
            agentLink    <- invitationLinkService.createLink(arn)
            agentDetails <- agentAssuranceConnector.getAgentRecordWithChecks(arn)
          } yield Ok(Json.toJson(AuthorisationRequestInfo(invitation, agentLink, agentDetails)))
        case _ =>
          Future.successful(NotFound)
      }
    }
  }

  def getForClient(invitationId: String): Action[AnyContent] = Action.async { implicit request =>
    invitationService.findInvitationForClient(invitationId).flatMap {
      case Some(invitation) =>
        authorisedUser(
          arn = Some(Arn(invitation.arn)),
          clientId = ClientIdentifier(invitation.clientId, invitation.clientIdType).underlying,
          Seq.empty
        ) { _ =>
          val arn = Arn(invitation.arn)
          for {
            agentDetails <- agentAssuranceConnector.getAgentRecordWithChecks(arn)
          } yield Ok(
            Json.toJson(
              AuthorisationRequestInfoForClient(
                agentName = agentDetails.agencyDetails.agencyName,
                service = invitation.service,
                status = invitation.status
              )
            )
          )
        }
      case _ =>
        Future.successful(NotFound)
    }
  }

}
