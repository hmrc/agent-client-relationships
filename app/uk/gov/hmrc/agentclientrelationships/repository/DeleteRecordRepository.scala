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
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.Updates.{combine, inc, set}
import org.mongodb.scala.model._
import play.api.Logging
import play.api.libs.json.Json.format
import play.api.libs.json._
import uk.gov.hmrc.agentclientrelationships.model.MongoLocalDateTimeFormat
import uk.gov.hmrc.agentclientrelationships.repository.DeleteRecord.formats
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, ClientIdentifier}
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.{Authorization, HeaderCarrier, SessionId}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.time.{Instant, LocalDateTime, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

case class DeleteRecord(
  arn: String,
  service: Option[String],
  clientIdentifier: String,
  clientIdentifierType: String,
  dateTime: LocalDateTime = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime,
  syncToETMPStatus: Option[SyncStatus] = None,
  syncToESStatus: Option[SyncStatus] = None,
  lastRecoveryAttempt: Option[LocalDateTime] = None,
  numberOfAttempts: Int = 0,
  headerCarrier: Option[HeaderCarrier] = None,
  relationshipEndedBy: Option[String] = None) {
  def actionRequired: Boolean = needToDeleteEtmpRecord || needToDeleteEsRecord

  def needToDeleteEtmpRecord: Boolean = !(syncToETMPStatus.contains(Success) || syncToETMPStatus.contains(InProgress))

  def needToDeleteEsRecord: Boolean = !(syncToESStatus.contains(Success) || syncToESStatus.contains(InProgress))
}

object DeleteRecord {

  implicit val dateReads: Reads[LocalDateTime] = MongoLocalDateTimeFormat.localDateTimeReads
  implicit val dateWrites: Writes[LocalDateTime] = MongoLocalDateTimeFormat.localDateTimeWrites

  implicit val hcWrites: OWrites[HeaderCarrier] = new OWrites[HeaderCarrier] {
    override def writes(hc: HeaderCarrier): JsObject =
      JsObject(
        Seq(
          "authorization" -> hc.authorization.map(_.value),
          "sessionId"     -> hc.sessionId.map(_.value),
          "gaToken"       -> hc.gaToken
        ).collect {
          case (key, Some(value)) => (key, JsString(value))
        })
  }

  import play.api.libs.functional.syntax._

  implicit val reads: Reads[HeaderCarrier] = (
    (JsPath \ "authorization").readNullable[String].map(_.map(Authorization.apply)) and
      (JsPath \ "sessionId").readNullable[String].map(_.map(SessionId.apply)) and
      (JsPath \ "gaToken").readNullable[String]
  )((a, s, g) => HeaderCarrier(authorization = a, sessionId = s, gaToken = g))

  implicit val formats: Format[DeleteRecord] = format[DeleteRecord]
}

@ImplementedBy(classOf[MongoDeleteRecordRepository])
trait DeleteRecordRepository {
  def create(record: DeleteRecord): Future[Int]
  def findBy(arn: Arn, identifier: TaxIdentifier): Future[Option[DeleteRecord]]
  def updateEtmpSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus): Future[Int]
  def updateEsSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus): Future[Int]
  def markRecoveryAttempt(arn: Arn, identifier: TaxIdentifier): Future[Unit]
  def remove(arn: Arn, identifier: TaxIdentifier): Future[Int]
  def selectNextToRecover(): Future[Option[DeleteRecord]]

  def terminateAgent(arn: Arn): Future[Either[String, Int]]
}

@Singleton
class MongoDeleteRecordRepository @Inject()(mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[DeleteRecord](
      mongoComponent = mongoComponent,
      collectionName = "delete-record",
      domainFormat = formats,
      indexes = Seq(
        IndexModel(
          Indexes.ascending("arn", "clientIdentifier", "clientIdentifierType"),
          IndexOptions().unique(true).name("arnAndAgentReference"))
      )
    )
    with DeleteRecordRepository
    with Logging {

  private def clientIdentifierType(identifier: TaxIdentifier) = ClientIdentifier(identifier).enrolmentId

  private val INDICATE_ERROR_DURING_DB_UPDATE = 0

  override def create(record: DeleteRecord): Future[Int] =
    collection
      .insertOne(record)
      .toFuture()
      .map(insertResult =>
        if (insertResult.wasAcknowledged()) 1
        else {
          logger.warn("Creating DeleteRecord failed.")
          INDICATE_ERROR_DURING_DB_UPDATE
      })

  override def findBy(arn: Arn, identifier: TaxIdentifier): Future[Option[DeleteRecord]] =
    collection
      .find(
        and(
          equal("arn", arn.value),
          equal("clientIdentifier", identifier.value),
          equal("clientIdentifierType", clientIdentifierType(identifier))))
      .headOption()

  override def updateEtmpSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus): Future[Int] =
    collection
      .updateOne(
        and(
          equal("arn", arn.value),
          equal("clientIdentifier", identifier.value),
          equal("clientIdentifierType", clientIdentifierType(identifier))),
        set("syncToETMPStatus", status.toString),
        UpdateOptions().upsert(false)
      )
      .toFuture()
      .map(updateResult => {
        if (updateResult.getModifiedCount != 1L) logger.warn(s"Updating ETMP sync status ($status) failed")
        updateResult.getModifiedCount.toInt
      })

  override def updateEsSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus): Future[Int] =
    collection
      .updateOne(
        and(
          equal("arn", arn.value),
          equal("clientIdentifier", identifier.value),
          equal("clientIdentifierType", clientIdentifierType(identifier))),
        set("syncToESStatus", status.toString),
        UpdateOptions().upsert(false)
      )
      .toFuture()
      .map(updateResult => {
        if (updateResult.getModifiedCount != 1L) logger.warn(s"Updating ES sync status ($status) failed")
        updateResult.getModifiedCount.toInt
      })

  override def markRecoveryAttempt(arn: Arn, identifier: TaxIdentifier): Future[Unit] =
    collection
      .findOneAndUpdate(
        and(
          equal("arn", arn.value),
          equal("clientIdentifier", identifier.value),
          equal("clientIdentifierType", clientIdentifierType(identifier))),
        combine(
          set("lastRecoveryAttempt", Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime),
          inc("numberOfAttempts", 1))
      )
      .toFuture()
      .map(_ => ())

  override def remove(arn: Arn, identifier: TaxIdentifier): Future[Int] =
    collection
      .deleteOne(
        and(
          equal("arn", arn.value),
          equal("clientIdentifier", identifier.value),
          equal("clientIdentifierType", clientIdentifierType(identifier)))
      )
      .toFuture()
      .map(deleteResult => deleteResult.getDeletedCount.toInt)

  override def selectNextToRecover(): Future[Option[DeleteRecord]] =
    collection
      .find()
      .sort(Sorts.ascending("lastRecoveryAttempt"))
      .headOption()

  override def terminateAgent(arn: Arn): Future[Either[String, Int]] =
    collection
      .deleteMany(equal("arn", arn.value))
      .toFuture()
      .map(deleteResult => Right(deleteResult.getDeletedCount.toInt))
      .recover {
        case e: MongoWriteException => Left(e.getMessage)
      }
}
