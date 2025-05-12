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
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse._
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP, HMRCMTDVAT}
import uk.gov.hmrc.agentmtdidentifiers.model.{ClientIdentifier, Service}

import scala.util.Try

case class ApiCreateInvitationRequest(
  service: String,
  suppliedClientId: String,
  knownFact: String,
  clientType: Option[String]
) {

  def getService(apiSupportedServices: Seq[Service]): Either[InvitationFailureResponse, Service] =
    Try(Service.forId(service))
      .fold(_ => Left(UnsupportedService), Right(_))
      .flatMap { service =>
        if (apiSupportedServices.contains(service)) Right(service)
        else Left(UnsupportedService)
      }

  def getSuppliedClientId(apiSupportedServices: Seq[Service]): Either[InvitationFailureResponse, ClientId] = for {
    service <- getService(apiSupportedServices)
    _ <- Either.cond[InvitationFailureResponse, String](
           service.supportedSuppliedClientIdType.isValid(suppliedClientId),
           suppliedClientId,
           InvalidClientId
         )

    clientId <-
      Try(ClientIdentifier(suppliedClientId, service.supportedSuppliedClientIdType.id))
        .fold(_ => Left(UnsupportedClientIdType), Right(_))
  } yield clientId

  private val validClientTypes = Seq("personal", "business", "trust")

  def getClientType: Either[InvitationFailureResponse, Option[String]] = clientType match {
    case Some(cliType) if validClientTypes.contains(cliType) => Right(clientType)
    case Some(_)                                             => Left(UnsupportedClientType)
    case None                                                => Right(None)
  }

}

object ApiCreateInvitationRequest {
  implicit val format: OFormat[ApiCreateInvitationRequest] = Json.format[ApiCreateInvitationRequest]

}
