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

import play.api.libs.json.*
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

object TrackRequestsResult:
  given format: Format[TrackRequestsResult] = Json.format[TrackRequestsResult]

case class MongoClientNames(clientNames: Seq[String])
object MongoClientNames {

  def mongoFormat(using
    crypto: Encrypter
      & Decrypter
  ): Format[MongoClientNames] = {
    given cryptoFormat: Format[String] = stringEncrypterDecrypter
    Json.format[MongoClientNames]
  }
  given format: Format[MongoClientNames] = Json.format[MongoClientNames]

}
case class MongoAvailableFilters(availableFilters: Seq[String])
object MongoAvailableFilters:
  given format: Format[MongoAvailableFilters] = Json.format[MongoAvailableFilters]

case class MongoTotalResults(count: Int)
object MongoTotalResults:
  given format: Format[MongoTotalResults] = Json.format[MongoTotalResults]

case class MongoTrackRequestsResult(
  requests: Seq[Invitation] = Nil,
  clientNamesFacet: Seq[MongoClientNames] = Nil,
  availableFiltersFacet: Seq[MongoAvailableFilters] = Nil,
  totalResultsFacet: Seq[MongoTotalResults] = Nil
)
object MongoTrackRequestsResult {
  def format(using
    crypto: Encrypter
      & Decrypter
  ): Format[MongoTrackRequestsResult] = {
    given invitationFormat: Format[Invitation] = Invitation.mongoFormat
    given mongoClientNamesFormat: Format[MongoClientNames] = MongoClientNames.mongoFormat
    Json.format[MongoTrackRequestsResult]
  }
}
