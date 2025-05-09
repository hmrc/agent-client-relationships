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

package uk.gov.hmrc.agentclientrelationships.model.clientDetails.cgt

import play.api.libs.json.JsError
import play.api.libs.json.JsSuccess
import play.api.libs.json.Reads

case class CgtSubscriptionDetails(
  name: String,
  postcode: Option[String],
  countryCode: String
)

object CgtSubscriptionDetails {

  implicit val reads: Reads[CgtSubscriptionDetails] = { json =>
    val basePath = json \ "subscriptionDetails"
    val typeOfPerson = (basePath \ "typeOfPersonDetails" \ "typeOfPerson").as[String]
    val postcode = (basePath \ "addressDetails" \ "postalCode").asOpt[String]
    val countryCode = (basePath \ "addressDetails" \ "countryCode").as[String]

    typeOfPerson match {
      case "Individual" =>
        val firstName = (basePath \ "typeOfPersonDetails" \ "firstName").as[String]
        val lastName = (basePath \ "typeOfPersonDetails" \ "lastName").as[String]
        JsSuccess(
          CgtSubscriptionDetails(
            firstName + " " + lastName,
            postcode,
            countryCode
          )
        )

      case "Trustee" =>
        val orgName = (basePath \ "typeOfPersonDetails" \ "organisationName").as[String]
        JsSuccess(
          CgtSubscriptionDetails(
            orgName,
            postcode,
            countryCode
          )
        )

      case e => JsError(s"Unexpected typeOfPerson from DES for CGT: $e")
    }
  }
}
