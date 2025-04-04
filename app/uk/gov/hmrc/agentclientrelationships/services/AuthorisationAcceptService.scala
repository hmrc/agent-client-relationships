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
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys._
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.connectors.AgentFiRelationshipConnector
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository.endedByClient
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP, HMRCPIR}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Instant, LocalDateTime, ZoneId}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

// scalastyle:off method.length
@Singleton
class AuthorisationAcceptService @Inject() (
  createRelationshipsService: CreateRelationshipsService,
  emailService: EmailService,
  itsaDeauthAndCleanupService: ItsaDeauthAndCleanupService,
  invitationsRepository: InvitationsRepository,
  partialAuthRepository: PartialAuthRepository,
  agentFiRelationshipConnector: AgentFiRelationshipConnector,
  auditService: AuditService
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
  ): Future[Option[Invitation]] = {
    val timestamp = Instant.now
    val isAltItsa = invitation.isAltItsa
    val isItsa = Seq(HMRCMTDIT, HMRCMTDITSUPP).contains(invitation.service)
    val nextStatus = if (isAltItsa) PartialAuth else Accepted
    for {
      // Remove existing main/supp relationship for arn when changing between main/supp
      _ <- if (isItsa)
             itsaDeauthAndCleanupService.deleteSameAgentRelationship(
               invitation.service,
               invitation.arn,
               if (isAltItsa) None else Some(invitation.clientId),
               invitation.suppliedClientId,
               timestamp
             )
           else Future.unit
      // Create relationship
      _ <- createRelationship(invitation, enrolment, isAltItsa, timestamp)
      // Update invitation
      updated <- invitationsRepository.updateStatus(invitation.invitationId, nextStatus, Some(timestamp))
      // Deauth previously accepted invitations (non blocking)
      _ <- Future.successful(
             if (invitation.service != HMRCMTDITSUPP)
               deauthAcceptedInvitations(
                 invitation.service,
                 None,
                 invitation.clientId,
                 Some(invitation.invitationId),
                 isAltItsa,
                 timestamp
               )
             else Future.unit
           )
      // Deauth previously accepted alt itsa invitations in case the client is mtd itsa (non blocking)
      _ <- Future.successful(
             if (invitation.service == HMRCMTDIT && !isAltItsa)
               deauthAcceptedInvitations(
                 invitation.service,
                 None,
                 invitation.suppliedClientId,
                 Some(invitation.invitationId),
                 isAltItsa = true,
                 timestamp
               )
             else Future.unit
           )
      // Accept confirmation email (non blocking)
      _ <- Future.successful(emailService.sendAcceptedEmail(invitation))
    } yield updated
  }

  private def createRelationship(
    invitation: Invitation,
    enrolment: EnrolmentKey,
    isAltItsa: Boolean,
    timestamp: Instant
  )(implicit
    hc: HeaderCarrier,
    request: Request[Any],
    currentUser: CurrentUser,
    auditData: AuditData
  ) = {
    auditData.set(
      if (isAltItsa) howPartialAuthCreatedKey else howRelationshipCreatedKey,
      if (currentUser.isStride) hmrcAcceptedInvitation else clientAcceptedInvitation
    )

    invitation.service match {
      case `HMRCMTDITSUPP` if isAltItsa => // Does not need to deauthorise current agent
        partialAuthRepository
          .create(
            timestamp,
            Arn(invitation.arn),
            invitation.service,
            Nino(invitation.clientId)
          )
          .andThen(_ => auditService.sendCreatePartialAuthAuditEvent)
      case `HMRCMTDIT` if isAltItsa => // Deauthorises current agent by updating partial auth
        deauthPartialAuth(invitation.clientId, timestamp).flatMap { _ =>
          partialAuthRepository
            .create(
              timestamp,
              Arn(invitation.arn),
              invitation.service,
              Nino(invitation.clientId)
            )
            .andThen(_ => auditService.sendCreatePartialAuthAuditEvent)
        }
      case `HMRCMTDIT` => // Create relationship automatically deauthorises current itsa agent, manually deauth alt itsa for this nino as a precaution
        deauthPartialAuth(invitation.suppliedClientId, timestamp).flatMap { _ =>
          createRelationshipsService
            .createRelationship(
              Arn(invitation.arn),
              enrolment,
              Set(),
              failIfCreateRecordFails = false,
              failIfAllocateAgentInESFails = true
            )
            .map(_.getOrElse(throw CreateRelationshipLocked))
        }
      case `HMRCPIR` => // AFI handles its own deauthorisations
        agentFiRelationshipConnector
          .createRelationship(
            Arn(invitation.arn),
            invitation.service,
            invitation.clientId,
            LocalDateTime.ofInstant(timestamp, ZoneId.systemDefault)
          )
          .andThen(_ => auditService.sendCreateRelationshipAuditEvent)
      case _ => // Create relationship automatically deauthorises current agents except for itsa-supp
        createRelationshipsService
          .createRelationship(
            Arn(invitation.arn),
            enrolment,
            Set(),
            failIfCreateRecordFails = false,
            failIfAllocateAgentInESFails = true
          )
          .map(_.getOrElse(throw CreateRelationshipLocked))
    }
  }

  private def deauthPartialAuth(nino: String, timestamp: Instant)(implicit hc: HeaderCarrier, request: Request[Any]) =
    partialAuthRepository.findMainAgent(nino).flatMap {
      case Some(mainAuth) =>
        partialAuthRepository.deauthorise(mainAuth.service, Nino(mainAuth.nino), Arn(mainAuth.arn), timestamp).map {
          result =>
            if (result) {
              implicit val auditData: AuditData = new AuditData()
              auditData.set(howPartialAuthTerminatedKey, agentReplacement)
              auditService.sendTerminatePartialAuthAuditEvent(
                mainAuth.arn,
                mainAuth.service,
                mainAuth.nino
              )
            }
            result
        }
      case None => Future.successful(false)
    }

  private def deauthAcceptedInvitations(
    service: String,
    optArn: Option[String],
    clientId: String,
    optInvitationId: Option[String],
    isAltItsa: Boolean,
    timestamp: Instant
  ) = {
    val acceptedStatus = if (isAltItsa) PartialAuth else Accepted
    invitationsRepository
      .findAllBy(
        arn = optArn,
        services = Seq(service),
        clientIds = Seq(clientId),
        status = Some(acceptedStatus)
      )
      .fallbackTo(Future.successful(Nil))
      .map { acceptedInvitations: Seq[Invitation] =>
        acceptedInvitations
          .filterNot(invitation => optInvitationId.contains(invitation.invitationId))
          .foreach(acceptedInvitation =>
            invitationsRepository.deauthInvitation(acceptedInvitation.invitationId, endedByClient, Some(timestamp))
          )
      }
  }

}

sealed trait AuthorisationResponseError extends Exception
case object CreateRelationshipLocked extends AuthorisationResponseError
