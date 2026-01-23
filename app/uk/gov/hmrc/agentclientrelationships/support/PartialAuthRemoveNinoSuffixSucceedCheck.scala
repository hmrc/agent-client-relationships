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
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext

@Singleton
class PartialAuthRemoveNinoSuffixSucceedCheck @Inject() (
  partialAuthRepository: PartialAuthRepository
)(implicit
  ec: ExecutionContext,
  mat: Materializer
)
extends Logging {

  partialAuthRepository
    .countNinoSuffixPresence()
    .foreach { case (withSuffix, withoutSuffix) =>
      logger.warn(
        s"[PartialAuthRemoveNinoSuffixSucceedCheck] NINO suffix counts - " +
          s"withSuffix=$withSuffix, withoutSuffix=$withoutSuffix"
      )
    }
}
