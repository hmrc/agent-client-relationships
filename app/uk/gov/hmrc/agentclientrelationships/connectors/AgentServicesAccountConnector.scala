/*
 * Copyright 2026 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.connectors

import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.http.HeaderNames
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport.hc
import uk.gov.hmrc.http.HttpReads.Implicits._

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class AgentServicesAccountConnector @Inject() (httpClient: HttpClientV2)(implicit
  ec: ExecutionContext,
  appConfig: AppConfig
) {

  // agent-services-account uses internal auth to support unauthenticated frontend client journey start requests
  private def acrHeaders: (String, String) = HeaderNames.authorisation -> appConfig.internalAuthToken

  def getAgentRecordWithChecks(arn: Arn)(implicit rh: RequestHeader): Future[AgentDetailsDesResponse] = httpClient
    .get(url"${appConfig.agentServicesAccountBaseUrl}/agent-services-account/agent-record-with-checks/arn/${arn.value}")
    .setHeader(acrHeaders)
    .execute[AgentDetailsDesResponse]

}
