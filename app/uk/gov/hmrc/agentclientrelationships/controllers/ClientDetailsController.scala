/*
 * Copyright 2024 HM Revenue & Customs
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
import play.api.mvc._
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.Pending
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentclientrelationships.services._
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCCBCNONUKORG, HMRCCBCORG, HMRCMTDIT, HMRCMTDITSUPP}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Service}
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.domain.Nino
import play.api.mvc.RequestHeader
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ClientDetailsController @Inject() (
  clientDetailsService: ClientDetailsService,
  checkRelationshipsService: CheckRelationshipsOrchestratorService,
  invitationsRepository: InvitationsRepository,
  partialAuthRepository: PartialAuthRepository,
  val authConnector: AuthConnector,
  cc: ControllerComponents,
  appConfig: AppConfig
)(implicit val executionContext: ExecutionContext)
extends BackendController(cc)
with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir

  private def refineService(clientDetails: ClientDetailsResponse, service: String): String =
    service match {
      case `HMRCCBCORG` if clientDetails.isOverseas.contains(true) => HMRCCBCNONUKORG
      case service                                                 => service
    }

  private val multiAgentServices: Map[String, String] = Map(HMRCMTDIT -> HMRCMTDITSUPP)

  def findClientDetails(service: String, clientId: String): Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsAgent { arn =>
      clientDetailsService
        .findClientDetails(service, clientId)
        .flatMap {
          case Right(clientDetails) =>
            val refinedService = refineService(clientDetails, service)
            val clientIdType = Service(refinedService).supportedSuppliedClientIdType.enrolmentId
            for {
              pendingInvitationMain <- pendingInvitation(arn, refinedService, clientId)
              pendingInvitationSupp <-
                if (multiAgentServices.contains(refinedService))
                  pendingInvitation(arn, multiAgentServices(refinedService), clientId)
                else
                  Future.successful(false)
              currentRelationshipMain <- existingRelationship(arn, refinedService, clientIdType, clientId)
              currentRelationshipSupp <-
                if (multiAgentServices.contains(refinedService))
                  existingRelationship(arn, multiAgentServices(refinedService), clientIdType, clientId)
                else
                  Future.successful(None)
            } yield Ok(
              Json.toJson(
                clientDetails.copy(
                  hasPendingInvitation = pendingInvitationMain || pendingInvitationSupp,
                  hasExistingRelationshipFor = currentRelationshipMain.orElse(currentRelationshipSupp)
                )
              )
            )
          case Left(ClientDetailsNotFound) => Future.successful(NotFound)
          case Left(ErrorRetrievingClientDetails(status, message)) =>
            throw new RuntimeException(s"Client details lookup failed - status: '$status', error: '$message''")
        }
    }
  }

  private def existingRelationship(arn: Arn, service: String, clientIdType: String, clientId: String)(implicit
    request: RequestHeader
  ): Future[Option[String]] = checkRelationshipsService
    .checkForRelationship(arn, service, clientIdType, clientId, None)
    .map {
      case CheckRelationshipFound          => Some(service)
      case CheckRelationshipNotFound(_)    => None
      case CheckRelationshipInvalidRequest => throw new RuntimeException("Unexpected error during relationship check")
    }
    .flatMap {
      case None if Seq(HMRCMTDIT, HMRCMTDITSUPP).contains(service) =>
        partialAuthRepository.findActive(service, Nino(clientId), arn).map(_.map(_ => service))
      case result => Future.successful(result)
    }

  private def pendingInvitation(arn: Arn, service: String, clientId: String): Future[Boolean] = invitationsRepository
    .findAllForAgent(arn.value, Seq(service), Seq(clientId), isSuppliedClientId = true)
    .map(_.exists(_.status == Pending))
}
