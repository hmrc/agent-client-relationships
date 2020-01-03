/*
 * Copyright 2020 HM Revenue & Customs
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

import java.util.UUID

import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.Json.format
import play.api.libs.json._
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.{BSONDocument, BSONObjectID}
import reactivemongo.play.json.ImplicitBSONHandlers
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.mongo.{AtomicUpdate, ReactiveRepository}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

case class RecoveryRecord(uid: String, runAt: DateTime)

object RecoveryRecord extends ReactiveMongoFormats {
  implicit val formats: Format[RecoveryRecord] = format[RecoveryRecord]
}

trait RecoveryScheduleRepository {
  def read(implicit ec: ExecutionContext): Future[RecoveryRecord]
  def write(nextUid: String, nextRunAt: DateTime)(implicit ec: ExecutionContext): Future[Unit]
}

@Singleton
class MongoRecoveryScheduleRepository @Inject()(mongoComponent: ReactiveMongoComponent)
    extends ReactiveRepository[RecoveryRecord, BSONObjectID](
      "recovery-schedule",
      mongoComponent.mongoConnector.db,
      RecoveryRecord.formats,
      ReactiveMongoFormats.objectIdFormats)
    with RecoveryScheduleRepository
    with StrictlyEnsureIndexes[RecoveryRecord, BSONObjectID] {

  import ImplicitBSONHandlers._

  override def indexes =
    Seq(Index(Seq("uid" -> Ascending, "runAt" -> Ascending), unique = true))

  def read(implicit ec: ExecutionContext): Future[RecoveryRecord] =
    findAll().flatMap(_.headOption match {
      case Some(record) => Future.successful(record)
      case None =>
        val record = RecoveryRecord(UUID.randomUUID().toString, DateTime.now())
        insert(record).map(_ => record).recoverWith {
          case NonFatal(error) =>
            Logger(getClass).warn(s"Creating RecoveryRecord failed: ${error.getMessage}")
            Future.failed(error)
        }
    })

  def write(newUid: String, newRunAt: DateTime)(implicit ec: ExecutionContext): Future[Unit] =
    findAndUpdate(
      query = Json.obj(),
      update =
        Json.obj("$set" -> Json.obj("uid" -> newUid, "runAt" -> ReactiveMongoFormats.dateTimeWrite.writes(newRunAt))),
      upsert = true
    ).map(_.lastError.foreach(error => Logger(getClass).warn(s"Updating uid and runAt failed with error: $error")))

}
