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

import reactivemongo.bson.BSONDocument
import reactivemongo.core.errors.DatabaseException
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus.SyncStatus
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class FakeRelationshipCopyRecordRepository() extends RelationshipCopyRecordRepository {
  var data:scala.collection.mutable.Map[String,RelationshipCopyRecord] = mutable.Map.empty[String,RelationshipCopyRecord]
  override def create(record: RelationshipCopyRecord)(implicit ec: ExecutionContext): Future[Int] = {
    findBy(Arn(record.arn),MtdItId(record.clientIdentifier)).map(result => {
      if(result.isDefined)throw new DatabaseException {override def code: Option[Int] = Some(2)

        override def originalDocument: Option[BSONDocument] = None

        override def message: String = "duplicate key error collection"
      } else {
        data += ((record.arn + record.clientIdentifier) → record)
        1
      }
    }
    )
  }

  override def findBy(arn: Arn, mtdItId: MtdItId)(implicit ec: ExecutionContext): Future[Option[RelationshipCopyRecord]] = {
    val maybeValue: Option[RelationshipCopyRecord] =data.get(arn.value + mtdItId.value)
    Future.successful(if (maybeValue.isDefined) {
      maybeValue
    } else {
      None
    })
  }

  override def updateEtmpSyncStatus(arn: Arn, mtdItId: MtdItId, status: SyncStatus)(implicit ec: ExecutionContext): Future[Unit] = {
    val maybeValue: Option[RelationshipCopyRecord] =data.get(arn.value + mtdItId.value)
    Future.successful(
      if (maybeValue.isDefined) {
        data(arn.value + mtdItId.value) = maybeValue.get.copy(syncToETMPStatus = Some(status))
      } else {
        throw new IllegalArgumentException(s"Unexpected arn and mtdItId $arn, $mtdItId")
      }
    )

  }

  def updateGgSyncStatus(arn: Arn, mtdItId: MtdItId, status: SyncStatus)(implicit ec: ExecutionContext): Future[Unit] = {
    val maybeValue: Option[RelationshipCopyRecord] =data.get(arn.value + mtdItId.value)
    Future.successful(
      if (maybeValue.isDefined) {
        data(arn.value + mtdItId.value) = maybeValue.get.copy(syncToGGStatus = Some(status))
      }  else {
        throw new IllegalArgumentException(s"Unexpected arn and mtdItId $arn, $mtdItId")
      }
    )
  }

  def remove(arn: Arn, mtdItId: MtdItId)(implicit ec: ExecutionContext): Future[Int] = {
    val maybeRemove = data.remove(arn.value + mtdItId.value)
    if(maybeRemove.isDefined)  Future.successful(1)
    else Future.successful(0)
  }

  def reset = {
    data.clear()
  }
}
