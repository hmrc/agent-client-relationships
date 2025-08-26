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

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.mongodb.scala.MongoWriteException
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model._
import play.api.Logger
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
import scala.concurrent.duration.DurationInt

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

@Singleton
class RelationshipCopyRecordRepository @Inject() (mongoComponent: MongoComponent)(implicit
  ec: ExecutionContext,
  mat: Materializer
)
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
with RequestAwareLogging {

  private val INDICATE_ERROR_DURING_DB_UPDATE = 0

  def create(record: RelationshipCopyRecord): Future[Int] = Mdc.preservingMdc {
    collection
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
  }

  def findBy(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Option[RelationshipCopyRecord]] = collection.find(filter(arn, enrolmentKey)).headOption()

  def updateEtmpSyncStatus(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    status: SyncStatus
  )(implicit requestHeader: RequestHeader): Future[Int] = Mdc.preservingMdc {
    collection
      .updateMany(filter(arn, enrolmentKey), Updates.set("syncToETMPStatus", status.toString))
      .toFuture()
      .map(res => res.getModifiedCount.toInt)
      .recover { case e: MongoWriteException =>
        logger.warn(s"Updating ETMP sync status ($status) failed: ${e.getMessage}")
        INDICATE_ERROR_DURING_DB_UPDATE
      }
  }

  def updateEsSyncStatus(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    status: SyncStatus
  )(implicit requestHeader: RequestHeader): Future[Int] = Mdc.preservingMdc {
    collection
      .updateMany(filter(arn, enrolmentKey), Updates.set("syncToESStatus", status.toString))
      .toFuture()
      .map(res => res.getModifiedCount.toInt)
      .recover { case e: MongoWriteException =>
        logger.warn(s"Updating ES sync status ($status) failed: ${e.getMessage}")
        INDICATE_ERROR_DURING_DB_UPDATE
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

  private val deprecatedRecordsQuery: Bson = Filters.exists("clientIdentifier")

  def countDeprecatedRecords(): Future[Long] = collection.countDocuments(deprecatedRecordsQuery).toFuture()

  def convertDeprecatedRecords(): Unit = {
    val logger = Logger(getClass)
    val observable = collection.find(deprecatedRecordsQuery)
    countDeprecatedRecords().map { count =>
      logger.warn(s"Data conversion has started, $count deprecated documents scheduled for conversion")
    }

    collection.deleteMany(
      Filters.and(deprecatedRecordsQuery, Filters.eq("references", null))
    ).toFuture().map { deleteResult =>
      logger.warn(s"${deleteResult.getDeletedCount} deprecated records with null references deleted")
      Source
        .fromPublisher(observable)
        .throttle(10, 1.second)
        .runForeach { record =>
          collection.updateOne(
            Filters.eq("clientIdentifier", record.clientIdentifier.get),
            Updates.combine(
              Updates.set("enrolmentKey", s"HMRC-MTD-IT~MTDITID~${record.clientIdentifier.get}"),
              Updates.unset("clientIdentifier"),
              Updates.unset("clientIdentifierType")
            )
          ).toFuture()
            .map(updateResult => logger.warn(s"Documents updated: ${updateResult.getModifiedCount}"))
            .recover { case ex: Throwable => logger.warn("Failed to replace record", ex) }
          ()
        }
        .recover {
          case ex: Throwable => logger.warn("Exception encountered when performing update", ex)
        }
        .onComplete { _ =>
          countDeprecatedRecords().map { count =>
            logger.warn(s"Conversion job completed, $count deprecated documents remain")
          }
        }
    }
  }

  convertDeprecatedRecords()

}
