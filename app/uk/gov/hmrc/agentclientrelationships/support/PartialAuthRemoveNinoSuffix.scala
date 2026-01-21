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

package uk.gov.hmrc.agentclientrelationships.support

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.services.MongoLockService

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

@Singleton
class PartialAuthRemoveNinoSuffix @Inject() (
  partialAuthRepository: PartialAuthRepository,
  mongoLockService: MongoLockService
)(implicit
  ec: ExecutionContext,
  mat: Materializer,
  appConfig: AppConfig
)
extends Logging {

  if (appConfig.removeNinoSuffixEnabled) {
    mongoLockService.partialAuthLock("PartialAuthRemoveNinoSuffix") {
      logger.info("[PartialAuthRemoveNinoSuffix] Remove nino suffix job is running")
      Source
        .fromPublisher(partialAuthRepository.findActiveWithNinoSuffix)
        .throttle(10, 1.second)
        .runForeach { aggregationResult =>
          logger.info(s"update record: ${aggregationResult.nino}")
        }
    }
    ()
  }
  else {
    logger.info("[PartialAuthRemoveNinoSuffix] removeNinoSuffixEnabled is disabled")
  }

}
