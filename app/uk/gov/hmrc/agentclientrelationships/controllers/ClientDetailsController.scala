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
import play.api.mvc.{Action, AnyContent, ControllerComponents, Result}
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.services.ClientDetailsService
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP}
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ClientDetailsController @Inject() (
  clientDetailsService: ClientDetailsService,
  relationshipsController: RelationshipsController,
  invitationsRepository: InvitationsRepository,
  val authConnector: AuthConnector,
  cc: ControllerComponents
)(implicit appConfig: AppConfig, ec: ExecutionContext)
    extends BackendController(cc)
    with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServices

  private def expectedResults(results: Seq[Result]): Boolean =
    results.forall(result => result.header.status == 200 | result.header.status == 404)

  private val multiAgentServices: Map[String, String] = Map(HMRCMTDIT -> HMRCMTDITSUPP)

  def findClientDetails(service: String, clientId: String): Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsAgent { arn =>
      for {
        clientDetailsResponse <- clientDetailsService.findClientDetails(service, clientId)
        clientIdType = Service(service).supportedSuppliedClientIdType.enrolmentId
        pendingRelResponse <- invitationsRepository.findAllForAgent(arn.value)
        existingRelResponseMain <-
          relationshipsController.checkForRelationship(arn, service, clientIdType, clientId, None)(request)
        existingRelResponseSupp <- if (multiAgentServices.contains(service))
                                     relationshipsController.checkForRelationship(
                                       arn,
                                       multiAgentServices(service),
                                       clientIdType,
                                       clientId,
                                       None
                                     )(request)
                                   else Future(NotFound)
      } yield clientDetailsResponse match {
        case Right(details) if expectedResults(Seq(existingRelResponseMain, existingRelResponseSupp)) =>
          val pendingRelationship = pendingRelResponse.exists(inv => inv.service == service && inv.clientId == clientId)
          val existingRelationship =
            (existingRelResponseMain.header.status, existingRelResponseSupp.header.status) match {
              case (OK, _) => Some(service)
              case (_, OK) => Some(multiAgentServices(service))
              case _       => None
            }
          val response = details.copy(
            hasPendingInvitation = pendingRelationship,
            hasExistingRelationshipFor = existingRelationship
          )
          Ok(Json.toJson(response))
        case Left(ClientDetailsNotFound) => NotFound
        case _                           => InternalServerError
      }
    }
  }
}
