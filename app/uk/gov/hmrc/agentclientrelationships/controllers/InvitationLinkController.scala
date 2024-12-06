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
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.InvitationLinkFailureResponse._
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.{ValidateInvitationRequest, ValidateInvitationResponse}
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.services.InvitationLinkService
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP}
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class InvitationLinkController @Inject() (
  agentReferenceService: InvitationLinkService,
  invitationsRepository: InvitationsRepository,
  val authConnector: AuthConnector,
  val appConfig: AppConfig,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServices

  def validateLink(uid: String, normalizedAgentName: String): Action[AnyContent] = Action.async { implicit request =>
    agentReferenceService.validateLink(uid, normalizedAgentName).map { response =>
      response.fold(
        {
          case AgentReferenceDataNotFound | NormalizedAgentNameNotMatched =>
            Logger(getClass).warn(s"Agent Reference Record not found for uid: $uid")
            NotFound
          case AgentSuspended =>
            Logger(getClass).warn(s"Agent is suspended for uid: $uid")
            Forbidden
        },
        validLinkResponse => Ok(Json.toJson(validLinkResponse))
      )
    }
  }

  def createLink: Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsAgent { arn =>
      agentReferenceService
        .createLink(arn)
        .map(createLinkResponse => Ok(Json.toJson(createLinkResponse)))
    }
  }
  // TODO: this is a duplicate of what's used in the ClientDetailsController - we really want centralised config
  private val multiAgentServices: Map[String, String] = Map(HMRCMTDIT -> HMRCMTDITSUPP)

  def validateInvitationForClient: Action[ValidateInvitationRequest] =
    Action.async(parse.json[ValidateInvitationRequest]) { implicit request =>
      withAuthorisedAsClient { enrolments =>
        val targetServices = request.body.serviceKeys
        val targetEnrolments = enrolments.view.filterKeys(key => targetServices.contains(key.enrolmentKey)).toMap
        agentReferenceService.validateInvitationRequest(request.body.uid).flatMap {
          case Right(validateLinkModel) =>
            val mainServices = targetEnrolments.keys.map(_.id).toSeq
            val suppServices =
              mainServices.filter(multiAgentServices.contains).map(service => multiAgentServices(service))
            val servicesToSearch = mainServices ++ suppServices
            val clientIds = targetEnrolments.values.map(_.value).toSeq
            invitationsRepository.findAllForAgent(validateLinkModel.arn.value, servicesToSearch, clientIds).map {
              case Seq(invitation) =>
                val response = ValidateInvitationResponse(
                  invitation.invitationId,
                  invitation.service,
                  validateLinkModel.name,
                  invitation.status,
                  invitation.lastUpdated
                )
                Ok(Json.toJson(response))
              case _ =>
                Logger(getClass).warn(
                  s"Invitation was not found for UID: ${request.body.uid}, service keys: ${request.body.serviceKeys}"
                )
                NotFound
            }
          case Left(AgentSuspended) =>
            Logger(getClass).warn(s"Agent is suspended for UID: ${request.body.uid}")
            Future(Forbidden)
          case Left(_) =>
            Logger(getClass).warn(s"Agent Reference Record not found for UID: ${request.body.uid}")
            Future(NotFound)

        }
      }
    }
}
