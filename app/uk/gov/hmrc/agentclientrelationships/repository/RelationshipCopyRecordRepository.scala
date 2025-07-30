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

import org.mongodb.scala.MongoWriteException
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model._
import play.api.libs.json.Json.format
import play.api.libs.json._
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.MongoLocalDateTimeFormat
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord.formats
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.services.MongoLockService
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport.thereIsNoRequest
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.play.http.logging.Mdc

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit.MILLIS
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

@Singleton
class RelationshipCopyRecordRepository @Inject() (
  mongoComponent: MongoComponent,
  mongoLockService: MongoLockService
)(implicit ec: ExecutionContext)
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
        logger.warn(s"Updating ETMP sync status ($status) failed: ${e.getMessage}");
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
        logger.warn(s"Updating ES sync status ($status) failed: ${e.getMessage}");
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

  def queryOnStartup(): Unit = {
    mongoLockService.lock("lockForQueries") {
      for {
        deprecated <- collection.countDocuments(Filters.exists("enrolmentKey", exists = false)).toFuture()
        withEnrolment <- collection.countDocuments(Filters.exists("enrolmentKey")).toFuture()
        totalFailedEtmp <- collection.countDocuments(Filters.and(
          Filters.exists("syncToETMPStatus"),
          Filters.ne("syncToETMPStatus", Success.toString)
        )).toFuture()
        totalFailedEs <- collection.countDocuments(Filters.and(
          Filters.exists("syncToESStatus"),
          Filters.ne("syncToESStatus", Success.toString)
        )).toFuture()
        copyAcrossItsaOld <- collection.countDocuments(Filters.and(
          Filters.ne("references", Set()),
          Filters.eq("clientIdentifierType", "MTDITID")
        )).toFuture()
        copyAcrossItsaNew <- collection.countDocuments(Filters.and(
          Filters.ne("references", Set()),
          Filters.regex("enrolmentKey", """HMRC-MTD-IT~MTDITID~""")
        )).toFuture()
        copyAcrossItsaFailed <- collection.countDocuments(Filters.and(
          Filters.ne("references", Set()),
          Filters.ne("syncToETMPStatus", Success.toString), // copy across does not fail when ES fails, only requires etmp
          Filters.or(
            Filters.eq("clientIdentifierType", "MTDITID"),
            Filters.regex("enrolmentKey", """HMRC-MTD-IT~MTDITID~""")
          )
        )).toFuture()
        copyAcrossItsaFailedRecent <- collection.countDocuments(Filters.and(
          Filters.ne("references", Set()),
          Filters.ne("syncToETMPStatus", Success.toString), // copy across does not fail when ES fails, only requires etmp
          Filters.gte("dateTime", Instant.now().atZone(ZoneOffset.UTC).minusDays(30).toLocalDateTime),
          Filters.or(
            Filters.eq("clientIdentifierType", "MTDITID"),
            Filters.regex("enrolmentKey", """HMRC-MTD-IT~MTDITID~""")
          )
        )).toFuture()
        copyAcrossVat <- collection.countDocuments(Filters.and(
          Filters.ne("references", Set()),
          Filters.or(
            Filters.eq("clientIdentifierType", "VRN"),
            Filters.regex("enrolmentKey", """HMRC-MTD-VAT~VRN~""")
          )
        )).toFuture()
      } yield {
        logger.warn(
          s"[RelationshipCopyRecordRepository] Querying copy record repository: \n" +
            s"Total deprecated docs: $deprecated, Total new format docs: $withEnrolment, \n" +
            s"Total failed ETMP docs: $totalFailedEtmp, Total failed ES docs: $totalFailedEs, \n" +
            s"Total VAT copy across docs: $copyAcrossVat, \n" +
            s"Total deprecated ITSA copy across docs: $copyAcrossItsaOld, Total new format ITSA copy across docs: $copyAcrossItsaNew, \n" +
            s"Total failed ITSA copy across docs: $copyAcrossItsaFailed, Total recently (30d) failed ITSA copy across docs: $copyAcrossItsaFailedRecent"
        )(thereIsNoRequest)
      }
    }
  }

  queryOnStartup()

}
