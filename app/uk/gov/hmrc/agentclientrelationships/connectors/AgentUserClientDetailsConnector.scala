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

import play.api.Logging
import play.api.http.Status.{NOT_FOUND, NO_CONTENT}
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.util.HttpApiMonitor
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport._
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HttpResponse, StringContextOps}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AgentUserClientDetailsConnector @Inject() (httpClient: HttpClientV2, val metrics: Metrics, appConfig: AppConfig)(
  implicit val ec: ExecutionContext
) extends HttpApiMonitor
    with Logging {

  val baseUrl = appConfig.agentUserClientDetailsUrl

  // update the cache in Granular Permissions (returns 404 if no cache currently in use)
  def cacheRefresh(arn: Arn)(implicit requestHeader: RequestHeader): Future[Unit] = {
    val url = url"$baseUrl/agent-user-client-details/arn/${arn.value}/cache-refresh"
    monitor("ConsumedAPI-GranPermsCacheRefresh-GET") {
      httpClient
        .get(url)
        .execute[HttpResponse]
        .map(response =>
          response.status match {
            case NOT_FOUND | NO_CONTENT =>
              () // TODO endpoint should not return NotFound because it's not obvious what is not found
            case other =>
              throw new RuntimeException(s"cache refresh returned status $other")
          }
        )
    }
  }

}
