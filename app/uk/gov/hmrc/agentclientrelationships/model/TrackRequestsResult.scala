/*
 * Copyright 2025 HM Revenue & Customs
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
import uk.gov.hmrc.crypto.json.JsonEncryption.stringEncrypterDecrypter
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter

case class TrackRequestsResult(
  pageNumber: Int,
  requests: Seq[Invitation],
  clientNames: Seq[String],
  availableFilters: Seq[String],
  filtersApplied: Option[Map[String, String]],
  totalResults: Int
)

object TrackRequestsResult {
  implicit val format: Format[TrackRequestsResult] = Json.format[TrackRequestsResult]
}

case class MongoClientNames(clientNames: Seq[String])
object MongoClientNames {

  def mongoFormat(implicit
    crypto: Encrypter
      with Decrypter
  ): Format[MongoClientNames] = {
    implicit val cryptoFormat: Format[String] = stringEncrypterDecrypter
    Json.format[MongoClientNames]
  }
  implicit val format: Format[MongoClientNames] = Json.format[MongoClientNames]

}
case class MongoAvailableFilters(availableFilters: Seq[String])
object MongoAvailableFilters {
  implicit val format: Format[MongoAvailableFilters] = Json.format[MongoAvailableFilters]
}
case class MongoTotalResults(count: Int)
object MongoTotalResults {
  implicit val format: Format[MongoTotalResults] = Json.format[MongoTotalResults]
}
case class MongoTrackRequestsResult(
  requests: Seq[Invitation] = Nil,
  clientNamesFacet: Seq[MongoClientNames] = Nil,
  availableFiltersFacet: Seq[MongoAvailableFilters] = Nil,
  totalResultsFacet: Seq[MongoTotalResults] = Nil
)
object MongoTrackRequestsResult {
  def format(implicit
    crypto: Encrypter
      with Decrypter
  ): Format[MongoTrackRequestsResult] = {
    implicit val invitationFormat: Format[Invitation] = Invitation.mongoFormat
    implicit val mongoClientNamesFormat: Format[MongoClientNames] = MongoClientNames.mongoFormat
    Json.format[MongoTrackRequestsResult]
  }
}
