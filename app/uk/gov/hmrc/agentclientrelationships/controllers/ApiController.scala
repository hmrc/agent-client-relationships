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

import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, ControllerComponents}
import uk.gov.hmrc.agentclientrelationships.audit.AuditService
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse._
import uk.gov.hmrc.agentclientrelationships.model.invitation._
import uk.gov.hmrc.agentclientrelationships.services.ApiService
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApiController @Inject() (
  apiService: ApiService,
  auditService: AuditService,
  val authConnector: AuthConnector,
  val appConfig: AppConfig,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir

  val apiSupportedServices: Seq[Service] = appConfig.apiSupportedServices

  private val strideRoles = Seq(appConfig.oldAuthStrideRole, appConfig.newAuthStrideRole)

  def createInvitation(arn: Arn): Action[JsValue] = Action.async(parse.json) { implicit request =>
    request.body
      .validate[ApiCreateInvitationRequest]
      .fold(
        errs => Future.successful(ApiErrorResults.InvalidPayload),
        apiCreateInvitationRequest =>
          apiService.createInvitation(arn, apiCreateInvitationRequest, apiSupportedServices).map { response =>
            response.fold(
              {
                // TODO WG - add audit for error cases
                // InputData Validation
                case UnsupportedService      => ApiErrorResults.UnsupportedService
                case InvalidClientId         => ApiErrorResults.ClientIdInvalidFormat
                case UnsupportedClientIdType => ApiErrorResults.ClientIdDoesNotMatchService

                // Get Agent, suspention check NoPermissionOnAgency
                case AgentSuspended                     => ApiErrorResults.AgentNotSubscribed
                case x @ ErrorRetrievingAgentDetails(_) => x.getResult("")

                // Get Client Data
                case ClientRegistrationNotFound         => ApiErrorResults.ClientRegistrationNotFound
                case ErrorRetrievingClientDetails(_, _) => ApiErrorResults.ClientRegistrationNotFound
                case VatClientInsolvent                 => ApiErrorResults.VatClientInsolvent

                // Get Pending, Create Invitation
                case _ @DuplicateAuthorisationRequest(invitationId) =>
                  ApiErrorResults.DuplicateAuthorisationRequest(invitationId)

                // RelationshipCheck
                case _ @DuplicateRelationshipRequest =>
                  ApiErrorResults.AlreadyAuthorised
                case ErrorRetrievingRelationships => InternalServerError("Retrieve relationship failed")

                // KnowFacts Checks
                case PostcodeDoesNotMatch | NotUkAddress      => ApiErrorResults.PostcodeDoesNotMatch
                case PostcodeFormatInvalid | PostcodeRequired => ApiErrorResults.PostcodeFormatInvalid
                case VatKnownFormatInvalid                    => ApiErrorResults.VatRegDateFormatInvalid
                case VatKnownFactNotMatched                   => ApiErrorResults.VatRegDateDoesNotMatch

                // Default for all others
                case _ => InternalServerError
              },
              invitation => {
                auditService.sendCreateInvitationAuditEvent(invitation)
                Created(Json.toJson(CreateInvitationResponse(invitation.invitationId)))
              }
            )
          }
      )
  }

}
