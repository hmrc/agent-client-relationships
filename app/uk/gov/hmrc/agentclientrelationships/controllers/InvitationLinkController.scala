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
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.InvitationLinkFailureResponse._
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.{ValidateInvitationRequest, ValidateInvitationResponse}
import uk.gov.hmrc.agentclientrelationships.services.{CheckRelationshipsService, InvitationLinkService, InvitationService}
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP}
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class InvitationLinkController @Inject() (
  agentReferenceService: InvitationLinkService,
  invitationService: InvitationService,
  checkRelationshipsService: CheckRelationshipsService,
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

  private def servicesToSearchInvitationsFor(enrolments: Seq[EnrolmentKey], serviceKeys: Seq[String]): Set[String] = {
    val suppServices =
      serviceKeys.filter(multiAgentServices.contains).map(service => multiAgentServices(service))
    (enrolments.map(_.service) ++ suppServices).map {
      case "HMRC-NI" | "HMRC-PT" if serviceKeys.contains("HMRC-MTD-IT") => "HMRC-MTD-IT"
      case "HMRC-NI" | "HMRC-PT"                                        => "PERSONAL-INCOME-RECORD"
      case serviceKey                                                   => serviceKey
    }.toSet
  }

  def validateInvitationForClient: Action[ValidateInvitationRequest] =
    Action.async(parse.json[ValidateInvitationRequest]) { implicit request =>
      withAuthorisedClientForServiceKeys(request.body.serviceKeys) { enrolments =>
        agentReferenceService.validateInvitationRequest(request.body.uid).flatMap {
          case Right(validateLinkResponse) =>
            val servicesToSearch = servicesToSearchInvitationsFor(enrolments, request.body.serviceKeys)
            val clientIdsToSearch = enrolments.map(e => e.oneTaxIdentifier()).map(_.value)
            invitationService
              .findAllForAgent(validateLinkResponse.arn.value, servicesToSearch, clientIdsToSearch)
              .flatMap {
                case Seq(invitation) =>
                  for {
                    existingRelationship <-
                      checkRelationshipsService
                        .findCurrentMainAgent(invitation, enrolments.find(_.service == invitation.service))
                  } yield Ok(
                    Json.toJson(
                      ValidateInvitationResponse(
                        invitation.invitationId,
                        invitation.service,
                        validateLinkResponse.name,
                        invitation.status,
                        invitation.lastUpdated,
                        existingMainAgent = existingRelationship
                      )
                    )
                  )
                case _ =>
                  Logger(getClass).warn(
                    s"Invitation was not found for UID: ${request.body.uid}, service keys: ${request.body.serviceKeys}"
                  )
                  Future.successful(NotFound)
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
