/*
 * Copyright 2025 HM Revenue & Customs
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

import uk.gov.hmrc.mongo.lock.Lock
import uk.gov.hmrc.mongo.lock.MongoLockRepository
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.play.http.logging.Mdc

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import java.time.Instant
import scala.concurrent.duration.Duration

@Singleton
class MongoLockRepositoryWithMdc @Inject() (
  mongoComponent: MongoComponent,
  timestampSupport: TimestampSupport
)(implicit ec: ExecutionContext)
extends MongoLockRepository(mongoComponent, timestampSupport)(ec) {

  override def takeLock(
    lockId: String,
    owner: String,
    ttl: Duration
  ): Future[Option[Lock]] = Mdc.preservingMdc {
    super.takeLock(
      lockId,
      owner,
      ttl
    )
  }

  override def releaseLock(
    lockId: String,
    owner: String
  ): Future[Unit] = Mdc.preservingMdc {
    super.releaseLock(lockId, owner)
  }

  override def disownLock(
    lockId: String,
    owner: String,
    expiry: Option[Instant]
  ): Future[Unit] = Mdc.preservingMdc {
    super.disownLock(
      lockId,
      owner,
      expiry
    )
  }

  override def refreshExpiry(
    lockId: String,
    owner: String,
    ttl: Duration
  ): Future[Boolean] = Mdc.preservingMdc {
    super.refreshExpiry(
      lockId,
      owner,
      ttl
    )
  }

  override def isLocked(
    lockId: String,
    owner: String
  ): Future[Boolean] = Mdc.preservingMdc {
    super.isLocked(lockId, owner)
  }

}
