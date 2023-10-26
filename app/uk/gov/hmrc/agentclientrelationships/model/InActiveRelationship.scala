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

import play.api.libs.json._
import uk.gov.hmrc.agentmtdidentifiers.model._

import java.time.LocalDate

case class InactiveRelationship(
  arn: Arn,
  dateTo: Option[LocalDate],
  dateFrom: Option[LocalDate],
  clientId: String,
  clientType: String,
  service: String)

object InactiveRelationship {

  implicit val inActiveRelationshipWrites: OWrites[InactiveRelationship] = Json.writes[InactiveRelationship]

  implicit val reads: Reads[InactiveRelationship] = new Reads[InactiveRelationship] {
    override def reads(json: JsValue): JsResult[InactiveRelationship] = {
      val arn = (json \ "agentReferenceNumber").as[Arn]
      val dateTo = (json \ "dateTo").asOpt[LocalDate]
      val dateFrom = (json \ "dateFrom").asOpt[LocalDate]
      val clientId = (json \ "referenceNumber").as[String]
      val clientType = {
        if ((json \ "individual").asOpt[JsValue].isDefined) "personal" else "business"
      }
      val service = clientId match {
        case _ if clientId.matches(CgtRef.cgtRegex)                => Service.CapitalGains.id
        case _ if PptRef.isValid(clientId)                         => Service.Ppt.id
        case _ if CbcId.isValid(clientId)                          => Service.Cbc.id
        case _ if Vrn.isValid(clientId)                            => Service.Vat.id
        case _ if Utr.isValid(clientId)                            => Service.Trust.id
        case _ if clientType == "business" & Urn.isValid(clientId) => Service.TrustNT.id
        case _ if PlrId.isValid(clientId)                          => Service.Pillar2.id
        case _ if MtdItId.isValid(clientId)                        => Service.MtdIt.id
      }
      JsSuccess(InactiveRelationship(arn, dateTo, dateFrom, clientId, clientType, service))
    }
  }

}

case class InactiveRelationshipResponse(relationship: Seq[InactiveRelationship])

object InactiveRelationshipResponse {
  implicit val inActiveRelationshipResponse: OFormat[InactiveRelationshipResponse] =
    Json.format[InactiveRelationshipResponse]
}
