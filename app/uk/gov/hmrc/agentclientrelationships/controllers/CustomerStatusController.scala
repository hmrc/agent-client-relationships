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

import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.AgentFiRelationshipConnector
import uk.gov.hmrc.agentclientrelationships.model.CustomerStatus
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentsWithNino
import uk.gov.hmrc.agentclientrelationships.model.Pending
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.services.FindRelationshipsService
import uk.gov.hmrc.agentclientrelationships.services.InvitationService
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class CustomerStatusController @Inject() (
  findRelationshipsService: FindRelationshipsService,
  invitationsService: InvitationService,
  agentFiRelationshipConnector: AgentFiRelationshipConnector,
  partialAuthRepository: PartialAuthRepository,
  val authConnector: AuthConnector,
  appConfig: AppConfig,
  cc: ControllerComponents
)(implicit val executionContext: ExecutionContext)
    extends BackendController(cc)
    with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServices

  def customerStatus: Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsClientWithNino { authResponse: EnrolmentsWithNino =>
      val services = authResponse.getIdentifierMap(supportedServices).keys.toSeq.map(_.id)
      val identifiers = authResponse.getIdentifierMap(supportedServices).values.toSeq.map(_.value)
      for {
        invitations <- invitationsService.findNonSuspendedClientInvitations(services, identifiers)
        partialAuthRecords <-
          authResponse.getNino match {
            case Some(ni) => partialAuthRepository.findByNino(Nino(ni))
            case None     => Future.successful(None)
          }
        irvRelationshipExists <-
          authResponse.getNino match {
            case Some(nino) => agentFiRelationshipConnector.findIrvRelationshipForClient(nino).map(_.nonEmpty)
            case None       => Future.successful(false)
          }
        existingRelationships <-
          if (partialAuthRecords.exists(_.active) || irvRelationshipExists) {
            Future.successful(true)
          } else {
            findRelationshipsService
              .getActiveRelationshipsForClient(authResponse.getIdentifierMap(supportedServices))
              .map(_.nonEmpty)
          }
      } yield Ok(
        Json.toJson(
          CustomerStatus(
            hasPendingInvitations = invitations.exists(_.status == Pending),
            hasInvitationsHistory = invitations.nonEmpty || partialAuthRecords.nonEmpty,
            hasExistingRelationships = existingRelationships
          )
        )
      )
    }
  }

}
