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
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.services._
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCCBCNONUKORG
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCCBCORG
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCMTDIT
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.domain.Nino
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.services.CheckRelationshipResult.relationshipNotFoundAlreadyCopied
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class ClientDetailsController @Inject() (
  clientDetailsService: ClientDetailsService,
  checkRelationshipsService: CheckRelationshipsOrchestratorService,
  checkAndCopyRelationshipsService: CheckAndCopyRelationshipsService,
  invitationsRepository: InvitationsRepository,
  partialAuthRepository: PartialAuthRepository,
  val authConnector: AuthConnector,
  cc: ControllerComponents,
  appConfig: AppConfig
)(implicit val executionContext: ExecutionContext)
extends BackendController(cc)
with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir

  private def refineService(
    clientDetails: ClientDetailsResponse,
    service: String
  ): String =
    service match {
      case `HMRCCBCORG` if clientDetails.isOverseas.contains(true) => HMRCCBCNONUKORG
      case service => service
    }

  private val multiAgentServices: Map[String, String] = Map(HMRCMTDIT -> HMRCMTDITSUPP)

  // scalastyle:off method.length cyclomatic.complexity
  def findClientDetails(
    service: String,
    clientId: String
  ): Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsAgent { arn =>
      clientDetailsService
        .findClientDetails(service, clientId)
        .flatMap {
          case Right(clientDetails) =>
            val refinedService = refineService(clientDetails, service)
            val clientIdType = Service(refinedService).supportedSuppliedClientIdType.enrolmentId
            for {
              pendingInvitationMain <- pendingInvitation(
                arn,
                refinedService,
                clientId
              )
              pendingInvitationSupp <-
                multiAgentServices.get(refinedService).fold(Future.successful(false))(
                  pendingInvitation(
                    arn,
                    _,
                    clientId
                  )
                )
              (currentRelationshipMain, alreadyCopied) <- existingRelationship(
                arn,
                refinedService,
                clientIdType,
                clientId
              )
              (currentRelationshipSupp, _) <-
                multiAgentServices.get(refinedService).fold(Future.successful((Option.empty[String], false)))(
                  existingRelationship(
                    arn,
                    _,
                    clientIdType,
                    clientId
                  )
                )
              pendingInvitation = pendingInvitationMain || pendingInvitationSupp
              currentRelationship = currentRelationshipMain.orElse(currentRelationshipSupp)
              (isMapped, legacyRelationships) <-
                currentRelationship match {
                  case None if !pendingInvitation && !alreadyCopied && Nino.isValid(clientId) && refinedService.equals(HMRCMTDIT) =>
                    checkAndCopyRelationshipsService
                      .lookupCesaForOldRelationship(arn, Nino(clientId)).map {
                        case (mappedRef, legacyRefs) => (Some(mappedRef.nonEmpty), Some(legacyRefs.map(_.value)))
                      }
                  case _ => Future.successful((None, None))
                }
            } yield Ok(
              Json.toJson(
                clientDetails.copy(
                  hasPendingInvitation = pendingInvitation,
                  hasExistingRelationshipFor = currentRelationship,
                  isMapped = isMapped,
                  clientsLegacyRelationships = legacyRelationships
                )
              )
            )
          case Left(ClientDetailsNotFound) => Future.successful(NotFound)
          case Left(ErrorRetrievingClientDetails(status, message)) =>
            throw new RuntimeException(s"Client details lookup failed - status: '$status', error: '$message''")
        }
    }
  }

  private def existingRelationship(
    arn: Arn,
    service: String,
    clientIdType: String,
    clientId: String
  )(implicit request: RequestHeader): Future[(Option[String], Boolean)] = checkRelationshipsService
    .checkForRelationship(
      arn,
      service,
      clientIdType,
      clientId,
      None
    )
    .flatMap {
      case CheckRelationshipFound => Future.successful((Some(service), false))
      case CheckRelationshipNotFound(reason) if Seq(HMRCMTDIT, HMRCMTDITSUPP).contains(service) =>
        partialAuthRepository
          .findActive(
            service,
            Nino(clientId),
            arn
          )
          .map(auth => (auth.map(_ => service), reason.contains(relationshipNotFoundAlreadyCopied)))
      case CheckRelationshipNotFound(_) => Future.successful((None, false))
      case CheckRelationshipInvalidRequest => throw new RuntimeException("Unexpected error during relationship check")
    }

  private def pendingInvitation(
    arn: Arn,
    service: String,
    clientId: String
  ): Future[Boolean] = invitationsRepository
    .findAllForAgent(
      arn.value,
      Seq(service),
      Seq(clientId),
      isSuppliedClientId = true
    )
    .map(_.exists(_.status == Pending))

}
