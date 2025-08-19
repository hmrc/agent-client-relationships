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

import cats.data.EitherT
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.invitation._
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.services.AgentAssuranceService
import uk.gov.hmrc.agentclientrelationships.services.InvitationLinkService
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class ApiGetInvitationController @Inject() (
  invitationLinkService: InvitationLinkService,
  agentAssuranceService: AgentAssuranceService,
  invitationsRepository: InvitationsRepository,
  val appConfig: AppConfig,
  cc: ControllerComponents,
  val authConnector: AuthConnector
)(implicit val executionContext: ExecutionContext)
extends BackendController(cc)
with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir

  val apiSupportedServices: Seq[Service] = appConfig.apiSupportedServices

  def getInvitation(
    arn: Arn,
    invitationId: String
  ): Action[AnyContent] = Action.async { implicit request =>
    authorised() {
      findInvitationForAgent(
        arn,
        invitationId,
        apiSupportedServices
      ).map {
        case Right(apiInvitationResponse) => Ok(Json.toJson(apiInvitationResponse))
        case Left(apiErrorResults) => apiErrorResults.getResult
      }
    }
  }

  def findInvitationForAgent(
    arn: Arn,
    invitationId: String,
    supportedServices: Seq[Service]
  )(implicit
    request: RequestHeader
  ): Future[Either[ApiFailureResponse, ApiInvitationResponse]] =
    (for {
      agentRecord <- EitherT.fromOptionF(agentAssuranceService.getNonSuspendedAgentRecord(arn), ApiFailureResponse.AgentSuspended)
      newNormaliseAgentName = invitationLinkService.normaliseAgentName(agentRecord.agencyDetails.agencyName)
      agentReferenceRecord <- EitherT.right[ApiFailureResponse](
        invitationLinkService.getAgentReferenceRecordByArn(arn = arn, newNormaliseAgentName = newNormaliseAgentName)
      )

      invitation <- EitherT(findInvitation(
        invitationId,
        arn,
        supportedServices
      ))

    } yield ApiInvitationResponse.createApiInvitationResponse(
      invitation,
      agentReferenceRecord.uid,
      newNormaliseAgentName
    )).value

  private def findInvitation(
    invitationId: String,
    arn: Arn,
    supportedServices: Seq[Service]
  ): Future[Either[ApiFailureResponse, Invitation]] = invitationsRepository
    .findOneById(invitationId)
    .map {
      _.fold[Either[ApiFailureResponse, Invitation]](Left(ApiFailureResponse.InvitationNotFound))(invitation =>
        if (invitation.arn == arn.value)
          if (supportedServices.map(_.id).contains(invitation.service))
            Right(invitation)
          else
            Left(ApiFailureResponse.UnsupportedService)
        else
          Left(ApiFailureResponse.NoPermissionOnAgency)
      )
    }

}
