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

import javax.inject.Inject

class IfHeaders @Inject() (
  randomUuidGenerator: CorrelationIdGenerator,
  appConfig: AppConfig
) {

  private val Environment = "Environment"
  private val CorrelationId = "CorrelationId"

  private val mdtp = "MDTP"
  private val hip = "HIP"

  def makeHeaders(authToken: String): Seq[(String, String)] = Seq(
    Environment -> appConfig.ifEnvironment,
    CorrelationId -> randomUuidGenerator.makeCorrelationId(),
    HeaderNames.AUTHORIZATION -> s"Bearer $authToken"
  )

}
