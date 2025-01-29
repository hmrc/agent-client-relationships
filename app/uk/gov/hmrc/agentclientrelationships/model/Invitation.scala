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

import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, Json, Reads, __}
import uk.gov.hmrc.agentclientrelationships.model.transitional.{DetailsForEmail, StatusChangeEvent}
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP}
import uk.gov.hmrc.agentmtdidentifiers.model.{InvitationId, Service}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import java.time.{Instant, LocalDate, ZoneOffset}

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
  clientType: Option[String],
  expiryDate: LocalDate,
  created: Instant,
  lastUpdated: Instant
) {

  def isAltItsa: Boolean =
    (Seq(HMRCMTDIT, HMRCMTDITSUPP).contains(this.service) &&
      this.clientId == this.suppliedClientId) ||
      this.status == PartialAuth
}

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
    expiryDate: LocalDate,
    clientType: Option[String]
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
      clientType,
      expiryDate,
      Instant.now(),
      Instant.now()
    )

  val acaReads: Reads[Invitation] =
    (
      (__ \ "invitationId").read[String] and
        (__ \ "arn").read[String] and
        (__ \ "clientType").readNullable[String] and
        (__ \ "service").read[String] and
        (__ \ "clientId").read[String] and
        (__ \ "clientIdType").read[String] and
        (__ \ "suppliedClientId").read[String] and
        (__ \ "suppliedClientIdType").read[String] and
        (__ \ "expiryDate").read[LocalDate] and
        (__ \ "relationshipEndedBy").readNullable[String] and
        (__ \ "detailsForEmail").readNullable[DetailsForEmail] and
        (__ \ "events").read[List[StatusChangeEvent]]
    ) {
      (
        invitationId,
        arn,
        clientType,
        service,
        clientId,
        clientIdType,
        suppliedClientId,
        suppliedClientIdType,
        expiryDate,
        relationshipEndedBy,
        detailsForEmail,
        events
      ) =>
        Invitation(
          invitationId = invitationId,
          arn = arn,
          clientType = clientType,
          service = service,
          clientId = clientId,
          suppliedClientId = suppliedClientId,
          expiryDate = expiryDate,
          clientName = detailsForEmail.getOrElse(DetailsForEmail("", "", "")).clientName,
          relationshipEndedBy = relationshipEndedBy,
          created = events.head.time.toInstant(ZoneOffset.UTC),
          lastUpdated = events.last.time.toInstant(ZoneOffset.UTC),
          status = events.last.status,
          clientIdType = clientIdType,
          suppliedClientIdType = suppliedClientIdType
        )
    }
}
