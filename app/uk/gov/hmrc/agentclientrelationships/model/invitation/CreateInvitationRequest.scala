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
import InvitationFailureResponse._
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.{ClientIdentifier, Service}

import scala.util.Try

case class CreateInvitationRequest(
  clientId: String,
  suppliedClientIdType: String,
  clientName: String,
  service: String,
  clientType: Option[String]
) {
  def getService: Either[InvitationFailureResponse, Service] = Try(Service.forId(service)).fold(
    _ => Left(UnsupportedService),
    Right(_)
  )

  def getSuppliedClientId: Either[InvitationFailureResponse, ClientId] =
    for {
      service <- getService
      _ <- Either.cond[InvitationFailureResponse, String](
             service.supportedSuppliedClientIdType.isValid(clientId),
             clientId,
             InvalidClientId
           )
      _ <- Either.cond[InvitationFailureResponse, String](
             service.supportedSuppliedClientIdType.id == suppliedClientIdType,
             suppliedClientIdType,
             UnsupportedClientIdType
           )
      clientId <- Try(ClientIdentifier(clientId, suppliedClientIdType)).fold(_ => Left(InvalidClientId), Right(_))
    } yield clientId

  private val validClientTypes = Seq("personal", "business", "trust")

  def getClientType: Either[InvitationFailureResponse, Option[String]] =
    clientType match {
      case Some(cliType) if validClientTypes.contains(cliType) => Right(clientType)
      case Some(_)                                             => Left(UnsupportedClientType)
      case None                                                => Right(None)
    }
}

object CreateInvitationRequest {
  implicit val format: OFormat[CreateInvitationRequest] = Json.format[CreateInvitationRequest]

}
