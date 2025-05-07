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
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.{InvalidClientId, InvitationNotFound, UnsupportedService}
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.{ClientIdentifier, Service}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

@Singleton
class CleanUpInvitationStatusService @Inject() (invitationsRepository: InvitationsRepository)(implicit
  ec: ExecutionContext
) extends Logging {

  def validateService(serviceStr: String): Either[InvitationFailureResponse, Service] =
    for {
      service <- Try(Service.forId(serviceStr)).fold(_ => Left(UnsupportedService), Right(_))
    } yield service

  def validateClientId(service: Service, clientIdStr: String): Either[InvitationFailureResponse, ClientId] =
    for {
      clientId <- Try(ClientIdentifier(clientIdStr, service.supportedClientIdType.id))
                    .fold(_ => Left(InvalidClientId), Right(_))
    } yield clientId

  def deauthoriseInvitation(
    arn: String,
    clientId: String,
    service: String,
    relationshipEndedBy: String
  ): Future[Either[InvitationFailureResponse, Unit]] = invitationsRepository
    .deauthAcceptedInvitation(
      arn = arn,
      clientId = clientId,
      service = service,
      relationshipEndedBy = relationshipEndedBy
    )
    .map {
      case true  => Right(())
      case false => Left(InvitationNotFound)
    }
}
