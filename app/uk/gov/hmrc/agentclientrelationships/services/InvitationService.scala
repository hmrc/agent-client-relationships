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
import uk.gov.hmrc.agentclientrelationships.connectors.IFConnector
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.{DuplicateInvitationError, NoPendingInvitation}
import uk.gov.hmrc.agentclientrelationships.model.invitation.{CreateInvitationRequest, InvitationFailureResponse}
import uk.gov.hmrc.agentclientrelationships.model.{Invitation, Pending, Rejected}
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{MtdIt, MtdItSupp}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, ClientIdentifier, NinoType, Service}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Instant, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class InvitationService @Inject() (
  invitationsRepository: InvitationsRepository,
  ifConnector: IFConnector,
  emailService: EmailService,
  appConfig: AppConfig
)(implicit ec: ExecutionContext)
    extends Logging {

  def createInvitation(
    arn: Arn,
    createInvitationInputData: CreateInvitationRequest
  )(implicit hc: HeaderCarrier): Future[Either[InvitationFailureResponse, Invitation]] = {
    val invitationT = for {

      suppliedClientId <- EitherT.fromEither[Future](createInvitationInputData.getSuppliedClientId)
      service          <- EitherT.fromEither[Future](createInvitationInputData.getService)
      clientType       <- EitherT.fromEither[Future](createInvitationInputData.getClientType)
      invitation <- EitherT(
                      makeInvitation(
                        arn,
                        suppliedClientId,
                        service,
                        createInvitationInputData.clientName,
                        clientType
                      )
                    )
    } yield invitation

    invitationT.value
  }

  def findInvitationForAgent(arn: String, invitationId: String): Future[Option[Invitation]] =
    invitationsRepository.findOneByIdForAgent(arn, invitationId)

  def findInvitation(invitationId: String): Future[Option[Invitation]] =
    invitationsRepository.findOneById(invitationId)

  def rejectInvitation(
    invitationId: String
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[InvitationFailureResponse, Unit]] =
    (for {
      invitation <- EitherT(
                      invitationsRepository
                        .updateStatusFromTo(invitationId, Pending, Rejected)
                        .map(_.fold[Either[InvitationFailureResponse, Invitation]](Left(NoPendingInvitation))(Right(_)))
                    )
      _ <-
        EitherT.right[InvitationFailureResponse](emailService.sendRejectedEmail(invitation).fallbackTo(successful(())))
      // TODO WG  auditEvent
    } yield invitation)
      .map(_ => ())
      .value

  def findInvitationForClient(invitationId: String): Future[Option[Invitation]] =
    invitationsRepository.findOneByIdForClient(invitationId)

  def findAllForAgent(
    arn: String,
    services: Set[String],
    clientIds: Seq[String],
    isSuppliedClientId: Boolean = false
  ): Future[Seq[Invitation]] =
    invitationsRepository.findAllForAgent(arn, services.toSeq, clientIds, isSuppliedClientId)

  def updateClientIdAndType(
    clientId: String,
    clientIdType: String,
    newClientId: String,
    newClientIdType: String
  ): Future[Boolean] =
    invitationsRepository.updateClientIdAndType(clientId, clientIdType, newClientId, newClientIdType)

  private def makeInvitation(
    arn: Arn,
    suppliedClientId: ClientId,
    service: Service,
    clientName: String,
    clientType: Option[String]
  )(implicit hc: HeaderCarrier): Future[Either[InvitationFailureResponse, Invitation]] = {
    val invitationT = for {
      clientId   <- EitherT(getClientId(suppliedClientId, service))
      invitation <- EitherT(create(arn, service, clientId, suppliedClientId, clientName, clientType))
    } yield invitation

    invitationT.value
  }

  private def getClientId(suppliedClientId: ClientId, service: Service)(implicit
    hc: HeaderCarrier
  ): Future[Either[InvitationFailureResponse, ClientId]] = (service, suppliedClientId.typeId) match {
    case (MtdIt | MtdItSupp, NinoType.id) =>
      ifConnector
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
    clientType: Option[String]
  )(implicit ec: ExecutionContext): Future[Either[InvitationFailureResponse, Invitation]] = {
    val expiryDate = currentTime().plusSeconds(invitationExpiryDuration.toSeconds).toLocalDate
    (for {
      invitation <-
        invitationsRepository.create(arn.value, service, clientId, suppliedClientId, clientName, expiryDate, clientType)
    } yield {
      logger.info(s"""Created invitation with id: "${invitation.invitationId}".""")
      Right(invitation)
    }).recover {
      case e: MongoException if e.getMessage.contains("E11000 duplicate key error") =>
        Left(DuplicateInvitationError)
    }
  }

  private def currentTime() = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime

  private val invitationExpiryDuration = appConfig.invitationExpiringDuration

}
