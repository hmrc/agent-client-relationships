/*
 * Copyright 2020 HM Revenue & Customs
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
import org.joda.time.LocalDate
import play.api.libs.json._
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import play.api.libs.functional.syntax._

case class InactiveRelationship(
  arn: Arn,
  dateTo: Option[LocalDate],
  dateFrom: Option[LocalDate],
  referenceNumber: String)

object InactiveRelationship {
  implicit val inActiveRelationshipWrites: OWrites[InactiveRelationship] = Json.writes[InactiveRelationship]

  implicit val reads: Reads[InactiveRelationship] = ((JsPath \ "agentReferenceNumber").read[Arn] and
    (JsPath \ "dateTo").readNullable[LocalDate] and
    (JsPath \ "dateFrom").readNullable[LocalDate] and
    (JsPath \ "referenceNumber").read[String])(InactiveRelationship.apply _)
}

case class InactiveRelationshipResponse(relationship: Seq[InactiveRelationship])

object InactiveRelationshipResponse {
  implicit val inActiveRelationshipResponse: OFormat[InactiveRelationshipResponse] =
    Json.format[InactiveRelationshipResponse]
}
