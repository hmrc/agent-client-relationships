/*
 * Copyright 2017 HM Revenue & Customs
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
import play.api.Logger
import play.api.libs.json.Json.format
import play.api.libs.json.{Format, Reads, Writes}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.{BSONDocument, BSONObjectID}
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord.formats
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.SaAgentReference
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.mongo.{AtomicUpdate, ReactiveRepository}

import scala.concurrent.{ExecutionContext, Future}

object SyncStatus extends Enumeration {
  type SyncStatus = Value
  val InProgress, IncompleteInputParams, Success, Failed = Value

  implicit val formats = Format[SyncStatus](Reads.enumNameReads(SyncStatus), Writes.enumNameWrites)
}

import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._

case class RelationshipCopyRecord(arn: String,
                                  clientIdentifier: String,
                                  clientIdentifierType: String,
                                  references: Option[Set[SaAgentReference]] = None,
                                  dateTime: DateTime = DateTime.now(),
                                  syncToETMPStatus: Option[SyncStatus] = None,
                                  syncToGGStatus: Option[SyncStatus] = None)

object RelationshipCopyRecord extends ReactiveMongoFormats {
  implicit val formats: Format[RelationshipCopyRecord] = format[RelationshipCopyRecord]
}

@Singleton
class RelationshipCopyRecordRepository @Inject()(mongoComponent: ReactiveMongoComponent) extends
  ReactiveRepository[RelationshipCopyRecord, BSONObjectID]("relationship-copy-record",
    mongoComponent.mongoConnector.db, formats, ReactiveMongoFormats.objectIdFormats) with AtomicUpdate[RelationshipCopyRecord] {

  private val MtdItIdType = "MTDITID"

  override def indexes = Seq(
    Index(Seq("arn" -> Ascending, "clientIdentifier" -> Ascending, "clientIdentifierType" -> Ascending), Some("arnAndAgentReference"), unique = true)
  )

  def create(record: RelationshipCopyRecord)(implicit ec: ExecutionContext): Future[Unit] = {
    insert(record).map { result =>
      result.errmsg.foreach(error => s"Creating RelationshipCopyRecord failed: $error")
    }
  }

  def findBy(arn: Arn, mtdItId: MtdItId)(implicit ec: ExecutionContext): Future[Option[RelationshipCopyRecord]] = {

    find("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> MtdItIdType)
      .map(_.headOption)
  }

  def updateEtmpSyncStatus(arn: Arn, mtdItId: MtdItId, status: SyncStatus)(implicit ec: ExecutionContext): Future[Unit] = {
    atomicUpdate(
      finder = BSONDocument("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> MtdItIdType),
      modifierBson = BSONDocument("$set" -> BSONDocument("syncToETMPStatus" -> status.toString))
    ).map(_.foreach { update =>
      update.writeResult.errMsg.foreach(error => Logger.warn(s"Updating ETMP sync status ($status) failed: $error"))
    })
  }

  def updateGgSyncStatus(arn: Arn, mtdItId: MtdItId, status: SyncStatus)(implicit ec: ExecutionContext): Future[Unit] = {
    atomicUpdate(
      finder = BSONDocument("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> MtdItIdType),
      modifierBson = BSONDocument("$set" -> BSONDocument("syncToGGStatus" -> status.toString))
    ).map(_.foreach { update =>
      update.writeResult.errMsg.foreach(error => Logger.warn(s"Updating GG sync status ($status) failed: $error"))
    })
  }

  override def isInsertion(newRecordId: BSONObjectID, oldRecord: RelationshipCopyRecord): Boolean = false
}