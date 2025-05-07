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

package uk.gov.hmrc.agentclientrelationships.connectors

import play.api.mvc.RequestHeader
import play.api.mvc.Results.Status
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.agentclientrelationships.util.HttpApiMonitor
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport._
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AgentAssuranceConnector @Inject() (httpClient: HttpClientV2, val metrics: Metrics)(implicit
  appConfig: AppConfig,
  val ec: ExecutionContext
) extends HttpApiMonitor {

  // TODO: why agent-assurance uses internal-auth.token?
  private def aaHeaders: (String, String) = HeaderNames.authorisation -> appConfig.internalAuthToken

  def getAgentRecordWithChecks(arn: Arn)(implicit rh: RequestHeader): Future[AgentDetailsDesResponse] =
    httpClient
      .get(url"${appConfig.agentAssuranceBaseUrl}/agent-assurance/agent-record-with-checks/arn/${arn.value}")
      .setHeader(aaHeaders)
      .execute[AgentDetailsDesResponse]

  def f()(implicit rh: RequestHeader): Future[Option[AgentDetailsDesResponse]] =
    httpClient
      .get(url"${appConfig.agentAssuranceBaseUrl}/whaver")
      .execute[Option[AgentDetailsDesResponse]] // /status = 2xx body.asJson.as[AgentDetailsDesResponse]

}
