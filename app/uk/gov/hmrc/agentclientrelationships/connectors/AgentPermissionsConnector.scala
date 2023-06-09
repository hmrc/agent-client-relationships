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

import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import play.api.Logging
import play.api.http.Status
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse, UpstreamErrorResponse}

import java.net.URL
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AgentPermissionsConnector @Inject()(http: HttpClient, metrics: Metrics)(implicit appConfig: AppConfig)
    extends HttpAPIMonitor
    with Logging {
  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  val agentPermissionsBaseUrl = new URL(appConfig.agentPermissionsUrl)

  def clientIsUnassigned(arn: Arn, enrolmentKey: EnrolmentKey)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Boolean] = {
    val url = s"$agentPermissionsBaseUrl/agent-permissions/arn/${arn.value}/client/${enrolmentKey.tag}/groups"
    monitor("ConsumedAPI-GetGroupSummariesForClient-GET") {
      http.GET[HttpResponse](url).map { response =>
        response.status match {
          case Status.OK        => false
          case Status.NOT_FOUND => true
          case e                => throw UpstreamErrorResponse(response.body, e)
        }
      }
    }
  }

}
