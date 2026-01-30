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
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.invitation.AuthorisationRequestInfo
import uk.gov.hmrc.agentclientrelationships.model.invitation.AuthorisationRequestInfoForClient
import uk.gov.hmrc.agentclientrelationships.services.AgentAssuranceService
import uk.gov.hmrc.agentclientrelationships.services.InvitationLinkService
import uk.gov.hmrc.agentclientrelationships.services.InvitationService
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.ClientIdentifier
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class InvitationInfoController @Inject() (
  invitationService: InvitationService,
  invitationLinkService: InvitationLinkService,
  agentAssuranceService: AgentAssuranceService,
  val authConnector: AuthConnector,
  val appConfig: AppConfig,
  cc: ControllerComponents
)(implicit val executionContext: ExecutionContext)
extends BackendController(cc)
with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir

  def get(
    arn: Arn,
    invitationId: String
  ): Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsAgent { _ =>
      invitationService
        .findInvitationForAgent(arn.value, invitationId)
        .flatMap {
          case Some(invitation) =>
            for {
              agentLink <- invitationLinkService.createLink(arn)
            } yield Ok(Json.toJson(AuthorisationRequestInfo(invitation, agentLink)))
          case _ => Future.successful(NotFound)
        }
    }
  }

  def trackRequests(
    arn: Arn,
    statusFilter: Option[String],
    clientName: Option[String],
    pageNumber: Int,
    pageSize: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsAgent {
      case agentArn: Arn if agentArn == arn =>
        invitationService
          .trackRequests(
            arn,
            statusFilter.filter(_.nonEmpty),
            clientName.filter(_.nonEmpty),
            pageNumber,
            pageSize
          )
          .map { result =>
            Ok(Json.toJson(result))
          }
      case _ => Future.successful(Forbidden)
    }
  }

  def getForClient(invitationId: String): Action[AnyContent] = Action.async { implicit request =>
    invitationService
      .findInvitationForClient(invitationId)
      .flatMap {
        case Some(invitation) =>
          authorisedUser(
            arn = Some(Arn(invitation.arn)),
            clientId = ClientIdentifier(invitation.suppliedClientId, invitation.suppliedClientIdType).underlying,
            Seq.empty
          ) { _ =>
            val arn = Arn(invitation.arn)
            for {
              agentDetails <- agentAssuranceService.getAgentRecord(arn)
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
        case _ => Future.successful(NotFound)
      }
  }

}
