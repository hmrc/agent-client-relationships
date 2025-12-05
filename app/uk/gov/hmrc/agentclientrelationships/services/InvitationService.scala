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

package uk.gov.hmrc.agentclientrelationships.services

import cats.data.EitherT
import org.mongodb.scala.MongoException
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.HipConnector
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.Rejected
import uk.gov.hmrc.agentclientrelationships.model.TrackRequestsResult
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiFailureResponse.InvalidInvitationStatus
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiFailureResponse.InvitationNotFound
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiFailureResponse.NoPermissionOnAgency
import uk.gov.hmrc.agentclientrelationships.model.invitation.CancelInvitationResponse._
import uk.gov.hmrc.agentclientrelationships.model.invitation.ApiFailureResponse
import uk.gov.hmrc.agentclientrelationships.model.invitation.CreateInvitationRequest
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.DuplicateInvitationError
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgencyDetails
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.agentclientrelationships.model.identifiers.ClientIdentifier.ClientId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.ClientIdentifier
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoType
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdIt
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdItSupp
import uk.gov.hmrc.domain.Nino

import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class InvitationService @Inject() (
  invitationsRepository: InvitationsRepository,
  hipConnector: HipConnector,
  agentAssuranceService: AgentAssuranceService,
  emailService: EmailService,
  appConfig: AppConfig
)(implicit ec: ExecutionContext)
extends RequestAwareLogging {

  def trackRequests(
    arn: Arn,
    statusFilter: Option[String],
    clientName: Option[String],
    pageNumber: Int,
    pageSize: Int
  ): Future[TrackRequestsResult] = invitationsRepository.trackRequests(
    arn.value,
    statusFilter,
    clientName,
    pageNumber,
    pageSize
  )

  def createInvitation(
    arn: Arn,
    createInvitationInputData: CreateInvitationRequest
  )(implicit request: RequestHeader): Future[Either[InvitationFailureResponse, Invitation]] = {
    val invitationT =
      for {

        suppliedClientId <- EitherT.fromEither[Future](createInvitationInputData.getSuppliedClientId)
        service <- EitherT.fromEither[Future](createInvitationInputData.getService)
        clientType <- EitherT.fromEither[Future](createInvitationInputData.getClientType)
        agentRecord <- EitherT.right(agentAssuranceService.getAgentRecord(arn))
        clientId <- EitherT(getClientId(suppliedClientId, service))
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

  def findInvitationForAgent(
    arn: String,
    invitationId: String
  ): Future[Option[Invitation]] = invitationsRepository.findOneByIdForAgent(arn, invitationId)

  def findInvitation(invitationId: String): Future[Option[Invitation]] = invitationsRepository.findOneById(invitationId)

  def rejectInvitation(invitationId: String)(implicit
    request: RequestHeader
  ): Future[Invitation] =
    for {
      invitation <- invitationsRepository.updateStatus(invitationId, Rejected)
      _ <- emailService.sendRejectedEmail(invitation)
    } yield invitation

  def cancelInvitation(
    arn: Arn,
    invitationId: String
  )(implicit ec: ExecutionContext): Future[Either[ApiFailureResponse, Unit]] = invitationsRepository.cancelByIdForAgent(arn.value, invitationId).map {
    case Success => Right(())
    case NotFound => Left(InvitationNotFound)
    case NoPermission => Left(NoPermissionOnAgency)
    case WrongInvitationStatus => Left(InvalidInvitationStatus)
  }

  def deauthoriseInvitation(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    endedBy: String
  ): Future[Boolean] = invitationsRepository
    .deauthAcceptedInvitation(
      enrolmentKey.service,
      enrolmentKey.oneIdentifier().value,
      arn.value,
      endedBy
    )

  def findInvitationForClient(invitationId: String): Future[Option[Invitation]] = invitationsRepository
    .findOneById(invitationId)

  def findNonSuspendedClientInvitations(
    services: Seq[String],
    clientIds: Seq[String]
  )(implicit request: RequestHeader): Future[Seq[Invitation]] = {
    def getSuspendedArns(arns: Seq[String]) = Future
      .sequence(
        arns.map { arn =>
          agentAssuranceService.getNonSuspendedAgentRecord(Arn(arn))
            .map {
              case None => Some(arn)
              case Some(_) => None
            }
        }
      )
      .map(_.flatten)

    for {
      invitations <- invitationsRepository.findAllBy(
        arn = None,
        services,
        clientIds,
        status = None
      )
      suspendedArns <- getSuspendedArns(invitations.map(_.arn).distinct)
    } yield invitations.filterNot(invitation => suspendedArns.contains(invitation.arn))
  }

  def findAllForAgent(
    arn: String,
    services: Set[String],
    clientIds: Seq[String],
    isSuppliedClientId: Boolean = false
  ): Future[Seq[Invitation]] = invitationsRepository.findAllForAgent(
    arn,
    services.toSeq,
    clientIds,
    isSuppliedClientId
  )

  def updateInvitation(
    service: String,
    clientId: String,
    clientIdType: String,
    newService: String,
    newClientId: String,
    newClientIdType: String
  ): Future[Boolean] = invitationsRepository.updateInvitation(
    service,
    clientId,
    clientIdType,
    newService,
    newClientId,
    newClientIdType
  )

  private def getClientId(
    suppliedClientId: ClientId,
    service: Service
  )(implicit request: RequestHeader): Future[Either[InvitationFailureResponse, ClientId]] =
    (service, suppliedClientId.typeId) match {
      case (MtdIt | MtdItSupp, NinoType.id) =>
        hipConnector
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
  )(implicit request: RequestHeader): Future[Either[InvitationFailureResponse, Invitation]] = {
    val expiryDate = currentTime().plusSeconds(invitationExpiryDuration.toSeconds).toLocalDate
    (
      for {
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
      }
    ).recover {
      case e: MongoException if e.getMessage.contains("E11000 duplicate key error") => Left(DuplicateInvitationError)
    }
  }

  private def currentTime() = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime

  private val invitationExpiryDuration = appConfig.invitationExpiringDuration

}
