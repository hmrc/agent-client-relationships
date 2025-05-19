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
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.AgentAssuranceConnector
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.invitation._
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.services.InvitationLinkService
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class ApiGetInvitationsController @Inject() (
  invitationLinkService: InvitationLinkService,
  agentAssuranceConnector: AgentAssuranceConnector,
  invitationsRepository: InvitationsRepository,
  val appConfig: AppConfig,
  cc: ControllerComponents
)(implicit val executionContext: ExecutionContext)
extends BackendController(cc) {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir

  val apiSupportedServices: Seq[Service] = appConfig.apiSupportedServices

  def getInvitations(arn: Arn): Action[AnyContent] = Action.async { implicit request =>
    findAllInvitationsForAgent(arn, apiSupportedServices).map {
      case Right(apiBulkInvitationsResponse) => Ok(Json.toJson(apiBulkInvitationsResponse))
      case Left(apiErrorResults) => apiErrorResults.getResult
    }
  }

  def findAllInvitationsForAgent(
    arn: Arn,
    supportedServices: Seq[Service]
  )(implicit
    request: RequestHeader
  ): Future[Either[ApiFailureResponse, ApiBulkInvitationsResponse]] =
    (for {
      agentRecord <- EitherT(getAgentDetailsByArn(arn))
      newNormaliseAgentName = invitationLinkService.normaliseAgentName(agentRecord.agencyDetails.agencyName)
      agentReferenceRecord <- EitherT.right[ApiFailureResponse](
        invitationLinkService.getAgentReferenceRecordByArn(arn = arn, newNormaliseAgentName = newNormaliseAgentName)
      )
      invitations <- EitherT.right[ApiFailureResponse](findAllInvitationForArn(arn, supportedServices))
    } yield ApiBulkInvitationsResponse.createApiBulkInvitationsResponse(
      invitations,
      agentReferenceRecord.uid,
      newNormaliseAgentName
    )).value

  private def findAllInvitationForArn(
    arn: Arn,
    supportedServices: Seq[Service]
  ): Future[Seq[Invitation]] = invitationsRepository
    .findAllForAgentService(arn = arn.value, services = supportedServices.map(_.id))

  private def getAgentDetailsByArn(arn: Arn)(implicit
    requestHeader: RequestHeader
  ): Future[Either[ApiFailureResponse, AgentDetailsDesResponse]] = agentAssuranceConnector
    .getAgentRecordWithChecks(arn)
    .map { agentRecord =>
      if (agentIsSuspended(agentRecord))
        Left(ApiFailureResponse.AgentSuspended)
      else
        Right(agentRecord)
    }
    .recover { case ex: Throwable => Left(ApiFailureResponse.ApiInternalServerError(ex.getMessage)) }

  private def agentIsSuspended(agentRecord: AgentDetailsDesResponse): Boolean = agentRecord.suspensionDetails.exists(_.suspensionStatus)

}
