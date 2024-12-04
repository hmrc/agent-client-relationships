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
import org.apache.pekko.Done
import org.mongodb.scala.MongoException
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.IFConnector
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.{ClientRegistrationNotFound, DuplicateInvitationError, InvalidClientId, InvitationNotFound, UnsupportedService}
import uk.gov.hmrc.agentclientrelationships.model.invitation.{CreateInvitationRequest, InvitationFailureResponse, ValidRequest}
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsEventStoreRepository, InvitationsRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{MtdIt, MtdItSupp}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, ClientIdentifier, NinoType, Service}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Instant, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.annotation.unused
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

@Singleton
class InvitationService @Inject() (
  invitationsRepository: InvitationsRepository,
  invitationsEventStoreRepository: InvitationsEventStoreRepository,
  analyticsService: PlatformAnalyticsService,
  ifConnector: IFConnector,
  appConfig: AppConfig
)(implicit ec: ExecutionContext)
    extends Logging {

  def createInvitation(
    arn: Arn,
    createInvitationInputData: CreateInvitationRequest,
    originHeader: Option[String]
  )(implicit hc: HeaderCarrier): Future[Either[InvitationFailureResponse, Invitation]] = {
    val invitationT = for {

      suppliedClientId <- EitherT.fromEither[Future](createInvitationInputData.getSuppliedClientId)
      service          <- EitherT.fromEither[Future](createInvitationInputData.getService)
      invitation <- EitherT(
                      makeInvitation(arn, suppliedClientId, service, createInvitationInputData.clientName, originHeader)
                    )
    } yield invitation

    invitationT.value
  }

  def validateRequest(serviceStr: String, clientIdStr: String): Either[InvitationFailureResponse, ValidRequest] = for {
    service <- Try(Service.forId(serviceStr))
                 .fold(_ => Left(UnsupportedService), Right(_))
    clientId <- Try(ClientIdentifier(clientIdStr, service.supportedSuppliedClientIdType.id))
                  .fold(_ => Left(InvalidClientId), Right(_))
  } yield ValidRequest(clientId, service)

  def findLatestActiveInvitations(
    arn: Arn,
    suppliedClientId: ClientId,
    service: Service
  ): Future[Either[InvitationFailureResponse, Invitation]] =
    invitationsRepository
      .findByArnClientIdService(arn, suppliedClientId, service)
      .map(
        _.filter(i => i.status == Accepted || i.status == PartialAuth)
          .sortBy(_.created)
          .headOption
          .fold[Either[InvitationFailureResponse, Invitation]](Left(InvitationNotFound))(Right(_))
      )

  // Find Latest Invitation and check status is PartialAuth
  def findLatestPartialAuthInvitationEvent(
    arn: Arn,
    clientId: ClientId,
    service: Service
  ): Future[Option[InvitationEvent]] =
    invitationsEventStoreRepository
      .findAllForClient(service, clientId)
      .map(x =>
        x.filter(_.arn == arn.value)
          .sortBy(_.created)
          .headOption
          .filter(_.status == PartialAuth)
      )

  def deAuthPartialAuthEventStore(invitationEvent: InvitationEvent, endedBy: String): Future[InvitationEvent] =
    invitationsEventStoreRepository.create(
      status = DeAuthorised,
      created = Instant.now(),
      arn = invitationEvent.arn,
      service = invitationEvent.service,
      clientId = invitationEvent.clientId,
      deauthorisedBy = Some(endedBy)
    )

  private def makeInvitation(
    arn: Arn,
    suppliedClientId: ClientId,
    service: Service,
    clientName: String,
    originHeader: Option[String]
  )(implicit hc: HeaderCarrier): Future[Either[InvitationFailureResponse, Invitation]] = {
    val invitationT = for {
      clientId   <- EitherT(getClientId(suppliedClientId, service))
      invitation <- EitherT(create(arn, service, clientId, suppliedClientId, clientName, originHeader))
    } yield invitation

    invitationT.value
  }

  @unused
  private def changeInvitationStatus(invitation: Invitation, status: InvitationStatus)(implicit
    ec: ExecutionContext
  ): Future[Either[InvitationFailureResponse, Invitation]] =
    if (invitation.status == Pending || invitation.status == PartialAuth) {
      (for {
        updatedInvitation <-
          EitherT
            .fromOptionF(invitationsRepository.updateStatus(invitation.invitationId, status), InvitationNotFound)
      } yield updatedInvitation).value
    } else Future.successful(Left(InvitationNotFound))

  private def getClientId(suppliedClientId: ClientId, service: Service)(implicit
    hc: HeaderCarrier
  ): Future[Either[InvitationFailureResponse, ClientId]] = (service, suppliedClientId.typeId) match {
    case (MtdIt | MtdItSupp, NinoType.id) =>
      ifConnector
        .getMtdIdFor(Nino(suppliedClientId.value))
        .map(_.map(ClientIdentifier(_)))
        .map(_.toRight(ClientRegistrationNotFound))
    case _ => Future successful Right(suppliedClientId)
  }

  def replaceEnrolmentKeyForItsa(
    suppliedClientId: ClientId,
    suppliedEnrolmentKey: EnrolmentKey,
    service: Service
  )(implicit
    hc: HeaderCarrier
  ): Future[Either[InvitationFailureResponse, EnrolmentKey]] =
    (service, suppliedClientId.typeId) match {
      case (MtdIt | MtdItSupp, NinoType.id) =>
        ifConnector
          .getMtdIdFor(Nino(suppliedClientId.value))
          .map(_.map(EnrolmentKey(Service.MtdIt, _)))
          .map(_.toRight(ClientRegistrationNotFound))
          .recover { case NonFatal(_) =>
            Left[InvitationFailureResponse, EnrolmentKey](ClientRegistrationNotFound)
          }
      case _ => Future successful Right(suppliedEnrolmentKey)
    }

  def setRelationshipEnded(invitation: Invitation, endedBy: String)(implicit ec: ExecutionContext): Future[Invitation] =
    for {
      updatedInvitation <- invitationsRepository.setRelationshipEnded(invitation, endedBy)
    } yield {
      logger info s"""Invitation with id: "${invitation.invitationId}" has been flagged as isRelationshipEnded = true"""
      updatedInvitation
    }

  private def create(
    arn: Arn,
    service: Service,
    clientId: ClientId,
    suppliedClientId: ClientId,
    clientName: String,
    originHeader: Option[String]
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Either[InvitationFailureResponse, Invitation]] = {
    val expiryDate = currentTime().plusSeconds(invitationExpiryDuration.toSeconds).toLocalDate
    (for {
      invitation <- invitationsRepository.create(arn.value, service, clientId, suppliedClientId, clientName, expiryDate)
      // TODO WG - remove that code
      _ <- analyticsService.reportSingleEventAnalyticsRequest(invitation, originHeader).fallbackTo(successful(Done))
    } yield {
      logger.info(s"""Created invitation with id: "${invitation.invitationId}".""")
      Right(invitation)
    }).recover {
      case e: MongoException if e.getMessage.contains("E11000 duplicate key error") =>
        Left(DuplicateInvitationError)
    }
  }

  private def currentTime() = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime
  @unused
  private def InstantUtc() = Instant.now().atZone(ZoneOffset.UTC)

  private val invitationExpiryDuration = appConfig.invitationExpiringDuration

}
