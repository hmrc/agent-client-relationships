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

import play.api.libs.json.{JsPath, Reads}

import java.time.LocalDate
import java.time.format.DateTimeFormatter

case class ItsaCitizenDetails(
  firstName: Option[String],
  lastName: Option[String],
  dateOfBirth: Option[LocalDate],
  saUtr: Option[String]
) {
  lazy val name: Option[String] = {
    val n = Seq(firstName, lastName).collect { case Some(x) => x }.mkString(" ")
    if (n.isEmpty) None else Some(n)
  }
}

object ItsaCitizenDetails {

  val citizenDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("ddMMyyyy")

  implicit val reads: Reads[ItsaCitizenDetails] = for {
    firstName <- (JsPath \ "name" \ "current" \ "firstName").readNullable[String]
    lastName  <- (JsPath \ "name" \ "current" \ "lastName").readNullable[String]
    dateOfBirth <-
      (JsPath \ "dateOfBirth").readNullable[String].map(_.map(date => LocalDate.parse(date, citizenDateFormatter)))
    saUtr <- (JsPath \ "ids" \ "sautr").readNullable[String]
  } yield ItsaCitizenDetails(firstName, lastName, dateOfBirth, saUtr)
}
