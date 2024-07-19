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

package uk.gov.hmrc.agentclientrelationships.connectors

import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import play.api.Logging
import play.api.http.Status.{NOT_FOUND, NO_CONTENT}
import uk.gov.hmrc.agentclientrelationships.util.HttpAPIMonitor
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse}
import uk.gov.hmrc.http.HttpReads.Implicits._

import java.net.URL
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AgentUserClientDetailsConnector @Inject() (http: HttpClient, val ec: ExecutionContext)(implicit
  val metrics: Metrics,
  val appConfig: AppConfig
) extends HttpAPIMonitor
    with Logging {

  val baseUrl = new URL(appConfig.agentUserClientDetailsUrl)

  // update the cache in Granular Permissions (returns 404 if no cache currently in use)
  def cacheRefresh(arn: Arn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Unit] = {
    val url = s"$baseUrl/agent-user-client-details/arn/${arn.value}/cache-refresh"
    monitor("ConsumedAPI-GranPermsCacheRefresh-GET") {
      http
        .GET[HttpResponse](url)
        .map(response =>
          response.status match {
            case NOT_FOUND | NO_CONTENT => ()
            case other                  => logger.warn(s"cache refresh returned status $other")
          }
        )
    }
  }

}
