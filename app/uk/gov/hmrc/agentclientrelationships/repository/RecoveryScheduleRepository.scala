/*
 * Copyright 2018 HM Revenue & Customs
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
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.mongo.{AtomicUpdate, ReactiveRepository}

import scala.concurrent.{ExecutionContext, Future}

case class RecoveryRecord(uid: String, runAt: String)

object RecoveryRecord extends ReactiveMongoFormats {
  implicit val formats: Format[RecoveryRecord] = format[RecoveryRecord]
}

trait RecoveryScheduleRepository {
  def create(record: RecoveryRecord)(implicit ec: ExecutionContext): Future[Int]
  def findBy(uid: String)(implicit ec: ExecutionContext): Future[Option[RecoveryRecord]]
  def update(uid: String, newUid: String, newRunAt: String)(implicit ec: ExecutionContext): Future[Unit]
}

@Singleton
class MongoRecoveryScheduleRepository @Inject()(mongoComponent: ReactiveMongoComponent)
    extends ReactiveRepository[RecoveryRecord, BSONObjectID](
      "deauth-failure-record",
      mongoComponent.mongoConnector.db,
      RecoveryRecord.formats,
      ReactiveMongoFormats.objectIdFormats)
    with RecoveryScheduleRepository
    with StrictlyEnsureIndexes[RecoveryRecord, BSONObjectID]
    with AtomicUpdate[RecoveryRecord] {

  override def indexes =
    Seq(Index(Seq("uid" -> Ascending, "runAt" -> Ascending), unique = true))

  def create(record: RecoveryRecord)(implicit ec: ExecutionContext): Future[Int] =
    insert(record).map { result =>
      result.writeErrors.foreach(error => Logger(getClass).warn(s"Creating DeleteRecord failed: ${error.errmsg}"))
      result.n
    }

  def findBy(uid: String)(implicit ec: ExecutionContext): Future[Option[RecoveryRecord]] =
    find("uid" -> uid)
      .map(_.headOption)

  def update(uid: String, newUid: String, newRunAt: String)(implicit ec: ExecutionContext): Future[Unit] =
    atomicUpdate(
      finder = BSONDocument("uid"        -> uid),
      modifierBson = BSONDocument("$set" -> BSONDocument("uid" -> newUid, "runAt" -> newRunAt))
    ).map(_.foreach { update =>
      update.writeResult.errMsg.foreach(error =>
        Logger(getClass).warn(s"Updating uid and runAt failed with error: $error"))
    })

  override def isInsertion(newRecordId: BSONObjectID, oldRecord: RecoveryRecord): Boolean = false
}
