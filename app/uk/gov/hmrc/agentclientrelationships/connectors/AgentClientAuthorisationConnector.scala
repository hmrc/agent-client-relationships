/*
 * Copyright 2022 HM Revenue & Customs
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
import play.api.libs.json.JsArray
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.agentclientrelationships.UriPathEncoding.encodePathSegment
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse}
import uk.gov.hmrc.http.HttpReads.Implicits._

import java.net.URL
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AgentClientAuthorisationConnector @Inject()(httpClient: HttpClient, metrics: Metrics)(
  implicit val appConfig: AppConfig)
    extends HttpAPIMonitor
    with Logging {

  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  val acaBaseUrl: URL = new URL(appConfig.agentClientAuthorisationUrl)

  def getPartialAuthExistsFor(clientId: TaxIdentifier, arn: Arn, service: String)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Boolean] = {
    val url: URL = new URL(
      acaBaseUrl,
      s"/agent-client-authorisation/agencies/${encodePathSegment(arn.value)}/invitations/sent"
    )

    monitor(s"ConsumedAPI-ACA-getPartialAuthExistsFor-$service-GET") {
      httpClient
        .GET[HttpResponse](
          url = url.toString,
          queryParams = List("status" -> "PartialAuth", "clientId" -> clientId.value, "service" -> service))
        .map { response =>
          response.status match {
            case Status.OK => !(response.json \ "_embedded" \ "invitations").as[JsArray].equals(JsArray.empty)
            case _         => false
          }
        }
    }
  }

  def updateAltItsaFor(nino: Nino)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Boolean] = {

    val url: URL = new URL(
      acaBaseUrl,
      s"/agent-client-authorisation/alt-itsa/update/${encodePathSegment(nino.value)}"
    )

    monitor(s"ConsumedAPI-ACA-updateAltItsaFor-PUT") {
      httpClient
        .PUTString[HttpResponse](url = url.toString, "")
        .map { response =>
          response.status match {
            case Status.CREATED => true
            case _              => false
          }
        }
    }
  }
}
