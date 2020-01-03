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
import org.joda.time.DateTime
import org.joda.time.DateTime.now
import org.joda.time.DateTimeZone.UTC
import play.api.Logger
import play.api.libs.json.Json.format
import play.api.libs.json._
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.{BSONDocument, BSONObjectID}
import reactivemongo.play.json.ImplicitBSONHandlers
import uk.gov.hmrc.agentclientrelationships.model.TypeOfEnrolment
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord.formats
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.mongo.{AtomicUpdate, ReactiveRepository}

import scala.concurrent.{ExecutionContext, Future}

case class RelationshipCopyRecord(
  arn: String,
  clientIdentifier: String,
  clientIdentifierType: String,
  references: Option[Set[RelationshipReference]] = None,
  dateTime: DateTime = now(UTC),
  syncToETMPStatus: Option[SyncStatus] = None,
  syncToESStatus: Option[SyncStatus] = None) {
  def actionRequired: Boolean = needToCreateEtmpRecord || needToCreateEsRecord

  def needToCreateEtmpRecord: Boolean = !syncToETMPStatus.contains(Success)

  def needToCreateEsRecord: Boolean = !(syncToESStatus.contains(Success) || syncToESStatus.contains(InProgress))
}

object RelationshipCopyRecord extends ReactiveMongoFormats {
  implicit val formats: OFormat[RelationshipCopyRecord] = format[RelationshipCopyRecord]
}

trait RelationshipCopyRecordRepository {
  def create(record: RelationshipCopyRecord)(implicit ec: ExecutionContext): Future[Int]
  def findBy(arn: Arn, identifier: TaxIdentifier)(implicit ec: ExecutionContext): Future[Option[RelationshipCopyRecord]]
  def updateEtmpSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus)(
    implicit ec: ExecutionContext): Future[Unit]

  def updateEsSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus)(
    implicit ec: ExecutionContext): Future[Unit]

  def remove(arn: Arn, identifier: TaxIdentifier)(implicit ec: ExecutionContext): Future[Int]
}

@Singleton
class MongoRelationshipCopyRecordRepository @Inject()(mongoComponent: ReactiveMongoComponent)
    extends ReactiveRepository[RelationshipCopyRecord, BSONObjectID](
      "relationship-copy-record",
      mongoComponent.mongoConnector.db,
      formats,
      ReactiveMongoFormats.objectIdFormats)
    with RelationshipCopyRecordRepository
    with StrictlyEnsureIndexes[RelationshipCopyRecord, BSONObjectID] {

  private def clientIdentifierType(identifier: TaxIdentifier) = TypeOfEnrolment(identifier).identifierKey

  import ImplicitBSONHandlers._
  import play.api.libs.json.Json.JsValueWrapper

  override def indexes: Seq[Index] =
    Seq(
      Index(
        Seq("arn" -> Ascending, "clientIdentifier" -> Ascending, "clientIdentifierType" -> Ascending),
        Some("arnAndAgentReference"),
        unique = true))

  def create(record: RelationshipCopyRecord)(implicit ec: ExecutionContext): Future[Int] =
    collection
      .update(ordered = false)
      .one[JsObject, RelationshipCopyRecord](
        JsObject(
          Seq(
            "arn"                  -> JsString(record.arn),
            "clientIdentifier"     -> JsString(record.clientIdentifier),
            "clientIdentifierType" -> JsString(record.clientIdentifierType))),
        record,
        upsert = true
      )
      .map { result =>
        result.writeErrors.foreach(error =>
          Logger(getClass).warn(s"Creating RelationshipCopyRecord failed: ${error.errmsg}"))
        result.n
      }

  def findBy(arn: Arn, identifier: TaxIdentifier)(
    implicit ec: ExecutionContext): Future[Option[RelationshipCopyRecord]] =
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
      update = Json.obj("$set" -> Json.obj("syncToETMPStatus" -> status.toString))
    ).map(_.lastError.foreach(error => Logger(getClass).warn(s"Updating ETMP sync status ($status) failed: $error")))

  def updateEsSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus)(
    implicit ec: ExecutionContext): Future[Unit] =
    findAndUpdate(
      query = Json.obj(
        "arn"                  -> arn.value,
        "clientIdentifier"     -> identifier.value,
        "clientIdentifierType" -> clientIdentifierType(identifier)),
      update = Json.obj("$set" -> Json.obj("syncToESStatus" -> status.toString))
    ).map(_.lastError.foreach(error => Logger(getClass).warn(s"Updating ES sync status ($status) failed: $error")))

  def remove(arn: Arn, identifier: TaxIdentifier)(implicit ec: ExecutionContext): Future[Int] =
    remove(
      "arn"                  -> arn.value,
      "clientIdentifier"     -> identifier.value,
      "clientIdentifierType" -> clientIdentifierType(identifier))
      .map(_.n)

}
