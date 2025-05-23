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

import play.api.libs.json._
import uk.gov.hmrc.agentmtdidentifiers.model.Arn

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

case class ClientRelationshipWithAgentName(
  arn: Arn,
  agentName: String,
  service: String,
  dateTo: Option[LocalDate],
  dateFrom: Option[LocalDate]
) {
  def isActive: Boolean =
    dateTo match {
      case None => true
      case Some(d) => d.isAfter(Instant.now().atZone(ZoneOffset.UTC).toLocalDate)
    }
}

object ClientRelationshipWithAgentName {
  implicit val format: OFormat[ClientRelationshipWithAgentName] = Json.format[ClientRelationshipWithAgentName]
}
