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

import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime.now
import org.joda.time.DateTimeZone.UTC
import org.joda.time.{DateTime, DateTimeZone}
import play.api.Logger
import play.api.libs.json.Json.format
import play.api.libs.json._
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.api.{CursorProducer, ReadPreference}
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.agentclientrelationships.model.TypeOfEnrolment
import uk.gov.hmrc.agentclientrelationships.repository.DeleteRecord.formats
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.logging.{Authorization, SessionId}
import uk.gov.hmrc.http.{HeaderCarrier, Token}
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

import scala.concurrent.{ExecutionContext, Future}

case class DeleteRecord(
  arn: String,
  clientIdentifier: String,
  clientIdentifierType: String,
  dateTime: DateTime = now(UTC),
  syncToETMPStatus: Option[SyncStatus] = None,
  syncToESStatus: Option[SyncStatus] = None,
  lastRecoveryAttempt: Option[DateTime] = None,
  numberOfAttempts: Int = 0,
  headerCarrier: Option[HeaderCarrier] = None) {
  def actionRequired: Boolean = needToDeleteEtmpRecord || needToDeleteEsRecord

  def needToDeleteEtmpRecord: Boolean = !(syncToETMPStatus.contains(Success) || syncToETMPStatus.contains(InProgress))

  def needToDeleteEsRecord: Boolean = !(syncToESStatus.contains(Success) || syncToESStatus.contains(InProgress))
}

object DeleteRecord {

  implicit val dateReads: Reads[DateTime] = uk.gov.hmrc.mongo.json.ReactiveMongoFormats.dateTimeRead
  implicit val dateWrites: Writes[DateTime] = uk.gov.hmrc.mongo.json.ReactiveMongoFormats.dateTimeWrite

  implicit val hcWrites: OWrites[HeaderCarrier] = new OWrites[HeaderCarrier] {
    override def writes(hc: HeaderCarrier): JsObject =
      JsObject(
        Seq(
          "authorization" -> hc.authorization.map(_.value),
          "sessionId"     -> hc.sessionId.map(_.value),
          "token"         -> hc.token.map(_.value),
          "gaToken"       -> hc.gaToken
        ).collect {
          case (key, Some(value)) => (key, JsString(value))
        })
  }

  import play.api.libs.functional.syntax._

  implicit val reads: Reads[HeaderCarrier] = (
    (JsPath \ "authorization").readNullable[String].map(_.map(Authorization.apply)) and
      (JsPath \ "sessionId").readNullable[String].map(_.map(SessionId.apply)) and
      (JsPath \ "token").readNullable[String].map(_.map(Token.apply)) and
      (JsPath \ "gaToken").readNullable[String]
  )((a, s, t, g) => HeaderCarrier(authorization = a, sessionId = s, token = t, gaToken = g))

  implicit val formats: Format[DeleteRecord] = format[DeleteRecord]
}

trait DeleteRecordRepository {
  def create(record: DeleteRecord)(implicit ec: ExecutionContext): Future[Int]
  def findBy(arn: Arn, identifier: TaxIdentifier)(implicit ec: ExecutionContext): Future[Option[DeleteRecord]]
  def updateEtmpSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus)(
    implicit ec: ExecutionContext): Future[Unit]
  def updateEsSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus)(
    implicit ec: ExecutionContext): Future[Unit]
  def markRecoveryAttempt(arn: Arn, identifier: TaxIdentifier)(implicit ec: ExecutionContext): Future[Unit]
  def remove(arn: Arn, identifier: TaxIdentifier)(implicit ec: ExecutionContext): Future[Int]
  def selectNextToRecover(implicit executionContext: ExecutionContext): Future[Option[DeleteRecord]]
}

@Singleton
class MongoDeleteRecordRepository @Inject()(mongoComponent: ReactiveMongoComponent)
    extends ReactiveRepository[DeleteRecord, BSONObjectID](
      "delete-record",
      mongoComponent.mongoConnector.db,
      formats,
      ReactiveMongoFormats.objectIdFormats)
    with DeleteRecordRepository
    with StrictlyEnsureIndexes[DeleteRecord, BSONObjectID] {

  private def clientIdentifierType(identifier: TaxIdentifier) = TypeOfEnrolment(identifier).identifierKey

  override def indexes: Seq[Index] =
    Seq(
      Index(
        Seq("arn" -> Ascending, "clientIdentifier" -> Ascending, "clientIdentifierType" -> Ascending),
        Some("arnAndAgentReference"),
        unique = true))

  def create(record: DeleteRecord)(implicit ec: ExecutionContext): Future[Int] =
    insert(record).map { result =>
      result.writeErrors.foreach(error => Logger(getClass).warn(s"Creating DeleteRecord failed: ${error.errmsg}"))
      result.n
    }

  def findBy(arn: Arn, identifier: TaxIdentifier)(implicit ec: ExecutionContext): Future[Option[DeleteRecord]] =
    find(
      "arn"                  -> arn.value,
      "clientIdentifier"     -> identifier.value,
      "clientIdentifierType" -> clientIdentifierType(identifier))
      .map(_.headOption)

  def updateEtmpSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus)(
    implicit ec: ExecutionContext): Future[Unit] =
    findAndUpdate(
      query = Json.obj(
        "arn"                  -> arn.value,
        "clientIdentifier"     -> identifier.value,
        "clientIdentifierType" -> clientIdentifierType(identifier)),
      update = Json.obj("$set" -> Json.obj("syncToETMPStatus" -> status.toString)),
      upsert = false
    ).map(
      _.lastError.foreach(error => Logger(getClass).warn(s"Updating ETMP sync status ($status) failed: $error"))
    )

  def updateEsSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus)(
    implicit ec: ExecutionContext): Future[Unit] =
    findAndUpdate(
      query = Json.obj(
        "arn"                  -> arn.value,
        "clientIdentifier"     -> identifier.value,
        "clientIdentifierType" -> clientIdentifierType(identifier)),
      update = Json.obj("$set" -> Json.obj("syncToESStatus" -> status.toString)),
      upsert = false
    ).map(
      _.lastError.foreach(error => Logger(getClass).warn(s"Updating ES sync status ($status) failed: $error"))
    )

  def markRecoveryAttempt(arn: Arn, identifier: TaxIdentifier)(implicit ec: ExecutionContext): Future[Unit] =
    findAndUpdate(
      query = Json.obj(
        "arn"                  -> arn.value,
        "clientIdentifier"     -> identifier.value,
        "clientIdentifierType" -> clientIdentifierType(identifier)),
      update = Json.obj(
        "$set" -> Json.obj(
          "lastRecoveryAttempt" -> ReactiveMongoFormats.dateTimeWrite.writes(DateTime.now(DateTimeZone.UTC))),
        "$inc" -> Json.obj("numberOfAttempts" -> JsNumber(1))
      )
    ).map(
      _.lastError.foreach(error => Logger(getClass).warn(s"Marking recovery attempt failed: $error"))
    )

  def remove(arn: Arn, identifier: TaxIdentifier)(implicit ec: ExecutionContext): Future[Int] =
    remove(
      "arn"                  -> arn.value,
      "clientIdentifier"     -> identifier.value,
      "clientIdentifierType" -> clientIdentifierType(identifier))
      .map(_.n)

  override def selectNextToRecover(implicit ec: ExecutionContext): Future[Option[DeleteRecord]] = {
    import reactivemongo.play.json.ImplicitBSONHandlers.JsObjectDocumentWriter
    collection
      .find(selector = Json.obj(), projection = None)
      .sort(JsObject(Seq("lastRecoveryAttempt" -> JsNumber(1))))
      .cursor[DeleteRecord](ReadPreference.primaryPreferred)(
        domainFormatImplicit,
        implicitly[CursorProducer[DeleteRecord]])
      .headOption
  }

}
