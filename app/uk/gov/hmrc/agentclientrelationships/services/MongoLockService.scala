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

package uk.gov.hmrc.agentclientrelationships.services

import com.google.inject.ImplementedBy
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.mongo.lock.{LockService, MongoLockRepository}

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[MongoLockServiceImpl])
trait MongoLockService {

  def recoveryLock[T](arn: Arn, enrolmentKey: EnrolmentKey)(body: => Future[T])(implicit
    ec: ExecutionContext
  ): Future[Option[T]]

  def schedulerLock[T](jobName: String)(
    body: => Future[T]
  )(implicit ec: ExecutionContext, appConfig: AppConfig): Future[Option[T]]
}

@Singleton
class MongoLockServiceImpl @Inject() (lockRepository: MongoLockRepository) extends MongoLockService {

  def recoveryLock[T](arn: Arn, enrolmentKey: EnrolmentKey)(
    body: => Future[T]
  )(implicit ec: ExecutionContext): Future[Option[T]] = {
    val recoveryLock =
      LockService(lockRepository, lockId = s"recovery-${arn.value}-${enrolmentKey.tag}", ttl = 5.minutes)
    recoveryLock.withLock(body)
  }

  def schedulerLock[T](
    jobName: String
  )(body: => Future[T])(implicit ec: ExecutionContext, appConfig: AppConfig): Future[Option[T]] = {
    val lockService =
      LockService(lockRepository, lockId = s"scheduler-lock-$jobName", ttl = appConfig.emailSchedulerLockTTL.seconds)
    lockService.withLock(body)
  }
}
