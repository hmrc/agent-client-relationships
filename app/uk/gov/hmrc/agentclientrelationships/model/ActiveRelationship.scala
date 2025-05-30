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

package uk.gov.hmrc.agentclientrelationships.model

import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.agentmtdidentifiers.model.Arn

import java.time.LocalDate
import java.time.LocalDateTime

case class ActiveRelationship(
  arn: Arn,
  dateTo: Option[LocalDate],
  dateFrom: Option[LocalDate]
)

object ActiveRelationship {

  implicit val activeRelationshipWrites: OWrites[ActiveRelationship] = Json.writes[ActiveRelationship]

  implicit val reads: Reads[ActiveRelationship] =
    (
      (JsPath \ "agentReferenceNumber").read[Arn] and
        (JsPath \ "dateTo").readNullable[LocalDate] and
        (JsPath \ "dateFrom").readNullable[LocalDate]
    )(ActiveRelationship.apply _)

  val hipReads: Reads[ActiveRelationship] = ((__ \ "arn").read[Arn] and (__ \ "dateTo").readNullable[LocalDate] and (__ \ "dateFrom").readNullable[LocalDate])(
    ActiveRelationship.apply _
  )

  val irvReads: Reads[ActiveRelationship] =
    (
      (__ \ "arn").read[Arn] and
        (__ \ "endDate").readNullable[LocalDateTime].map(optDate => optDate.map(_.toLocalDate)) and
        (__ \ "startDate").readNullable[LocalDateTime].map(optDate => optDate.map(_.toLocalDate))
    )(ActiveRelationship.apply _)

}

case class ActiveRelationshipResponse(relationship: Seq[ActiveRelationship])

object ActiveRelationshipResponse {
  implicit val activeRelationshipResponse: OFormat[ActiveRelationshipResponse] = Json.format[ActiveRelationshipResponse]
}
