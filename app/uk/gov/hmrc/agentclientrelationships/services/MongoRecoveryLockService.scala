/*
 * Copyright 2021 HM Revenue & Customs
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
import javax.inject.{Inject, Singleton}
import org.joda.time
import uk.gov.hmrc.agentclientrelationships.repository.MongoLockRepository
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.lock.LockKeeper

import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[MongoRecoveryLockService])
trait RecoveryLockService {

  def tryLock[T](arn: Arn, identifier: TaxIdentifier)(body: => Future[T])(
    implicit ec: ExecutionContext): Future[Option[T]]
}

@Singleton
class MongoRecoveryLockService @Inject()(lockRepository: MongoLockRepository) extends RecoveryLockService {
  override def tryLock[T](arn: Arn, identifier: TaxIdentifier)(body: => Future[T])(
    implicit ec: ExecutionContext): Future[Option[T]] =
    new LockKeeper {
      override def repo = lockRepository

      override def lockId: String = s"recovery-${arn.value}-${identifier.value}"

      override val forceLockReleaseAfter: time.Duration = time.Duration.standardMinutes(5)
    }.tryLock(body)
}
