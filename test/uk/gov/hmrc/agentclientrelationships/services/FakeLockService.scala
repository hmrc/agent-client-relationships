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

package uk.gov.hmrc.agentclientrelationships.services

import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class FakeLockService extends RecoveryLockService {
  val locked: mutable.Set[(Arn, MtdItId)] = mutable.Set.empty[(Arn, MtdItId)]

  override def tryLock[T](arn: Arn, mtdItId: MtdItId)(body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
    if (locked.contains((arn, mtdItId))) Future.successful(None)
    else {
      locked.add((arn, mtdItId))
      body.map(Some.apply).map { result =>
        locked.remove((arn, mtdItId))
        result
      }
    }

}
