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
  emailService: EmailService,
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
  ): Future[Option[Invitation]] = {
    val timestamp = Instant.now
    val isAltItsa = invitation.isAltItsa
    val nextStatus = if (isAltItsa) PartialAuth else Accepted
    for {
      // Remove existing main/supp relationship for arn when changing for same client
      _ <- deleteSameAgentRelationshipForItsa(
             invitation.service,
             invitation.arn,
             invitation.clientId,
             isAltItsa,
             timestamp
           )
      // Create relationship
      _ <- createRelationship(invitation, enrolment, isAltItsa, timestamp)
      // Update invitation
      updated <- invitationsRepository.updateStatus(invitation.invitationId, nextStatus, Some(timestamp))
      // Deauth previously accepted invitations (non blocking)
      _ <- Future.successful(
             deauthAcceptedInvitations(
               invitation.service,
               None,
               invitation.clientId,
               Some(invitation.invitationId),
               isAltItsa,
               timestamp
             )
           )
      // Accept confirmation email (non blocking)
      _ <- Future.successful(emailService.sendAcceptedEmail(invitation))
    } yield updated
  }

  def deleteSameAgentRelationshipForItsa(
    service: String,
    arn: String,
    clientId: String,
    isAltItsa: Boolean,
    timestamp: Instant = Instant.now()
  )(implicit
    hc: HeaderCarrier,
    currentUser: CurrentUser,
    request: Request[_]
  ): Future[Boolean] =
    service match {
      case `HMRCMTDIT` | `HMRCMTDITSUPP` =>
        val serviceToCheck = Service.forId(if (service == HMRCMTDIT) HMRCMTDITSUPP else HMRCMTDIT)
        for {
          // Attempt to remove existing alt itsa partial auth
          altItsa <- partialAuthRepository.deauthorise(serviceToCheck.id, Nino(clientId), Arn(arn), timestamp)
          // Check for existing itsa relationship
          itsa <- checkRelationshipsOrchestratorService
                    .checkForRelationship(Arn(arn), serviceToCheck.id, MtdItIdType.enrolmentId, clientId, None)
          // Remove existing itsa relationship
          _ <- if (itsa == CheckRelationshipFound)
                 deleteRelationshipsService.deleteRelationship(
                   Arn(arn),
                   EnrolmentKey(
                     serviceToCheck,
                     MtdItId(clientId)
                   ),
                   currentUser.affinityGroup
                 )
               else Future.unit
          // Clean up accepted invitations
          _ <- Future.successful(
                 deauthAcceptedInvitations(serviceToCheck.id, Some(arn), clientId, None, isAltItsa, timestamp)
               )
        } yield altItsa || (itsa == CheckRelationshipFound)
      case _ => Future.successful(false)
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
      case `HMRCMTDITSUPP` if isAltItsa =>
        partialAuthRepository.create(
          timestamp,
          Arn(invitation.arn),
          invitation.service,
          Nino(invitation.clientId)
        )
      case `HMRCMTDIT` if isAltItsa =>
        for {
          optMainAuth <- partialAuthRepository.findMainAgent(invitation.clientId)
          _ <- optMainAuth.fold(Future.successful(false)) { mainAuth =>
                 partialAuthRepository.deauthorise(mainAuth.service, Nino(mainAuth.nino), Arn(mainAuth.arn), timestamp)
               }
          newAuth <- partialAuthRepository.create(
                       timestamp,
                       Arn(invitation.arn),
                       invitation.service,
                       Nino(invitation.clientId)
                     )
        } yield newAuth
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
        clientId = Some(clientId),
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
