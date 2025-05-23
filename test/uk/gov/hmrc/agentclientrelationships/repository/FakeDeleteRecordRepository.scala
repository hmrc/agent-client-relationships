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

import org.mongodb.scala.MongoException
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus.SyncStatus
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import java.time.Instant
import java.time.ZoneOffset
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FakeDeleteRecordRepository
extends DeleteRecordRepository(FakeMongoComponent.make) {

  override lazy val initialised: Future[Unit] = Future.unit

  private val data: mutable.Map[(Arn, EnrolmentKey), DeleteRecord] = mutable.Map()

  // the provided DeleteCopyRecord must use an enrolment key
  override def create(record: DeleteRecord)(implicit requestHeader: RequestHeader): Future[Int] = findBy(Arn(record.arn), record.enrolmentKey.get).map(result =>
    if (result.isDefined)
      throw new MongoException("duplicate key error collection")
    else {
      data += ((Arn(record.arn), record.enrolmentKey.get) -> record)
      1
    }
  )

  override def findBy(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Option[DeleteRecord]] = {
    val maybeValue: Option[DeleteRecord] = data.get((arn, enrolmentKey))
    Future.successful(
      if (maybeValue.isDefined)
        maybeValue
      else
        None
    )
  }

  override def updateEtmpSyncStatus(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    status: SyncStatus
  )(implicit requestHeader: RequestHeader): Future[Int] = {
    val maybeValue: Option[DeleteRecord] = data.get((arn, enrolmentKey))
    Future.successful(
      if (maybeValue.isDefined) {
        data((arn, enrolmentKey)) = maybeValue.get.copy(syncToETMPStatus = Some(status))
        1
      }
      else
        throw new IllegalArgumentException(s"Unexpected arn and enrolment key $arn, ${enrolmentKey.tag}")
    )

  }

  override def updateEsSyncStatus(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    status: SyncStatus
  )(implicit requestHeader: RequestHeader): Future[Int] = {
    val maybeValue: Option[DeleteRecord] = data.get((arn, enrolmentKey))
    Future.successful(
      if (maybeValue.isDefined) {
        data((arn, enrolmentKey)) = maybeValue.get.copy(syncToESStatus = Some(status))
        1
      }
      else
        throw new IllegalArgumentException(s"Unexpected arn and enrolment key $arn, ${enrolmentKey.tag}")
    )
  }

  override def remove(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Int] = {
    val maybeRemove = data.remove((arn, enrolmentKey))
    if (maybeRemove.isDefined)
      Future.successful(1)
    else
      Future.successful(0)
  }

  override def markRecoveryAttempt(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Unit] = {
    val maybeValue: Option[DeleteRecord] = data.get((arn, enrolmentKey))
    Future.successful(
      if (maybeValue.isDefined)
        data((arn, enrolmentKey)) = maybeValue.get
          .copy(lastRecoveryAttempt = Some(Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime))
      else
        throw new IllegalArgumentException(s"Unexpected arn and enrolment key $arn, ${enrolmentKey.tag}")
    )
  }

  override def selectNextToRecover(): Future[Option[DeleteRecord]] = Future.successful(
    data.toSeq.map(_._2).sortBy(_.lastRecoveryAttempt.map(_.toEpochSecond(ZoneOffset.UTC)).getOrElse(0L)).headOption
  )

  def reset() = data.clear()

  override def terminateAgent(arn: Arn): Future[Either[String, Int]] = {
    val keysToRemove: Seq[(Arn, EnrolmentKey)] = data.keys.filter(_._1 == arn).toSeq
    keysToRemove.foreach(data.remove)
    Future.successful(Right(keysToRemove.size))
  }

}
