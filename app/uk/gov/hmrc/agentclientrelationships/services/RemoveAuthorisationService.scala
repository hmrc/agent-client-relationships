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
import uk.gov.hmrc.auth.core.AffinityGroup
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HeaderCarrier

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

@Singleton
class RemoveAuthorisationService @Inject() (
  deleteService: DeleteRelationshipsServiceWithAcr,
  partialAuthRepository: PartialAuthRepository,
  invitationsRepository: InvitationsRepository,
  ifConnector: IFConnector
)(implicit ec: ExecutionContext)
    extends Logging {

  def validateRequest(serviceStr: String, clientIdStr: String): Either[InvitationFailureResponse, ValidRequest] = for {
    service <- Try(Service.forId(serviceStr))
                 .fold(_ => Left(UnsupportedService), Right(_))
    clientId <- Try(ClientIdentifier(clientIdStr, service.supportedSuppliedClientIdType.id))
                  // Client requests can come with an MTDITID instead of nino so we need to check that too
                  .orElse(Try(ClientIdentifier(clientIdStr, service.supportedClientIdType.id)))
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
          .findActive(service.id, Nino(clientId.value), arn)
      case _ => Future.successful(None)
    }

  def deauthPartialAuth(arn: Arn, clientId: ClientId, service: Service): Future[Boolean] =
    clientId.typeId match {
      case NinoType.id =>
        partialAuthRepository
          .deauthorise(service.id, Nino(clientId.value), arn, Instant.now)
      case _ => Future.successful(false)
    }

  def deauthAltItsaInvitation(
    arn: Arn,
    clientId: ClientId,
    service: Service,
    affinityGroup: Option[AffinityGroup]
  ): Future[Boolean] =
    clientId.typeId match {
      case NinoType.id =>
        invitationsRepository
          .updatePartialAuthToDeAuthorisedStatus(
            arn,
            service.id,
            Nino(clientId.value),
            deleteService.determineUserTypeFromAG(affinityGroup).getOrElse("HMRC")
          )
          .map(_.fold(false)(_ => true))
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
          .map {
            case Some(mtdItId) => Right(EnrolmentKey(service, mtdItId))
            case None          => Right(suppliedEnrolmentKey)
          }
          .recover { case NonFatal(_) =>
            Left(ClientRegistrationNotFound)
          }
      case _ => Future successful Right(suppliedEnrolmentKey)
    }

}
