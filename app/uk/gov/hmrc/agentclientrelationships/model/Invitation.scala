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

package uk.gov.hmrc.agentclientrelationships.model

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.{InvitationId, Service}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import java.time.{Instant, LocalDate}

case class Invitation(
  invitationId: String,
  arn: String,
  service: String,
  clientId: String,
  clientIdType: String,
  suppliedClientId: String,
  suppliedClientIdType: String,
  clientName: String,
  status: InvitationStatus,
  relationshipEndedBy: Option[String] = None,
  expiryDate: LocalDate,
  created: Instant,
  lastUpdated: Instant
)

object Invitation {
  implicit val format: Format[Invitation] = Json.format[Invitation]

  val mongoFormat: Format[Invitation] = {
    implicit val mongoInstantFormat: Format[Instant] = MongoJavatimeFormats.instantFormat
    implicit val mongoLocalDateFormat: Format[LocalDate] = MongoJavatimeFormats.localDateFormat
    Json.format[Invitation]
  }

  def createNew(
    arn: String,
    service: Service,
    clientId: ClientId,
    suppliedClientId: ClientId,
    clientName: String,
    expiryDate: LocalDate
  ): Invitation =
    Invitation(
      InvitationId.create(arn, clientId.value, service.id)(service.invitationIdPrefix).value,
      arn,
      service.id,
      clientId.value,
      clientId.typeId,
      suppliedClientId.value,
      suppliedClientId.typeId,
      clientName,
      Pending,
      None,
      expiryDate,
      Instant.now(),
      Instant.now()
    )
}
