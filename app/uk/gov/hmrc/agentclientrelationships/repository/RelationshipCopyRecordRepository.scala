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

import javax.inject.{Inject, Singleton}

import org.joda.time.DateTime
import org.joda.time.DateTime.now
import org.joda.time.DateTimeZone.UTC
import play.api.Logger
import play.api.libs.json.Json.format
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.{BSONDocument, BSONObjectID}
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentType
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord.formats
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.domain.{SaAgentReference, TaxIdentifier}
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.mongo.{AtomicUpdate, ReactiveRepository}

import scala.concurrent.{ExecutionContext, Future}

object SyncStatus extends Enumeration {
  type SyncStatus = Value
  val InProgress, IncompleteInputParams, Success, Failed = Value

  implicit val formats = Format[SyncStatus](Reads.enumNameReads(SyncStatus), Writes.enumNameWrites)
}

import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._

sealed trait RelationshipReference {
  val value: TaxIdentifier
}

object RelationshipReference {
  case class SaRef(value: SaAgentReference) extends RelationshipReference

  object SaRef {
    implicit val saReads = (__ \ "saAgentReference").read[SaAgentReference].map(SaRef.apply)

    val saWrites: Writes[SaRef] = new Writes[SaRef] {
      override def writes(o: SaRef): JsValue = Json.obj("saAgentReference" -> o.value)
    }
  }

  case class VatRef(value: Vrn) extends RelationshipReference

  object VatRef {
    implicit val vatReads = (__ \ "vrn").read[Vrn].map(VatRef.apply)
    val vatWrites: Writes[VatRef] = new Writes[VatRef] {
      override def writes(o: VatRef): JsValue = Json.obj("vrn" -> o.value)
    }
  }

  implicit val relationshipReferenceReads =
    __.read[SaRef].map(x => x: RelationshipReference) orElse __.read[VatRef].map(x => x: RelationshipReference)

  implicit val relationshipReferenceWrites = Writes[RelationshipReference] {
    case saRef: SaRef => SaRef.saWrites.writes(saRef)
    case vatRef: VatRef => VatRef.vatWrites.writes(vatRef)
  }
}

case class RelationshipCopyRecord(arn: String,
                                  clientIdentifier: String,
                                  clientIdentifierType: String,
                                  references: Option[Set[RelationshipReference]] = None,
                                  dateTime: DateTime = now(UTC),
                                  syncToETMPStatus: Option[SyncStatus] = None,
                                  syncToGGStatus: Option[SyncStatus] = None) {
  def actionRequired: Boolean = needToCreateEtmpRecord || needToCreateGgRecord

  def needToCreateEtmpRecord = !syncToETMPStatus.contains(Success)

  def needToCreateGgRecord = !(syncToGGStatus.contains(Success) || syncToGGStatus.contains(InProgress))
}

object RelationshipCopyRecord extends ReactiveMongoFormats {
  implicit val formats: Format[RelationshipCopyRecord] = format[RelationshipCopyRecord]
}

trait RelationshipCopyRecordRepository {
  def create(record: RelationshipCopyRecord)(implicit ec: ExecutionContext): Future[Int]
  def findBy(arn: Arn, identifier: TaxIdentifier)(implicit ec: ExecutionContext): Future[Option[RelationshipCopyRecord]]
  def updateEtmpSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus)(implicit ec: ExecutionContext): Future[Unit]

  def updateGgSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus)(implicit ec: ExecutionContext): Future[Unit]

  def remove(arn: Arn, mtdItId: MtdItId)(implicit ec: ExecutionContext): Future[Int]
}

@Singleton
class MongoRelationshipCopyRecordRepository @Inject()(mongoComponent: ReactiveMongoComponent) extends
  ReactiveRepository[RelationshipCopyRecord, BSONObjectID]("relationship-copy-record",
    mongoComponent.mongoConnector.db, formats, ReactiveMongoFormats.objectIdFormats)
  with RelationshipCopyRecordRepository
  with AtomicUpdate[RelationshipCopyRecord] {

  private def clientIdentifierType(identifier: TaxIdentifier) = EnrolmentType.enrolmentTypeFor(identifier).identifierKey

  override def indexes = Seq(
    Index(Seq("arn" -> Ascending, "clientIdentifier" -> Ascending, "clientIdentifierType" -> Ascending), Some("arnAndAgentReference"), unique = true)
  )

  def create(record: RelationshipCopyRecord)(implicit ec: ExecutionContext): Future[Int] = {
    insert(record).map { result =>
      result.writeErrors.foreach(error => Logger.warn(s"Creating RelationshipCopyRecord failed: ${error.errmsg}"))
      result.n
    }
  }

  def findBy(arn: Arn, identifier: TaxIdentifier)(implicit ec: ExecutionContext): Future[Option[RelationshipCopyRecord]] = {

    find("arn" -> arn.value, "clientIdentifier" -> identifier.value, "clientIdentifierType" -> clientIdentifierType(identifier))
      .map(_.headOption)
  }

  def updateEtmpSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus)(implicit ec: ExecutionContext): Future[Unit] = {
    atomicUpdate(
      finder = BSONDocument("arn" -> arn.value, "clientIdentifier" -> identifier.value, "clientIdentifierType" -> clientIdentifierType(identifier)),
      modifierBson = BSONDocument("$set" -> BSONDocument("syncToETMPStatus" -> status.toString))
    ).map(_.foreach { update =>
      update.writeResult.errMsg.foreach(error => Logger.warn(s"Updating ETMP sync status ($status) failed: $error"))
    })
  }

  def updateGgSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus)(implicit ec: ExecutionContext): Future[Unit] = {
    atomicUpdate(
      finder = BSONDocument("arn" -> arn.value, "clientIdentifier" -> identifier.value, "clientIdentifierType" -> clientIdentifierType(identifier)),
      modifierBson = BSONDocument("$set" -> BSONDocument("syncToGGStatus" -> status.toString))
    ).map(_.foreach { update =>
      update.writeResult.errMsg.foreach(error => Logger.warn(s"Updating GG sync status ($status) failed: $error"))
    })
  }

  def remove(arn: Arn, mtdItId: MtdItId)(implicit ec: ExecutionContext): Future[Int] = {
    remove("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> clientIdentifierType(mtdItId))
      .map(_.n)
  }

  override def isInsertion(newRecordId: BSONObjectID, oldRecord: RelationshipCopyRecord): Boolean = false
}
