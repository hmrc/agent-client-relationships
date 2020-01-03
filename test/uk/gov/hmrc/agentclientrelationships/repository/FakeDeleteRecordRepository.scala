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

import org.joda.time.{DateTime, DateTimeZone}
import reactivemongo.core.errors.GenericDatabaseException
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus.SyncStatus
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.TaxIdentifier

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class FakeDeleteRecordRepository extends DeleteRecordRepository {
  private val data: mutable.Map[String, DeleteRecord] = mutable.Map()

  override def create(record: DeleteRecord)(implicit ec: ExecutionContext): Future[Int] =
    findBy(Arn(record.arn), MtdItId(record.clientIdentifier)).map(
      result =>
        if (result.isDefined)
          throw GenericDatabaseException("duplicate key error collection", code = Some(2))
        else {
          data += ((record.arn + record.clientIdentifier) → record)
          1
      })

  override def findBy(arn: Arn, identifier: TaxIdentifier)(
    implicit ec: ExecutionContext): Future[Option[DeleteRecord]] = {
    val maybeValue: Option[DeleteRecord] = data.get(arn.value + identifier.value)
    Future.successful(
      if (maybeValue.isDefined)
        maybeValue
      else
        None)
  }

  override def updateEtmpSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus)(
    implicit ec: ExecutionContext): Future[Unit] = {
    val maybeValue: Option[DeleteRecord] = data.get(arn.value + identifier.value)
    Future.successful(
      if (maybeValue.isDefined)
        data(arn.value + identifier.value) = maybeValue.get.copy(syncToETMPStatus = Some(status))
      else
        throw new IllegalArgumentException(s"Unexpected arn and identifier $arn, $identifier"))

  }

  def updateEsSyncStatus(arn: Arn, identifier: TaxIdentifier, status: SyncStatus)(
    implicit ec: ExecutionContext): Future[Unit] = {
    val maybeValue: Option[DeleteRecord] = data.get(arn.value + identifier.value)
    Future.successful(
      if (maybeValue.isDefined)
        data(arn.value + identifier.value) = maybeValue.get.copy(syncToESStatus = Some(status))
      else
        throw new IllegalArgumentException(s"Unexpected arn and identifier $arn, $identifier"))
  }

  def remove(arn: Arn, identifier: TaxIdentifier)(implicit ec: ExecutionContext): Future[Int] = {
    val maybeRemove = data.remove(arn.value + identifier.value)
    if (maybeRemove.isDefined) Future.successful(1)
    else Future.successful(0)
  }

  def markRecoveryAttempt(arn: Arn, identifier: TaxIdentifier)(implicit ec: ExecutionContext): Future[Unit] = {
    val maybeValue: Option[DeleteRecord] = data.get(arn.value + identifier.value)
    Future.successful(
      if (maybeValue.isDefined)
        data(arn.value + identifier.value) =
          maybeValue.get.copy(lastRecoveryAttempt = Some(DateTime.now(DateTimeZone.UTC)))
      else
        throw new IllegalArgumentException(s"Unexpected arn and identifier $arn, $identifier"))
  }

  override def selectNextToRecover(implicit executionContext: ExecutionContext): Future[Option[DeleteRecord]] =
    Future.successful(data.toSeq.map(_._2).sortBy(_.lastRecoveryAttempt.map(_.getMillis).getOrElse(0L)).headOption)

  def reset() =
    data.clear()

}
