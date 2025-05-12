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
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDIT
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDITSUPP
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.auth.core.AuthorisedFunctions
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class LookupInvitationsController @Inject() (
  invitationsRepository: InvitationsRepository,
  partialAuthRepository: PartialAuthRepository,
  val authConnector: AuthConnector,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
extends BackendController(cc)
with AuthorisedFunctions {

  def lookupInvitations(
    arn: Option[Arn],
    services: Seq[String],
    clientIds: Seq[String],
    status: Option[InvitationStatus]
  ): Action[AnyContent] = Action.async { implicit request =>
    authorised() {
      if (arn.isEmpty && services.isEmpty && clientIds.isEmpty && status.isEmpty) {
        Future.successful(BadRequest)
      }
      else {
        for {
          invitations <- invitationsRepository.findAllBy(
            arn.map(_.value),
            services,
            clientIds,
            status
          )
          partialAuths <- lookupPartialAuths(
            arn,
            services,
            clientIds,
            status,
            invitations
          )
          combined = (invitations ++ partialAuths.map(_.asInvitation)).sortBy(_.created)
          result =
            combined match {
              case Nil => NotFound
              case _ => Ok(Json.toJson(combined))
            }
        } yield result
      }
    }
  }

  def lookupInvitation(invitationId: String): Action[AnyContent] = Action.async { implicit request =>
    authorised() {
      invitationsRepository
        .findOneById(invitationId)
        .map {
          case None => NotFound
          case Some(invitation) => Ok(Json.toJson(invitation))
        }
    }
  }

  private def arePartialAuthsNeeded(
    services: Seq[String],
    clientIds: Seq[String],
    status: Option[InvitationStatus]
  ) =
    (status.contains(PartialAuth) || status.contains(DeAuthorised) || status.isEmpty) &&
      (clientIds.exists(Nino.isValid) || clientIds.isEmpty) &&
      (services.exists(Seq(HMRCMTDIT, HMRCMTDITSUPP).contains(_)) || services.isEmpty)

  private[controllers] def lookupPartialAuths(
    arn: Option[Arn],
    services: Seq[String],
    clientIds: Seq[String],
    status: Option[InvitationStatus],
    existingInvitations: Seq[Invitation]
  ): Future[Seq[PartialAuthRelationship]] =
    if (
      arePartialAuthsNeeded(
        services,
        clientIds,
        status
      )
    ) {
      val itsaServices = services.filter(Seq(HMRCMTDIT, HMRCMTDITSUPP).contains(_))
      val optNino = clientIds.find(Nino.isValid)
      val isActive = status.map {
        case PartialAuth => true
        case DeAuthorised => false
      }

      partialAuthRepository
        .findAllBy(
          arn.map(_.value),
          itsaServices,
          optNino,
          isActive
        )
        .map { partialAuths =>
          partialAuths.filterNot(auth =>
            existingInvitations.exists { invitation =>
              invitation.arn == auth.arn && invitation.service == auth.service && invitation.clientId == auth.nino &&
              ((invitation.status == PartialAuth && auth.active) || (invitation.status == DeAuthorised && !auth.active))
            }
          )
        }
    }
    else {
      Future.successful(Nil)
    }

}
