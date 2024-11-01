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

package uk.gov.hmrc.agentclientrelationships.model.clientDetails.pillar2

import play.api.libs.json.{JsPath, Reads}

case class Pillar2Record(organisationName: String, registrationDate: String, countryCode: String, inactive: Boolean)

object Pillar2Record {
  implicit val reads: Reads[Pillar2Record] = for {
    orgName     <- (JsPath \ "upeDetails" \ "organisationName").read[String]
    regDate     <- (JsPath \ "upeDetails" \ "registrationDate").read[String]
    countryCode <- (JsPath \ "upeCorrespAddressDetails" \ "countryCode").read[String]
    inactive    <- (JsPath \ "accountStatus" \ "inactive").readNullable[Boolean].map(_.getOrElse(false))
  } yield Pillar2Record(orgName, regDate, countryCode, inactive)
}
