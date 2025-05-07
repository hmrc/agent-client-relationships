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

package uk.gov.hmrc.agentclientrelationships.services.transitional

import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.{InvalidClientId, InvitationNotFound, UnsupportedService, UpdateStatusFailed}
import uk.gov.hmrc.agentclientrelationships.model.transitional.ChangeInvitationStatusRequest
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, ClientIdentifier, NinoType, Service}
import uk.gov.hmrc.domain.Nino

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

@Singleton
class ChangeInvitationStatusService @Inject() (
  partialAuthRepository: PartialAuthRepository,
  invitationsRepository: InvitationsRepository
)(implicit ec: ExecutionContext)
extends Logging {

  val validStatusChangesFrom: Map[InvitationStatus, Set[InvitationStatus]] = Map(
    DeAuthorised -> Set(Accepted, PartialAuth)
  )

  def validateService(serviceStr: String): Either[InvitationFailureResponse, Service] =
    for {
      service <- Try(Service.forId(serviceStr)).fold(_ => Left(UnsupportedService), Right(_))
    } yield service

  def validateClientId(service: Service, clientIdStr: String): Either[InvitationFailureResponse, ClientId] =
    for {
      suppliedClientId <- Try(ClientIdentifier(clientIdStr, service.supportedSuppliedClientIdType.id))
                            .fold(_ => Left(InvalidClientId), Right(_))
    } yield suppliedClientId

  private def findPartialAuthInvitation(
    arn: Arn,
    clientId: ClientId,
    service: Service
  ): Future[Option[PartialAuthRelationship]] =
    clientId.typeId match {
      case NinoType.id => partialAuthRepository.findActive(service.id, Nino(clientId.value), arn)
      case _           => Future.successful(None)
    }

  private def deauthPartialAuth(
    arn: Arn,
    clientId: ClientId,
    service: Service
  ): Future[Either[InvitationFailureResponse, Unit]] = partialAuthRepository
    .deauthorise(service.id, Nino(clientId.value), arn, Instant.now)
    .map {
      case true  => Right(())
      case false => Left(UpdateStatusFailed("Update status for PartialAuth invitation failed."))
    }

  private def updateInvitationStore(
    invitationId: String,
    fromStatus: InvitationStatus,
    toStatus: InvitationStatus,
    endedBy: Option[String],
    lastUpdated: Option[Instant]
  ): Future[Either[InvitationFailureResponse, Unit]] = invitationsRepository
    .updateStatusFromTo(
      invitationId = invitationId,
      fromStatus = fromStatus,
      toStatus = toStatus,
      relationshipEndedBy = endedBy,
      lastUpdated = lastUpdated
    )
    .map(
      _.fold[Either[InvitationFailureResponse, Unit]](
        Left(UpdateStatusFailed("Update status for invitation failed."))
      )(_ => Right(()))
    )

  private def findAllMatchingInvitations(
    arn: Arn,
    service: Service,
    suppliedClientId: ClientId
  ): Future[Seq[Invitation]] = invitationsRepository.findAllForAgent(
    arn = arn.value,
    services = Seq(service.id),
    clientIds = Seq(suppliedClientId.value),
    isSuppliedClientId = true
  )

  def changeStatusInStore(
    arn: Arn,
    service: Service,
    suppliedClientId: ClientId,
    changeRequest: ChangeInvitationStatusRequest
  ): Future[Either[InvitationFailureResponse, Unit]] =
    for {
      invitationStoreResults <- findAllMatchingInvitations(arn, service, suppliedClientId)
                                  .map(
                                    _.find(x =>
                                      validStatusChangesFrom(changeRequest.invitationStatus).contains(x.status)
                                    )
                                  )
                                  .flatMap {
                                    case Some(invitation) =>
                                      updateInvitationStore(
                                        invitationId = invitation.invitationId,
                                        fromStatus = invitation.status,
                                        toStatus = changeRequest.invitationStatus,
                                        endedBy =
                                          if (changeRequest.invitationStatus == DeAuthorised)
                                            changeRequest.endedBy.orElse(Some("HMRC"))
                                          else
                                            None,
                                        lastUpdated = None
                                      )
                                    case None => Future.successful(Left(InvitationNotFound))
                                  }

      partialStoreResults <-
        changeRequest.invitationStatus match {
          case DeAuthorised =>
            findPartialAuthInvitation(arn, suppliedClientId, service).flatMap {
              case Some(_) => deauthPartialAuth(arn, suppliedClientId, service)
              case None    => Future.successful(Left(InvitationNotFound))
            }
          case _ => Future.successful(Left(InvitationNotFound))
        }

    } yield invitationStoreResults match {
      case Left(value: UpdateStatusFailed)    => Left(value)
      case Left(_: InvitationFailureResponse) => partialStoreResults
      case Right(value)                       => Right(value)
    }

}
