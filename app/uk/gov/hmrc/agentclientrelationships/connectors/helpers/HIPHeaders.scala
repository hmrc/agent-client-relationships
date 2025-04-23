/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.connectors.helpers

import play.api.http.HeaderNames
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.util.DateTimeHelper

import java.time.{Clock, Instant}
import javax.inject.Inject

class HIPHeaders @Inject() (randomUUIDGenerator: RandomUUIDGenerator, appConfig: AppConfig, clock: Clock) {
  private val correlationIdHeader: String = "correlationid"
  private val xOriginatingSystemHeader: String = "X-Originating-System"
  private val xReceiptDateHeader: String = "X-Receipt-Date"
  private val xTransmittingSystemHeader: String = "X-Transmitting-System"
  private val xMessageType: String = "X-Message-Type"
  private val xRegimeType: String = "X-Regime-Type"

  private val mdtp = "MDTP"
  private val hip = "HIP"
  private val itsa = "ITSA"

  def subscriptionHeaders(): Seq[(String, String)] =
    Seq(
      (HeaderNames.AUTHORIZATION, s"Basic ${appConfig.hipAuthToken}"),
      (correlationIdHeader, randomUUIDGenerator.uuid),
      (xOriginatingSystemHeader, mdtp),
      (xReceiptDateHeader, DateTimeHelper.formatISOInstantSeconds(Instant.now(clock))),
      (xTransmittingSystemHeader, hip)
    )

  def subscriptionBusinessDetailsHeaders(): Seq[(String, String)] =
    Seq(
      (HeaderNames.AUTHORIZATION, s"Basic ${appConfig.hipAuthToken}"),
      (correlationIdHeader, randomUUIDGenerator.uuid),
      (xOriginatingSystemHeader, mdtp),
      (xReceiptDateHeader, DateTimeHelper.formatISOInstantSeconds(Instant.now(clock))),
      (xTransmittingSystemHeader, hip),
      (xMessageType, "TaxpayerDisplay"),
      (xRegimeType, itsa)
    )
}
