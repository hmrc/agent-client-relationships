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

package uk.gov.hmrc.agentclientrelationships.repository

import com.google.inject.ImplementedBy
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Updates.set
import org.mongodb.scala.model._
import play.api.Logging
import play.api.libs.json.Json.format
import play.api.libs.json._
import uk.gov.hmrc.agentclientrelationships.model.MongoLocalDateTimeFormat
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.time.{LocalDateTime, ZoneOffset}
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

case class RecoveryRecord(uid: String, runAt: LocalDateTime)

object RecoveryRecord {

  implicit val localDateTimeFormat: Format[LocalDateTime] = MongoLocalDateTimeFormat.localDateTimeFormat
  implicit val formats: Format[RecoveryRecord] = format[RecoveryRecord]
}

@ImplementedBy(classOf[MongoRecoveryScheduleRepository])
trait RecoveryScheduleRepository {
  def read: Future[RecoveryRecord]
  def write(nextUid: String, nextRunAt: LocalDateTime): Future[Unit]
}

@Singleton
class MongoRecoveryScheduleRepository @Inject() (mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[RecoveryRecord](
      mongoComponent = mongoComponent,
      collectionName = "recovery-schedule",
      domainFormat = RecoveryRecord.formats,
      indexes = Seq(IndexModel(ascending("uid", "runAt"), IndexOptions().unique(true))),
      replaceIndexes = true
    )
    with RecoveryScheduleRepository
    with Logging {

  override def read: Future[RecoveryRecord] = collection
    .find()
    .headOption()
    .flatMap {
      case Some(record) => Future successful record
      case None =>
        {
          val record = RecoveryRecord(
            UUID.randomUUID().toString,
            LocalDateTime.now().atZone(ZoneOffset.UTC).toLocalDateTime
          )
          collection.insertOne(record).toFuture().map(_ => record)
        }.recoverWith { case NonFatal(error) =>
          logger.warn(s"Creating RecoveryRecord failed: ${error.getMessage}")
          Future.failed(error)
        }
    }

  override def write(newUid: String, newRunAt: LocalDateTime): Future[Unit] = collection
    .findOneAndUpdate(
      Filters.exists("uid"),
      Updates.combine(set("uid", newUid), set("runAt", newRunAt)),
      FindOneAndUpdateOptions().upsert(true)
    )
    .toFuture()
    .map(_ => ())

}
