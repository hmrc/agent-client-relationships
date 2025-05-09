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

import cats.data.EitherT
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.InvitationNotFound
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.UnsupportedStatusChange
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.UpdateStatusFailed
import uk.gov.hmrc.agentclientrelationships.model.transitional.InvitationStatusAction
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.Nino

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

@Singleton
class ChangeInvitationStatusByIdService @Inject() (
  invitationsRepository: InvitationsRepository,
  partialAuthRepository: PartialAuthRepository
)(implicit ec: ExecutionContext)
extends Logging {

  val validInvitationStatusActionsFrom: Map[InvitationStatusAction, Set[InvitationStatus]] = Map(
    InvitationStatusAction.Accept -> Set(Pending),
    InvitationStatusAction.Reject -> Set(Pending),
    InvitationStatusAction.Cancel -> Set(Pending)
  )

  def validateAction(action: String): Either[InvitationFailureResponse, InvitationStatusAction] =
    for {
      invitationStatusAction <- Try(InvitationStatusAction(action)).fold(_ => Left(UnsupportedStatusChange), Right(_))
    } yield invitationStatusAction

  def changeStatusById(
    invitationId: String,
    invitationStatusAction: InvitationStatusAction
  ): Future[Either[InvitationFailureResponse, Unit]] =
    for {

      invitationStoreResults <- findMatchingInvitationById(invitationId)
        .map(_.find(x => validInvitationStatusActionsFrom(invitationStatusAction).contains(x.status)))
        .flatMap {
          case Some(invitation) =>
            updateStatus(invitation, invitationStatusAction)

          case None =>
            Future.successful(Left(InvitationNotFound))
        }
    } yield invitationStoreResults

  private def updateStatus(
    invitation: Invitation,
    invitationStatusAction: InvitationStatusAction
  ): Future[Either[InvitationFailureResponse, Unit]] =
    (
      for {
        _ <- EitherT(
          updateInvitationStore(
            invitationId = invitation.invitationId,
            fromStatus = invitation.status,
            toStatus =
              invitationStatusAction match {
                case InvitationStatusAction.Accept if invitation.isAltItsa =>
                  PartialAuth
                case InvitationStatusAction.Accept =>
                  Accepted
                case InvitationStatusAction.Cancel =>
                  Cancelled
                case InvitationStatusAction.Reject =>
                  Rejected
              },
            endedBy = None,
            lastUpdated = None
          )
        )
        _ <-
          if (invitation.isAltItsa)
            EitherT(
              createPartialAuthRecord(
                created = invitation.created,
                arn = Arn(invitation.arn),
                service = invitation.service,
                nino = Nino(invitation.suppliedClientId)
              )
            )
          else
            EitherT.rightT[Future, InvitationFailureResponse](())
      } yield ()
    ).value

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
      _.fold[Either[InvitationFailureResponse, Unit]](Left(UpdateStatusFailed("Update status for invitation failed.")))(
        _ => Right(())
      )
    )

  private def createPartialAuthRecord(
    created: Instant,
    arn: Arn,
    service: String,
    nino: Nino
  ): Future[Either[InvitationFailureResponse, Unit]] = partialAuthRepository
    .create(
      created = created,
      arn = arn,
      service = service,
      nino = nino
    )
    .map(Right(_))

  private def findMatchingInvitationById(invitationId: String): Future[Option[Invitation]] = invitationsRepository
    .findOneById(invitationId = invitationId)

}
