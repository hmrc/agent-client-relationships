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
import uk.gov.hmrc.agentclientrelationships.connectors.IFConnector
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.{ClientRegistrationNotFound, InvalidClientId, UnsupportedService}
import uk.gov.hmrc.agentclientrelationships.model.invitation.{InvitationFailureResponse, ValidRequest}
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{MtdIt, MtdItSupp}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, ClientIdentifier, NinoType, Service}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

@Singleton
class RemoveAuthorisationService @Inject() (
  invitationsRepository: InvitationsRepository,
  partialAuthRepository: PartialAuthRepository,
  ifConnector: IFConnector
)(implicit ec: ExecutionContext)
    extends Logging {

  def validateRequest(serviceStr: String, clientIdStr: String): Either[InvitationFailureResponse, ValidRequest] = for {
    service <- Try(Service.forId(serviceStr))
                 .fold(_ => Left(UnsupportedService), Right(_))
    clientId <- Try(ClientIdentifier(clientIdStr, service.supportedSuppliedClientIdType.id))
                  .fold(_ => Left(InvalidClientId), Right(_))
  } yield ValidRequest(clientId, service)

  def findPartialAuthInvitation(
    arn: Arn,
    clientId: ClientId,
    service: Service
  ): Future[Option[PartialAuthRelationship]] =
    clientId.typeId match {
      case NinoType.id =>
        partialAuthRepository
          .find(service.id, Nino(clientId.value), arn)
      case _ => Future.successful(None)
    }

  def deletePartialAuthInvitation(arn: Arn, clientId: ClientId, service: Service): Future[Boolean] =
    clientId.typeId match {
      case NinoType.id =>
        partialAuthRepository
          .deletePartialAuth(service.id, Nino(clientId.value), arn)
      case _ => Future.successful(false)
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

  def setRelationshipEnded(arn: String, suppliedClientId: String, service: String, endedBy: String)(implicit
    ec: ExecutionContext
  ): Future[Option[Invitation]] =
    for {
      updatedInvitation <- invitationsRepository.deauthorise(arn, suppliedClientId, service, endedBy)
    } yield updatedInvitation.map { i =>
      logger info s"""Invitation with id: "${i.invitationId}" status has been changed to Deauthorise"""
      i
    }

}
