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
import uk.gov.hmrc.agentclientrelationships.model.CleanUpInvitationStatusRequest
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.services.InvitationService
import uk.gov.hmrc.agentclientrelationships.services.ValidationService
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class CleanUpInvitationStatusController @Inject() (
  validationService: ValidationService,
  invitationService: InvitationService,
  val appConfig: AppConfig,
  val authConnector: AuthConnector,
  cc: ControllerComponents
)(implicit val executionContext: ExecutionContext)
extends BackendController(cc)
with AuthActions
with RequestAwareLogging {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir
  val apiSupportedServices: Seq[Service] = appConfig.apiSupportedServices

  def deauthoriseInvitation: Action[CleanUpInvitationStatusRequest] =
    Action.async(parse.json[CleanUpInvitationStatusRequest]) { implicit request =>
      authorised() {
        val payload = request.body

        validationService.validateForEnrolmentKey(
          payload.service,
          Service(payload.service).supportedClientIdType.enrolmentId,
          payload.clientId
        ).flatMap {
          case Right(enrolment) =>
            invitationService.deauthoriseInvitation(
              arn = Arn(payload.arn),
              enrolmentKey = enrolment,
              endedBy = "HMRC"
            ).map {
              case true => NoContent
              case false => NotFound
            }
          case Left(_) => Future.successful(BadRequest)
        }
      }
    }

}
