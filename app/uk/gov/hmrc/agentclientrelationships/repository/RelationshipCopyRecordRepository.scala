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
import org.mongodb.scala.MongoWriteException
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model._
import play.api.Logging
import play.api.libs.json.Json.format
import play.api.libs.json._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.MongoLocalDateTimeFormat
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord.formats
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.time.temporal.ChronoUnit.MILLIS
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/* Despite the name not just for copy across, also used as CreateRecord recovery */
case class RelationshipCopyRecord(
  arn: String,
  enrolmentKey: Option[EnrolmentKey], // APB-7215 - added to accommodate multiple identifiers (cbc)
  clientIdentifier: Option[String] = None, // Deprecated - for legacy use only. Use the enrolment key instead.
  clientIdentifierType: Option[String] = None, // Deprecated - for legacy use only. Use the enrolment key instead.
  references: Option[Set[RelationshipReference]] = None,
  dateTime: LocalDateTime = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime.truncatedTo(MILLIS),
  syncToETMPStatus: Option[SyncStatus] = None,
  syncToESStatus: Option[SyncStatus] = None
) {

  // Legacy records use client id & client id type. Newer records use enrolment key.
  require(enrolmentKey.isDefined || (clientIdentifier.isDefined && clientIdentifierType.isDefined))

  def actionRequired: Boolean = needToCreateEtmpRecord || needToCreateEsRecord

  def needToCreateEtmpRecord: Boolean = !syncToETMPStatus.contains(Success)

  def needToCreateEsRecord: Boolean = !(syncToESStatus.contains(Success) || syncToESStatus.contains(InProgress))

}

object RelationshipCopyRecord {

  implicit val localDateTimeFormat: Format[LocalDateTime] = MongoLocalDateTimeFormat.localDateTimeFormat
  implicit val formats: OFormat[RelationshipCopyRecord] = format[RelationshipCopyRecord]

}

@ImplementedBy(classOf[MongoRelationshipCopyRecordRepository])
trait RelationshipCopyRecordRepository {

  def create(record: RelationshipCopyRecord): Future[Int]
  def findBy(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Option[RelationshipCopyRecord]]
  def updateEtmpSyncStatus(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    status: SyncStatus
  ): Future[Int]
  def updateEsSyncStatus(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    status: SyncStatus
  ): Future[Int]
  def remove(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Int]
  def terminateAgent(arn: Arn): Future[Either[String, Int]]

}

@Singleton
class MongoRelationshipCopyRecordRepository @Inject() (mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
extends PlayMongoRepository[RelationshipCopyRecord](
  mongoComponent = mongoComponent,
  collectionName = "relationship-copy-record",
  domainFormat = formats,
  indexes = Seq(
    // Note: these are *partial* indexes as sometimes we index on clientIdentifier, other times on enrolmentKey.
    // The situation will be simplified after a migration of the legacy documents.
    IndexModel(
      ascending(
        "arn",
        "clientIdentifier",
        "clientIdentifierType"
      ),
      IndexOptions()
        .name("arnAndAgentReferencePartial")
        .partialFilterExpression(Filters.exists("clientIdentifier"))
        .unique(true)
    ),
    IndexModel(
      ascending("arn", "enrolmentKey"),
      IndexOptions()
        .name("arnAndEnrolmentKeyPartial")
        .partialFilterExpression(Filters.exists("enrolmentKey"))
        .unique(true)
    )
  ),
  replaceIndexes = true
)
with RelationshipCopyRecordRepository
with Logging {

  private val INDICATE_ERROR_DURING_DB_UPDATE = 0

  override def create(record: RelationshipCopyRecord): Future[Int] = collection
    .findOneAndReplace(
      filter(
        Arn(record.arn),
        record.enrolmentKey.get
      ), // we assume that all newly created records WILL have an enrolment key
      record,
      FindOneAndReplaceOptions().upsert(true)
    )
    .toFuture()
    .map(_ => 1)

  override def findBy(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Option[RelationshipCopyRecord]] = collection.find(filter(arn, enrolmentKey)).headOption()

  override def updateEtmpSyncStatus(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    status: SyncStatus
  ): Future[Int] = collection
    .updateMany(filter(arn, enrolmentKey), Updates.set("syncToETMPStatus", status.toString))
    .toFuture()
    .map(res => res.getModifiedCount.toInt)
    .recover { case e: MongoWriteException =>
      logger.warn(s"Updating ETMP sync status ($status) failed: ${e.getMessage}");
      INDICATE_ERROR_DURING_DB_UPDATE
    }

  override def updateEsSyncStatus(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    status: SyncStatus
  ): Future[Int] = collection
    .updateMany(filter(arn, enrolmentKey), Updates.set("syncToESStatus", status.toString))
    .toFuture()
    .map(res => res.getModifiedCount.toInt)
    .recover { case e: MongoWriteException =>
      logger.warn(s"Updating ES sync status ($status) failed: ${e.getMessage}");
      INDICATE_ERROR_DURING_DB_UPDATE
    }

  override def remove(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Int] = collection.deleteMany(filter(arn, enrolmentKey)).toFuture().map(res => res.getDeletedCount.toInt)

  override def terminateAgent(arn: Arn): Future[Either[String, Int]] = collection
    .deleteMany(Filters.equal("arn", arn.value))
    .toFuture()
    .map(res => Right(res.getDeletedCount.toInt))
    .recover { case ex: MongoWriteException => Left(ex.getMessage) }

  private def filter(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ) = {
    val identifierType: String = enrolmentKey.identifiers.head.key
    val identifier: String = enrolmentKey.identifiers.head.value
    Filters.or(
      Filters.and(Filters.equal("arn", arn.value), Filters.equal("enrolmentKey", enrolmentKey.tag)),
      Filters.and(
        Filters.equal("arn", arn.value),
        Filters.equal("clientIdentifier", identifier),
        Filters.equal("clientIdentifierType", identifierType)
      )
    )
  }

}
