/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.model.stride

import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.agentmtdidentifiers.model.Arn

import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}

case class ClientRelationship(
  arn: Arn,
  dateTo: Option[LocalDate],
  dateFrom: Option[LocalDate],
  authProfile: Option[String]
) {
  def isActive: Boolean = dateTo match {
    case None    => true
    case Some(d) => d.isAfter(Instant.now().atZone(ZoneOffset.UTC).toLocalDate)
  }

}

object ClientRelationship {

  implicit val clientRelationshipWrites: OWrites[ClientRelationship] =
    Json.writes[ClientRelationship]

  implicit val reads: Reads[ClientRelationship] = ((JsPath \ "agentReferenceNumber").read[Arn] and
    (JsPath \ "dateTo").readNullable[LocalDate] and
    (JsPath \ "dateFrom").readNullable[LocalDate] and
    (JsPath \ "authProfile").readNullable[String])(ClientRelationship.apply _)

  val hipReads: Reads[ClientRelationship] = ((__ \ "arn").read[Arn] and
    (__ \ "dateTo").readNullable[LocalDate] and
    (__ \ "dateFrom").readNullable[LocalDate] and
    (__ \ "authProfile").readNullable[String])(ClientRelationship.apply _)

  val irvReads: Reads[ClientRelationship] = ((__ \ "arn").read[Arn] and
    (__ \ "endDate").readNullable[LocalDateTime].map(optDate => optDate.map(_.toLocalDate)) and
    (__ \ "startDate").readNullable[LocalDateTime].map(optDate => optDate.map(_.toLocalDate)) and
    (__ \ "authProfile").readNullable[String])(ClientRelationship.apply _)
}

case class ClientRelationshipResponse(relationship: Seq[ClientRelationship])

object ClientRelationshipResponse {
  implicit val clientRelationshipResponseFormat: OFormat[ClientRelationshipResponse] =
    Json.format[ClientRelationshipResponse]
}
