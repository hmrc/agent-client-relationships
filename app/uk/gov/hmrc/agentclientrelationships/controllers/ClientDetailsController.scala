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
import uk.gov.hmrc.agentclientrelationships.model.Pending
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentclientrelationships.services.ClientDetailsService
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Service}
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ClientDetailsController @Inject() (
  clientDetailsService: ClientDetailsService,
  relationshipsController: RelationshipsController,
  invitationsRepository: InvitationsRepository,
  partialAuthRepository: PartialAuthRepository,
  val authConnector: AuthConnector,
  cc: ControllerComponents
)(implicit appConfig: AppConfig, ec: ExecutionContext)
    extends BackendController(cc)
    with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir

  private def expectedResults(results: Seq[Result]): Boolean =
    results.forall(result => result.header.status == 200 | result.header.status == 404)

  private def existingRelationshipFound(results: Seq[Result]): Boolean =
    results.exists(result => result.header.status == 200)

  private val multiAgentServices: Map[String, String] = Map(HMRCMTDIT -> HMRCMTDITSUPP)

  def findClientDetails(service: String, clientId: String): Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsAgent { arn =>
      for {
        clientDetailsResponse <- clientDetailsService.findClientDetails(service, clientId)
        clientIdType = Service(service).supportedSuppliedClientIdType.enrolmentId
        pendingRelResponse <-
          invitationsRepository.findAllForAgent(arn.value, Seq(service), Seq(clientId), isSuppliedClientId = true)
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
        additionalInvitations <-
          if (service == HMRCMTDIT && !existingRelationshipFound(Seq(existingRelResponseMain, existingRelResponseSupp)))
            findAltItsaInvitations(Nino(clientId), arn)
          else Future(None)
      } yield clientDetailsResponse match {
        case Right(details) if expectedResults(Seq(existingRelResponseMain, existingRelResponseSupp)) =>
          val pendingRelationship = pendingRelResponse.exists(_.status == Pending)
          val existingRelationship =
            (existingRelResponseMain.header.status, existingRelResponseSupp.header.status) match {
              case (OK, _) => Some(service)
              case (_, OK) => Some(multiAgentServices(service))
              case _       => additionalInvitations
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

  private def findAltItsaInvitations(nino: Nino, arn: Arn): Future[Option[String]] = for {
    existingMain <- partialAuthRepository.find(HMRCMTDIT, nino, arn)
    existingSupp <- if (existingMain.isDefined) Future(None)
                    else partialAuthRepository.find(HMRCMTDITSUPP, nino, arn)
  } yield (existingMain, existingSupp) match {
    case (Some(_), _) => Some(HMRCMTDIT)
    case (_, Some(_)) => Some(HMRCMTDITSUPP)
    case (None, None) => None
  }
}
