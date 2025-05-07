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
import play.api.http.Status
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.util.HttpApiMonitor
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport.hc
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HttpResponse, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AgentPermissionsConnector @Inject() (httpClient: HttpClientV2, appConfig: AppConfig, val metrics: Metrics)(
  implicit val ec: ExecutionContext
) extends HttpApiMonitor
    with Logging {

  def isClientUnassigned(arn: Arn, enrolmentKey: EnrolmentKey)(implicit rh: RequestHeader): Future[Boolean] = {
    val url = url"${appConfig.agentPermissionsUrl}/agent-permissions/arn/${arn.value}/client/${enrolmentKey.tag}/groups"
    monitor("ConsumedAPI-GetGroupSummariesForClient-GET") {
      httpClient
        .get(url)
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case Status.OK => false
            case Status.NOT_FOUND =>
              true // TODO: the endpoint should return empty Seq for such case. Now when NotFound is return, it's not obvious if records are not found or if the url is not defined or permissions are not found
            case e => throw UpstreamErrorResponse(response.body, e)
          }
        }
    }
  }

}
