/*
 * Copyright 2026 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.testOnly.controllers

import play.api.libs.json.Format
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.ControllerComponents
import play.api.mvc.Request
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.invitation.CreateInvitationRequest
import uk.gov.hmrc.agentclientrelationships.services.InvitationService
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.auth.core.AuthorisedFunctions
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class TestOnlyJourneySetupController @Inject() (
  controllerComponents: ControllerComponents,
  invitationService: InvitationService,
  val authConnector: AuthConnector
)(implicit ec: ExecutionContext)
extends BackendController(controllerComponents)
with AuthorisedFunctions {

  case class JourneySetupRequest(invitations: Seq[JourneySetupItem])

  object JourneySetupRequest {
    implicit val format: Format[JourneySetupRequest] = Json.format[JourneySetupRequest]
  }

  case class JourneySetupItem(
    arn: String,
    clientId: String,
    suppliedClientIdType: String,
    clientName: String,
    service: String,
    clientType: Option[String]
  )

  object JourneySetupItem {
    implicit val format: Format[JourneySetupItem] = Json.format[JourneySetupItem]
  }

  def createData: Action[JsValue] = Action(parse.json).async { implicit request: Request[JsValue] =>
    val setupRequest = request.body.as[JourneySetupRequest]

    Future.sequence(
      setupRequest.invitations.map(item =>
        invitationService.createInvitation(
          Arn(item.arn),
          CreateInvitationRequest(
            clientId = item.clientId,
            suppliedClientIdType = item.suppliedClientIdType,
            clientName = item.clientName,
            service = item.service,
            clientType = item.clientType
          )
        )
      )
    ).map(_ => Created(""))
  }

}
