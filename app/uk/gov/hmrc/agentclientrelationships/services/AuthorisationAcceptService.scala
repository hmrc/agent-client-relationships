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

package uk.gov.hmrc.agentclientrelationships.services

import play.api.Logging
import play.api.mvc.Request
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.AgentFiRelationshipConnector
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository.endedByClient
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP, HMRCPIR}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, MtdItIdType, Service}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Instant, LocalDateTime, ZoneId}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AuthorisationAcceptService @Inject() (
  appConfig: AppConfig,
  relationshipsService: CreateRelationshipsService,
  checkRelationshipsOrchestratorService: CheckRelationshipsOrchestratorService,
  deleteRelationshipsService: DeleteRelationshipsServiceWithAcr,
  invitationsRepository: InvitationsRepository,
  partialAuthRepository: PartialAuthRepository,
  agentFiRelationshipConnector: AgentFiRelationshipConnector
)(implicit ec: ExecutionContext)
    extends Logging {

  def accept(
    invitation: Invitation,
    enrolment: EnrolmentKey
  )(implicit
    hc: HeaderCarrier,
    request: Request[_],
    currentUser: CurrentUser,
    auditData: AuditData
  ) = {
    val timestamp = Instant.now
    val isAltItsa = Seq(HMRCMTDIT, HMRCMTDITSUPP).contains(invitation.service) &&
      invitation.clientId == invitation.suppliedClientId
    val nextStatus = if (isAltItsa) PartialAuth else Accepted
    for {
      _ <- manuallyDeleteSameAgentRelationship(invitation, isAltItsa)
      // Create relationship
      _ <- createRelationship(invitation, enrolment, isAltItsa, timestamp)
      // Create partial auth if alt itsa
      _ <- if (isAltItsa)
             partialAuthRepository.create(timestamp, Arn(invitation.arn), invitation.service, Nino(invitation.clientId))
           else Future.unit
      // Update invitation
      updated <- invitationsRepository.updateStatus(invitation.invitationId, nextStatus, Some(timestamp))
      // Deauth old invitations (putting a Future into a successful Future creates a non-blocking thread and lets the code continue)
      _ <- Future.successful(deauthExistingInvitations(invitation, isAltItsa, timestamp))
    } yield updated
  }

  private def manuallyDeleteSameAgentRelationship(invitation: Invitation, isAltItsa: Boolean)(implicit
    hc: HeaderCarrier,
    currentUser: CurrentUser,
    request: Request[_]
  ) =
    invitation.service match {
      case `HMRCMTDIT` | `HMRCMTDITSUPP` if isAltItsa =>
        // val serviceToCheck = Service.forId(if (invitation.service == HMRCMTDIT) HMRCMTDITSUPP else HMRCMTDIT)
        // TODO update existing partial auth to deauth
        Future.unit
      case `HMRCMTDIT` | `HMRCMTDITSUPP` =>
        val serviceToCheck = Service.forId(if (invitation.service == HMRCMTDIT) HMRCMTDITSUPP else HMRCMTDIT)
        checkRelationshipsOrchestratorService
          .checkForRelationship(
            Arn(invitation.arn),
            serviceToCheck.id,
            MtdItIdType.enrolmentId,
            invitation.clientId,
            None
          )
          .flatMap {
            case CheckRelationshipFound =>
              deleteRelationshipsService.deleteRelationship(
                Arn(invitation.arn),
                EnrolmentKey(
                  serviceToCheck,
                  MtdItId(invitation.clientId)
                ),
                None // TODO Fix
              )
            case _ => Future.unit
          }
      case _ => Future.unit
    }

  private def createRelationship(
    invitation: Invitation,
    enrolment: EnrolmentKey,
    isAltItsa: Boolean,
    timestamp: Instant
  )(implicit
    hc: HeaderCarrier,
    auditData: AuditData
  ) =
    invitation.service match {
      case _ if isAltItsa => Future.unit
      case `HMRCPIR` =>
        agentFiRelationshipConnector.createRelationship(
          Arn(invitation.arn),
          invitation.service,
          invitation.clientId,
          LocalDateTime.ofInstant(timestamp, ZoneId.systemDefault)
        )
      case _ =>
        relationshipsService
          .createRelationship(
            Arn(invitation.arn),
            enrolment,
            Set(),
            failIfCreateRecordFails = false,
            failIfAllocateAgentInESFails = true
          )
          .map(_.getOrElse(throw CreateRelationshipLocked))
    }

  private def deauthExistingInvitations(invitation: Invitation, isAltItsa: Boolean, timestamp: Instant) = {
    val acceptedStatus = if (isAltItsa) PartialAuth else Accepted
    (invitation.service match {
      case `HMRCMTDIT` => // Deauth any `main` agents and only same arn `supp` agents
        for {
          supp <- invitationsRepository
                    .findAllBy(
                      arn = Some(invitation.arn),
                      services = Seq(HMRCMTDITSUPP),
                      clientId = Some(invitation.clientId),
                      status = Some(acceptedStatus)
                    )
                    .fallbackTo(Future.successful(Nil))
          main <- invitationsRepository
                    .findAllBy(
                      services = Seq(HMRCMTDIT),
                      clientId = Some(invitation.clientId),
                      status = Some(acceptedStatus)
                    )
                    .fallbackTo(Future.successful(Nil))
        } yield supp ++ main
      case `HMRCMTDITSUPP` => // Deauth only same arn `main`/`supp` agents
        invitationsRepository
          .findAllBy(
            arn = Some(invitation.arn),
            services = Seq(HMRCMTDITSUPP, HMRCMTDIT),
            clientId = Some(invitation.clientId),
            status = Some(acceptedStatus)
          )
          .fallbackTo(Future.successful(Nil))
      case _ => // Deauth any `main` agent
        invitationsRepository
          .findAllBy(
            services = Seq(invitation.service),
            clientId = Some(invitation.clientId),
            status = Some(acceptedStatus)
          )
          .fallbackTo(Future.successful(Nil))
    })
      .map { acceptedInvitations: Seq[Invitation] =>
        acceptedInvitations
          .filterNot(_.invitationId == invitation.invitationId)
          .foreach(acceptedInvitation =>
            invitationsRepository.deauthInvitation(acceptedInvitation.invitationId, endedByClient, Some(timestamp))
          )
      }
  }

}

sealed trait AuthorisationResponseError extends Exception
case object CreateRelationshipLocked extends AuthorisationResponseError
