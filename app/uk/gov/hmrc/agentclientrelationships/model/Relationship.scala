/*
 * Copyright 2016 HM Revenue & Customs
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

import org.joda.time.DateTime
import play.api.libs.json.Json
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.domain.{SimpleObjectReads, SimpleObjectWrites}
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

case class Arn(value: String)
object Arn {
  implicit val writes = new SimpleObjectWrites[Arn](_.value)
  implicit val reads = new SimpleObjectReads[Arn]("arn", Arn.apply)
}

object Relationship {
  implicit val oidFormats = ReactiveMongoFormats.objectIdFormats
  implicit val jsonFormats = Json.format[Relationship]
  val mongoFormats = ReactiveMongoFormats.mongoEntity(jsonFormats)
}

case class Relationship (id: BSONObjectID,
                         arn: Arn,
                         regime: String,
                         clientRegimeId: String,
                         created: DateTime,
                         removed: Option[DateTime] = None) {
  val isRemoved = removed.isDefined
}
