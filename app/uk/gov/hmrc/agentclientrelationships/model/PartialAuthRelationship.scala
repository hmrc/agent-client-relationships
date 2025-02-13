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

import play.api.libs.functional.syntax.{toFunctionalBuilderOps, unlift}
import play.api.libs.json.{Format, Json, __}
import uk.gov.hmrc.crypto.json.JsonEncryption.stringEncrypterDecrypter
import uk.gov.hmrc.crypto.{Decrypter, Encrypter}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import java.time.{Instant, LocalDate}

case class PartialAuthRelationship(
  created: Instant,
  arn: String,
  service: String,
  nino: String,
  active: Boolean,
  lastUpdated: Instant
) {
  def asInvitation: Invitation = Invitation(
    invitationId = "",
    arn = this.arn,
    service = this.service,
    clientId = this.nino,
    clientIdType = "ni",
    suppliedClientId = this.nino,
    suppliedClientIdType = "ni",
    clientName = "",
    status = if (this.active) PartialAuth else DeAuthorised,
    relationshipEndedBy = if (this.active) None else Some(""),
    clientType = None,
    expiryDate = LocalDate.now(),
    created = this.created,
    lastUpdated = this.lastUpdated
  )
}

object PartialAuthRelationship {
  implicit val format: Format[PartialAuthRelationship] = Json.format[PartialAuthRelationship]

  def mongoFormat(implicit crypto: Encrypter with Decrypter): Format[PartialAuthRelationship] = {
    implicit val mongoInstantFormat: Format[Instant] = MongoJavatimeFormats.instantFormat
    (
      (__ \ "created").format[Instant] and
        (__ \ "arn").format[String] and
        (__ \ "service").format[String] and
        (__ \ "nino").format[String](stringEncrypterDecrypter) and
        (__ \ "active").format[Boolean] and
        (__ \ "lastUpdated").format[Instant]
    )(PartialAuthRelationship.apply, unlift(PartialAuthRelationship.unapply))
  }
}
