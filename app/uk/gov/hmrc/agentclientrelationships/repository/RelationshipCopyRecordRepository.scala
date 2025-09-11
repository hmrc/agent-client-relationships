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

package uk.gov.hmrc.agentclientrelationships.repository

import org.apache.pekko.Done
import org.mongodb.scala.MongoWriteException
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model._
import play.api.libs.json.Json.format
import play.api.libs.json._
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.MongoLocalDateTimeFormat
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord.formats
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.mdc.Mdc
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit.MILLIS
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

case class RelationshipCopyRecord(
  arn: String,
  enrolmentKey: EnrolmentKey,
  references: Option[Set[RelationshipReference]] = None,
  dateTime: LocalDateTime = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime.truncatedTo(MILLIS),
  syncToETMPStatus: Option[SyncStatus] = None,
  syncToESStatus: Option[SyncStatus] = None
) {

  def actionRequired: Boolean = needToCreateEtmpRecord || needToCreateEsRecord

  def needToCreateEtmpRecord: Boolean = !syncToETMPStatus.contains(Success)

  def needToCreateEsRecord: Boolean = !(syncToESStatus.contains(Success) || syncToESStatus.contains(InProgress))

}

object RelationshipCopyRecord {

  implicit val localDateTimeFormat: Format[LocalDateTime] = MongoLocalDateTimeFormat.localDateTimeFormat
  implicit val formats: OFormat[RelationshipCopyRecord] = format[RelationshipCopyRecord]

}

@Singleton
class RelationshipCopyRecordRepository @Inject() (mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
extends PlayMongoRepository[RelationshipCopyRecord](
  mongoComponent = mongoComponent,
  collectionName = "relationship-copy-record",
  domainFormat = formats,
  indexes = Seq(
    IndexModel(
      ascending("arn", "enrolmentKey"),
      IndexOptions()
        .name("arnAndEnrolmentKeyPartial")
        .unique(true)
    )
  ),
  replaceIndexes = true
)
with RequestAwareLogging {

  def create(record: RelationshipCopyRecord): Future[Done] = Mdc.preservingMdc {
    collection
      .findOneAndReplace(
        filter(
          Arn(record.arn),
          record.enrolmentKey
        ),
        record,
        FindOneAndReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => Done)
  }

  def findBy(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Option[RelationshipCopyRecord]] = collection.find(filter(arn, enrolmentKey)).headOption()

  def updateEtmpSyncStatus(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    status: SyncStatus
  )(implicit requestHeader: RequestHeader): Future[Done] = Mdc.preservingMdc {
    collection
      .updateMany(filter(arn, enrolmentKey), Updates.set("syncToETMPStatus", status.toString))
      .toFuture()
      .map { updateResult =>
        if (updateResult.getModifiedCount != 1L)
          logger.warn(s"Updated ${updateResult.getModifiedCount} documents when updating ETMP sync status to ($status)")
        Done
      }
  }

  def updateEsSyncStatus(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    status: SyncStatus
  )(implicit requestHeader: RequestHeader): Future[Done] = Mdc.preservingMdc {
    collection
      .updateMany(filter(arn, enrolmentKey), Updates.set("syncToESStatus", status.toString))
      .toFuture()
      .map { updateResult =>
        if (updateResult.getModifiedCount != 1L)
          logger.warn(s"Updated ${updateResult.getModifiedCount} documents when updating ES sync status to ($status)")
        Done
      }
  }

  def remove(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Int] = Mdc.preservingMdc {
    collection.deleteMany(filter(arn, enrolmentKey)).toFuture().map(res => res.getDeletedCount.toInt)
  }

  def terminateAgent(arn: Arn): Future[Either[String, Int]] = Mdc.preservingMdc {
    collection
      .deleteMany(Filters.equal("arn", arn.value))
      .toFuture()
      .map(res => Right(res.getDeletedCount.toInt))
      .recover { case ex: MongoWriteException => Left(ex.getMessage) }
  }

  private def filter(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ) = Filters.and(Filters.equal("arn", arn.value), Filters.equal("enrolmentKey", enrolmentKey.tag))

}
