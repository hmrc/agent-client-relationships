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

import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class FakeLockService
extends MongoLockService {

  val locked: mutable.Set[(Arn, EnrolmentKey)] = mutable.Set.empty[(Arn, EnrolmentKey)]

  override def recoveryLock[T](
    arn: Arn,
    enrolmentKey: EnrolmentKey
  )(body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
    if (locked.contains((arn, enrolmentKey)))
      Future.successful(None)
    else {
      locked.add((arn, enrolmentKey))
      body
        .map(Some.apply)
        .map { result =>
          locked.remove((arn, enrolmentKey))
          result
        }
    }

  override def schedulerLock[T](jobName: String)(body: => Future[T])(implicit
    ec: ExecutionContext,
    appConfig: AppConfig
  ): Future[Option[T]] = body
    .map(Some.apply)
    .map { result =>
      result
    }

}
