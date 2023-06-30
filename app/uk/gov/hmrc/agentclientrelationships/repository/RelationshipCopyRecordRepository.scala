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

import javax.inject.{Inject, Singleton}
import org.mongodb.scala.MongoWriteException
import org.mongodb.scala.model.{Filters, FindOneAndReplaceOptions, IndexModel, IndexOptions, Updates}
import org.mongodb.scala.model.Indexes.ascending
import play.api.Logging
import play.api.libs.json.Json.format
import play.api.libs.json._
import uk.gov.hmrc.agentclientrelationships.model.MongoLocalDateTimeFormat
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord.formats
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, ClientIdentifier}
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.time.{Instant, LocalDateTime, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future}

/* Despite the name not just for copy across, also used as CreateRecord recovery */
case class RelationshipCopyRecord(
  arn: String,
  maybeEnrolmentKey: Option[String], // APB-7215 - added to accommodate multiple identifiers (cbc)
  clientIdentifier: String,
  clientIdentifierType: String,
  references: Option[Set[RelationshipReference]] = None,
  dateTime: LocalDateTime = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime,
  syncToETMPStatus: Option[SyncStatus] = None,
  syncToESStatus: Option[SyncStatus] = None) {
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
  def findBy(arn: Arn, identifier: TaxIdentifier): Future[Option[RelationshipCopyRecord]]
  def updateEtmpSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus): Future[Int]
  def updateEsSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus): Future[Int]
  def remove(arn: Arn, identifier: TaxIdentifier): Future[Int]
  def terminateAgent(arn: Arn): Future[Either[String, Int]]
}

@Singleton
class MongoRelationshipCopyRecordRepository @Inject()(mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[RelationshipCopyRecord](
      mongoComponent = mongoComponent,
      collectionName = "relationship-copy-record",
      domainFormat = formats,
      indexes = Seq(
        IndexModel(
          ascending("arn", "clientIdentifier", "clientIdentifierType"),
          IndexOptions().name("arnAndAgentReference").unique(true))
      )
    )
    with RelationshipCopyRecordRepository
    with Logging {

  private val INDICATE_ERROR_DURING_DB_UPDATE = 0

  override def create(record: RelationshipCopyRecord): Future[Int] =
    collection
      .findOneAndReplace(
        Filters.and(
          Filters.equal("arn", record.arn),
          Filters.equal("clientIdentifier", record.clientIdentifier),
          Filters.equal("clientIdentifierType", record.clientIdentifierType)
        ),
        record,
        FindOneAndReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => 1)

  override def findBy(arn: Arn, identifier: TaxIdentifier): Future[Option[RelationshipCopyRecord]] =
    collection
      .find(
        Filters.and(
          Filters.equal("arn", arn.value),
          Filters.equal("clientIdentifier", identifier.value),
          Filters.equal("clientIdentifierType", ClientIdentifier(identifier).enrolmentId)
        ))
      .headOption()

  override def updateEtmpSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus): Future[Int] =
    collection
      .updateMany(
        Filters.and(
          Filters.equal("arn", arn.value),
          Filters.equal("clientIdentifier", identifier.value),
          Filters.equal("clientIdentifierType", ClientIdentifier(identifier).enrolmentId)
        ),
        Updates.set("syncToETMPStatus", status.toString)
      )
      .toFuture()
      .map(res => res.getModifiedCount.toInt)
      .recover {
        case e: MongoWriteException =>
          logger.warn(s"Updating ETMP sync status ($status) failed: ${e.getMessage}"); INDICATE_ERROR_DURING_DB_UPDATE
      }

  override def updateEsSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus): Future[Int] =
    collection
      .updateMany(
        Filters.and(
          Filters.equal("arn", arn.value),
          Filters.equal("clientIdentifier", identifier.value),
          Filters.equal("clientIdentifierType", ClientIdentifier(identifier).enrolmentId)
        ),
        Updates.set("syncToESStatus", status.toString)
      )
      .toFuture()
      .map(res => res.getModifiedCount.toInt)
      .recover {
        case e: MongoWriteException =>
          logger.warn(s"Updating ES sync status ($status) failed: ${e.getMessage}"); INDICATE_ERROR_DURING_DB_UPDATE
      }

  override def remove(arn: Arn, identifier: TaxIdentifier): Future[Int] =
    collection
      .deleteMany(
        Filters.and(
          Filters.equal("arn", arn.value),
          Filters.equal("clientIdentifier", identifier.value),
          Filters.equal("clientIdentifierType", ClientIdentifier(identifier).enrolmentId)
        ))
      .toFuture()
      .map(res => res.getDeletedCount.toInt)

  override def terminateAgent(arn: Arn): Future[Either[String, Int]] =
    collection
      .deleteMany(Filters.equal("arn", arn.value))
      .toFuture()
      .map(res => Right(res.getDeletedCount.toInt))
      .recover {
        case ex: MongoWriteException => Left(ex.getMessage)
      }
}
