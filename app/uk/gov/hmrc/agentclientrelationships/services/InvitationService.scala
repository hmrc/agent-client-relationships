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

import cats.data.EitherT
import org.mongodb.scala.MongoException
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.{AgentAssuranceConnector, IfOrHipConnector}
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.{DuplicateInvitationError, NoPendingInvitation}
import uk.gov.hmrc.agentclientrelationships.model.invitation.{CreateInvitationRequest, InvitationFailureResponse}
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgencyDetails
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentKey, Invitation, Rejected, TrackRequestsResult}
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{MtdIt, MtdItSupp}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, ClientIdentifier, NinoType, Service}
import uk.gov.hmrc.domain.Nino
import play.api.mvc.RequestHeader

import java.time.{Instant, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class InvitationService @Inject() (
  invitationsRepository: InvitationsRepository,
  partialAuthRepository: PartialAuthRepository,
  ifOrHipConnector: IfOrHipConnector,
  agentAssuranceConnector: AgentAssuranceConnector,
  emailService: EmailService,
  appConfig: AppConfig
)(implicit ec: ExecutionContext)
    extends Logging {

  def trackRequests(
    arn: Arn,
    statusFilter: Option[String],
    clientName: Option[String],
    pageNumber: Int,
    pageSize: Int
  ): Future[TrackRequestsResult] =
    invitationsRepository.trackRequests(arn.value, statusFilter, clientName, pageNumber, pageSize)

  def createInvitation(arn: Arn, createInvitationInputData: CreateInvitationRequest)(implicit
    request: RequestHeader
  ): Future[Either[InvitationFailureResponse, Invitation]] = {
    val invitationT =
      for {

        suppliedClientId <- EitherT.fromEither[Future](createInvitationInputData.getSuppliedClientId)
        service          <- EitherT.fromEither[Future](createInvitationInputData.getService)
        clientType       <- EitherT.fromEither[Future](createInvitationInputData.getClientType)
        agentRecord      <- EitherT.right(agentAssuranceConnector.getAgentRecordWithChecks(arn))
        clientId         <- EitherT(getClientId(suppliedClientId, service))
        invitation <- EitherT(
                        create(
                          arn,
                          service,
                          clientId,
                          suppliedClientId,
                          createInvitationInputData.clientName,
                          clientType,
                          agentRecord.agencyDetails
                        )
                      )
      } yield invitation

    invitationT.value
  }

  def findInvitationForAgent(arn: String, invitationId: String): Future[Option[Invitation]] =
    invitationsRepository.findOneByIdForAgent(arn, invitationId)

  def findInvitation(invitationId: String): Future[Option[Invitation]] = invitationsRepository.findOneById(invitationId)

  def rejectInvitation(
    invitationId: String
  )(implicit ec: ExecutionContext, request: RequestHeader): Future[Invitation] =
    for {
      invitation <- invitationsRepository.updateStatus(invitationId, Rejected)
      _          <- emailService.sendRejectedEmail(invitation)
    } yield invitation

  def cancelInvitation(arn: Arn, invitationId: String): Future[Unit] =
    invitationsRepository.cancelByIdForAgent(arn.value, invitationId)

  def deauthoriseInvitation(arn: Arn, enrolmentKey: EnrolmentKey, endedBy: String)(implicit
    ec: ExecutionContext
  ): Future[Boolean] =
    invitationsRepository
      .deauthorise(arn.value, enrolmentKey.oneIdentifier().value, enrolmentKey.service, endedBy)
      .map(_.fold(false)(_ => true))

  def findInvitationForClient(invitationId: String): Future[Option[Invitation]] =
    invitationsRepository.findOneByIdForClient(invitationId)

  def findNonSuspendedClientInvitations(services: Seq[String], clientIds: Seq[String])(implicit
    request: RequestHeader
  ): Future[Seq[Invitation]] = {
    def getSuspendedArns(arns: Seq[String]) =
      Future
        .sequence(arns.map { arn =>
          agentAssuranceConnector
            .getAgentRecordWithChecks(Arn(arn))
            .map(record =>
              if (record.suspensionDetails.exists(_.suspensionStatus))
                Some(arn)
              else
                None
            )
        })
        .map(_.flatten)

    for {
      invitations   <- invitationsRepository.findAllBy(arn = None, services, clientIds, status = None)
      suspendedArns <- getSuspendedArns(invitations.map(_.arn).distinct)
    } yield invitations.filterNot(invitation => suspendedArns.contains(invitation.arn))
  }

  def findAllForAgent(
    arn: String,
    services: Set[String],
    clientIds: Seq[String],
    isSuppliedClientId: Boolean = false
  ): Future[Seq[Invitation]] = invitationsRepository.findAllForAgent(arn, services.toSeq, clientIds, isSuppliedClientId)

  def updateInvitation(
    service: String,
    clientId: String,
    clientIdType: String,
    newService: String,
    newClientId: String,
    newClientIdType: String
  ): Future[Boolean] =
    invitationsRepository.updateInvitation(service, clientId, clientIdType, newService, newClientId, newClientIdType)

  private def getClientId(suppliedClientId: ClientId, service: Service)(implicit
    request: RequestHeader
  ): Future[Either[InvitationFailureResponse, ClientId]] =
    (service, suppliedClientId.typeId) match {
      case (MtdIt | MtdItSupp, NinoType.id) =>
        ifOrHipConnector
          .getMtdIdFor(Nino(suppliedClientId.value))
          .map(
            _.fold[Either[InvitationFailureResponse, ClientId]](Right(suppliedClientId))(mdtId =>
              Right(ClientIdentifier(mdtId))
            )
          )
      case _ => Future successful Right(suppliedClientId)
    }

  private def create(
    arn: Arn,
    service: Service,
    clientId: ClientId,
    suppliedClientId: ClientId,
    clientName: String,
    clientType: Option[String],
    agentDetails: AgencyDetails
  )(implicit ec: ExecutionContext): Future[Either[InvitationFailureResponse, Invitation]] = {
    val expiryDate = currentTime().plusSeconds(invitationExpiryDuration.toSeconds).toLocalDate
    (for {
      invitation <- invitationsRepository.create(
                      arn.value,
                      service,
                      clientId,
                      suppliedClientId,
                      clientName,
                      agentDetails.agencyName,
                      agentDetails.agencyEmail,
                      expiryDate,
                      clientType
                    )
    } yield {
      logger.info(s"""Created invitation with id: "${invitation.invitationId}".""")
      Right(invitation)
    }).recover {
      case e: MongoException if e.getMessage.contains("E11000 duplicate key error") => Left(DuplicateInvitationError)
    }
  }

  def migratePartialAuth(invitation: Invitation): Future[Unit] =
    for {
      _ <- partialAuthRepository
             .create(invitation.created, Arn(invitation.arn), invitation.service, Nino(invitation.clientId))
      _ <-
        if (invitation.expiryDate.isAfter(currentTime().toLocalDate)) {
          invitationsRepository.migrateActivePartialAuthInvitation(invitation).map(_ => ())
        } else
          Future.unit
    } yield ()

  private def currentTime() = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime

  private val invitationExpiryDuration = appConfig.invitationExpiringDuration

}
