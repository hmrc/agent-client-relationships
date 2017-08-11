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

import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus.SyncStatus
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}

import scala.concurrent.{ExecutionContext, Future}

class FakeRelationshipCopyRecordRepository(var record: RelationshipCopyRecord) extends RelationshipCopyRecordRepository {

  override def create(record: RelationshipCopyRecord)(implicit ec: ExecutionContext): Future[Int] =
    Future successful 1

  override def findBy(arn: Arn, mtdItId: MtdItId)(implicit ec: ExecutionContext): Future[Option[RelationshipCopyRecord]] = {
    Future.successful(if (arn.value == record.arn && mtdItId.value == record.clientIdentifier) {
      Some(record)
    } else {
      None
    })
  }

  override def updateEtmpSyncStatus(arn: Arn, mtdItId: MtdItId, status: SyncStatus)(implicit ec: ExecutionContext): Future[Unit] = {
    Future.successful(
      if (arn.value == record.arn && mtdItId.value == record.clientIdentifier) {
        record = record.copy(syncToETMPStatus = Some(status))
      } else {
        throw new IllegalArgumentException(s"Unexpected arn and mtdItId $arn, $mtdItId")
      }
    )
  }

  def updateGgSyncStatus(arn: Arn, mtdItId: MtdItId, status: SyncStatus)(implicit ec: ExecutionContext): Future[Unit] = ???

  def remove(arn: Arn, mtdItId: MtdItId)(implicit ec: ExecutionContext): Future[Int] = ???

}
