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
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.api.ApiCheckRelationshipRequest
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiFailureResponse._
import uk.gov.hmrc.agentclientrelationships.services._
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// This controller meant only for use by agent-authorisation-api
@Singleton
class ApiCheckRelationshipController @Inject() (
  agentAssuranceService: AgentAssuranceService,
  clientDetailsService: ClientDetailsService,
  checkRelationshipsService: CheckRelationshipsOrchestratorService,
  val authConnector: AuthConnector,
  val appConfig: AppConfig,
  cc: ControllerComponents
)(implicit val executionContext: ExecutionContext)
extends BackendController(cc)
with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir

  // scalastyle:off cyclomatic.complexity
  def checkRelationship(arn: Arn): Action[ApiCheckRelationshipRequest] =
    Action.async(parse.json[ApiCheckRelationshipRequest]) { implicit request =>
      agentAssuranceService.getNonSuspendedAgentRecord(arn).flatMap {
        case None => Future.successful(AgentSuspended.getResult)
        case Some(_) =>
          clientDetailsService.findClientDetails(request.body.service, request.body.suppliedClientId).flatMap {
            case Left(_) => Future.successful(ClientRegistrationNotFound.getResult)
            case Right(clientDetails) if !clientDetails.containsKnownFact(request.body.knownFact) => Future.successful(KnownFactDoesNotMatch.getResult)
            case Right(clientDetails) if clientDetails.status.nonEmpty => Future.successful(ClientInsolvent.getResult)
            case Right(_) =>
              checkRelationshipsService.checkForRelationship(
                arn,
                request.body.service,
                Service.forId(request.body.service).supportedSuppliedClientIdType.enrolmentId,
                request.body.suppliedClientId,
                None
              ).map {
                case CheckRelationshipFound => NoContent
                case CheckRelationshipNotFound(_) => RelationshipNotFound.getResult
                case _ => ApiInternalServerError.getResult
              }
          }
      }
    }

}
