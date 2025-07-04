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

package uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa

import play.api.libs.json.JsPath
import play.api.libs.json.Reads

case class ItsaDesignatoryDetails(
  postCode: Option[String],
  country: Option[String]
)

object ItsaDesignatoryDetails {

  implicit val reads: Reads[ItsaDesignatoryDetails] =
    for {
      postCode <- (JsPath \ "address" \ "postcode").readNullable[String]
      country <- (JsPath \ "address" \ "country").readNullable[String]
    } yield ItsaDesignatoryDetails(postCode, country)

}
