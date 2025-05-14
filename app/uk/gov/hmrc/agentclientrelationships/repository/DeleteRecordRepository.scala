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
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Updates.combine
import org.mongodb.scala.model.Updates.inc
import org.mongodb.scala.model.Updates.set
import org.mongodb.scala.model._
import play.api.Logging
import play.api.libs.json.Json.format
import play.api.libs.json._
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.MongoLocalDateTimeFormat
import uk.gov.hmrc.agentclientrelationships.repository.DeleteRecord.formats
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.http.Authorization
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.SessionId
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
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport._

case class DeleteRecord(
  arn: String,
  enrolmentKey: Option[EnrolmentKey], // APB-7215 - added to accommodate multiple identifiers (cbc)
  clientIdentifier: Option[String] = None, // Deprecated - for legacy use only. Use the enrolment key instead.
  clientIdentifierType: Option[String] = None, // Deprecated - for legacy use only. Use the enrolment key instead.
  dateTime: LocalDateTime = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime.truncatedTo(MILLIS),
  syncToETMPStatus: Option[SyncStatus] = None,
  syncToESStatus: Option[SyncStatus] = None,
  lastRecoveryAttempt: Option[LocalDateTime] = None,
  numberOfAttempts: Int = 0,
  headerCarrier: Option[HeaderCarrier] = None,
  relationshipEndedBy: Option[String] = None
) {

  // Legacy records use client id & client id type. Newer records use enrolment key.
  require(enrolmentKey.isDefined || (clientIdentifier.isDefined && clientIdentifierType.isDefined))

  def actionRequired: Boolean = needToDeleteEtmpRecord || needToDeleteEsRecord

  def needToDeleteEtmpRecord: Boolean = !(syncToETMPStatus.contains(Success) || syncToETMPStatus.contains(InProgress))

  def needToDeleteEsRecord: Boolean = !(syncToESStatus.contains(Success) || syncToESStatus.contains(InProgress))

}

object DeleteRecord {

  implicit val dateReads: Reads[LocalDateTime] = MongoLocalDateTimeFormat.localDateTimeReads
  implicit val dateWrites: Writes[LocalDateTime] = MongoLocalDateTimeFormat.localDateTimeWrites

  implicit val hcWrites: OWrites[HeaderCarrier] =
    new OWrites[HeaderCarrier] {
      override def writes(hc: HeaderCarrier): JsObject = JsObject(
        Seq(
          "authorization" -> hc.authorization.map(_.value),
          "sessionId" -> hc.sessionId.map(_.value),
          "gaToken" -> hc.gaToken
        ).collect { case (key, Some(value)) => (key, JsString(value)) }
      )
    }

  import play.api.libs.functional.syntax._

  implicit val reads: Reads[HeaderCarrier] =
    (
      (JsPath \ "authorization").readNullable[String].map(_.map(Authorization.apply)) and
        (JsPath \ "sessionId").readNullable[String].map(_.map(SessionId.apply)) and
        (JsPath \ "gaToken").readNullable[String]
    )(
      (
        a,
        s,
        g
      ) =>
        HeaderCarrier(
          authorization = a,
          sessionId = s,
          gaToken = g
        )
    )

  implicit val formats: Format[DeleteRecord] = format[DeleteRecord]

}

@ImplementedBy(classOf[MongoDeleteRecordRepository])
trait DeleteRecordRepository {

  def create(record: DeleteRecord): Future[Int]
  def findBy(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Option[DeleteRecord]]
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
  def markRecoveryAttempt(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Unit]
  def remove(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Int]
  def selectNextToRecover(): Future[Option[DeleteRecord]]

  def terminateAgent(arn: Arn): Future[Either[String, Int]]

}

@Singleton
class MongoDeleteRecordRepository @Inject() (mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
extends PlayMongoRepository[DeleteRecord](
  mongoComponent = mongoComponent,
  collectionName = "delete-record",
  domainFormat = formats,
  indexes = Seq(
    // Note: these are *partial* indexes as sometimes we index on clientIdentifier, other times on enrolmentKey.
    // The situation will be simplified after a migration of the legacy documents.
    IndexModel(
      Indexes.ascending(
        "arn",
        "clientIdentifier",
        "clientIdentifierType"
      ),
      IndexOptions()
        .partialFilterExpression(Filters.exists("clientIdentifier"))
        .unique(true)
        .name("arnAndAgentReferencePartial")
    ),
    IndexModel(
      Indexes.ascending("arn", "enrolmentKey"),
      IndexOptions()
        .partialFilterExpression(Filters.exists("enrolmentKey"))
        .unique(true)
        .name("arnAndEnrolmentKeyPartial")
    )
  ),
  replaceIndexes = true
)
with DeleteRecordRepository
with Logging {

  private val INDICATE_ERROR_DURING_DB_UPDATE = 0

  override def create(record: DeleteRecord): Future[Int] = collection
    .insertOne(record)
    .toFuture()
    .map(insertResult =>
      if (insertResult.wasAcknowledged())
        1
      else {
        logger.warn("Creating DeleteRecord failed.")
        INDICATE_ERROR_DURING_DB_UPDATE
      }
    )

  override def findBy(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Option[DeleteRecord]] = collection.find(filter(arn, enrolmentKey)).headOption()

  override def updateEtmpSyncStatus(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    status: SyncStatus
  ): Future[Int] = collection
    .updateOne(
      filter(arn, enrolmentKey),
      set("syncToETMPStatus", status.toString),
      UpdateOptions().upsert(false)
    )
    .toFuture()
    .map { updateResult =>
      if (updateResult.getModifiedCount != 1L)
        logger.warn(s"Updating ETMP sync status ($status) failed")
      updateResult.getModifiedCount.toInt
    }

  override def updateEsSyncStatus(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    status: SyncStatus
  ): Future[Int] = collection
    .updateOne(
      filter(arn, enrolmentKey),
      set("syncToESStatus", status.toString),
      UpdateOptions().upsert(false)
    )
    .toFuture()
    .map { updateResult =>
      if (updateResult.getModifiedCount != 1L)
        logger.warn(s"Updating ES sync status ($status) failed")
      updateResult.getModifiedCount.toInt
    }

  override def markRecoveryAttempt(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Unit] = collection
    .findOneAndUpdate(
      filter(arn, enrolmentKey),
      combine(
        set("lastRecoveryAttempt", Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime),
        inc("numberOfAttempts", 1)
      )
    )
    .toFuture()
    .map(_ => ())

  override def remove(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Int] = collection
    .deleteOne(filter(arn, enrolmentKey))
    .toFuture()
    .map(deleteResult => deleteResult.getDeletedCount.toInt)

  override def selectNextToRecover(): Future[Option[DeleteRecord]] = collection
    .find()
    .sort(Sorts.ascending("lastRecoveryAttempt"))
    .headOption()

  override def terminateAgent(arn: Arn): Future[Either[String, Int]] = collection
    .deleteMany(equal("arn", arn.value))
    .toFuture()
    .map(deleteResult => Right(deleteResult.getDeletedCount.toInt))
    .recover { case e: MongoWriteException => Left(e.getMessage) }

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
