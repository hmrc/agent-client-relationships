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

package uk.gov.hmrc.agentclientrelationships.model.invitation

import play.api.libs.json.{Json, OFormat}
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.InvitationFailureResponse
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.InvitationFailureResponse._
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.{ClientIdentifier, Service}

import scala.util.Try

case class CreateInvitationInputData(
  inputSuppliedClientId: String,
  inputSuppliedClientIdType: String,
  clientName: String,
  inputService: String
) {
  def getService: Either[InvitationFailureResponse, Service] = Try(Service.forId(inputService))
    .fold(_ => Left(UnsupportedService), Right(_))

  def getSuppliedClientId: Either[InvitationFailureResponse, ClientId] = for {
    service <- getService
    _ <- Either.cond[InvitationFailureResponse, String](
           service.supportedSuppliedClientIdType.isValid(inputSuppliedClientId),
           inputSuppliedClientId,
           InvalidClientId
         )
    _ <- Either.cond[InvitationFailureResponse, String](
           service.supportedSuppliedClientIdType.id == inputSuppliedClientIdType,
           inputSuppliedClientIdType,
           UnsupportedClientIdType
         )
    clientId <-
      Try(ClientIdentifier(inputSuppliedClientId, inputSuppliedClientIdType)).fold(_ => Left(InvalidClientId), Right(_))
  } yield clientId

}

object CreateInvitationInputData {
  implicit val format: OFormat[CreateInvitationInputData] = Json.format[CreateInvitationInputData]

}
