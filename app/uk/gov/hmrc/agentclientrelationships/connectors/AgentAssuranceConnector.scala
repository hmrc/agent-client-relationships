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

import play.api.http.Status.OK
import play.api.libs.json.{JsError, JsSuccess, Json}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.ValidateLinkFailureResponse.AgentDetailsJsonError
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.{AgentDetailsDesResponse, ValidateLinkFailureResponse}
import uk.gov.hmrc.agentclientrelationships.util.HttpAPIMonitor
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.net.URL
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AgentAssuranceConnector @Inject() (httpV2: HttpClientV2)(implicit
  val metrics: Metrics,
  appConfig: AppConfig,
  val ec: ExecutionContext
) extends HttpAPIMonitor {

  private lazy val baseUrl = appConfig.agentAssuranceBaseUrl

  import uk.gov.hmrc.http.HttpReads.Implicits._

  def getAgentRecordWithChecks(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[ValidateLinkFailureResponse, AgentDetailsDesResponse]] =
    httpV2
      .get(new URL(s"$baseUrl/agent-assurance/agent-record-with-checks"))
      .execute[HttpResponse]
      .map(response =>
        response.status match {
          case OK =>
            Json.parse(response.body).validate[AgentDetailsDesResponse] match {
              case JsSuccess(agentDetailsDesResponse, _) =>
                Right(agentDetailsDesResponse)
              case JsError(errors) =>
                val errorDetails = JsError.toJson(errors).toString()
                Left(AgentDetailsJsonError(errorDetails))
            }
          case other => throw UpstreamErrorResponse(s"Agent record unavailable: des response code: $other", other)
        }
      )

}
