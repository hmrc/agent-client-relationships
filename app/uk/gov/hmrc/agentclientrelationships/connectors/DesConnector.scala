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

import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import play.api.http.Status
import play.api.libs.json._
import play.api.mvc.RequestHeader
import play.utils.UriEncoding
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.helpers.CorrelationIdGenerator
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.util.HttpApiMonitor
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport._
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.domain.SaAgentReference
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.HeaderNames
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class DesConnector @Inject() (
  httpClient: HttpClientV2,
  randomUuidGenerator: CorrelationIdGenerator,
  appConfig: AppConfig
)(implicit
  val metrics: Metrics,
  val ec: ExecutionContext
)
extends HttpApiMonitor
with RequestAwareLogging {

  private val desBaseUrl = appConfig.desUrl
  private val desAuthToken = appConfig.desToken
  private val desEnv = appConfig.desEnv

  private val Environment = "Environment"
  private val CorrelationId = "CorrelationId"

  def getClientSaAgentSaReferences(nino: Nino)(implicit request: RequestHeader): Future[Seq[SaAgentReference]] = {
    val url = url"${appConfig.desUrl}/registration/relationship/nino/${nino.value}"

    getWithDesHeaders("GetStatusAgentRelationship", url).map { response =>
      response.status match {
        case Status.OK =>
          response.json
            .as[Agents]
            .agents
            .filter(agent => agent.hasAgent && agent.agentCeasedDate.isEmpty)
            .flatMap(_.agentId)
        case other =>
          logger.error(s"Error in GetStatusAgentRelationship. $other, ${response.body}")
          Seq.empty
      }
    }
  }

  // DES API #1363  Get Vat Customer Information
  def vrnIsKnownInEtmp(vrn: Vrn)(implicit request: RequestHeader): Future[Boolean] = {
    val url = url"$desBaseUrl/vat/customer/vrn/${vrn.value}/information"
    getWithDesHeaders(
      "GetVatCustomerInformation",
      url,
      desAuthToken,
      desEnv
    ).map { response =>
      response.status match {
        case Status.OK if response.json.as[JsObject].fields.isEmpty => false
        case Status.OK => true
        case Status.NOT_FOUND => false
        case other: Int =>
          logger.error(s"Error in GetVatCustomerInformation. $other, ${response.body}")
          false
      }
    }
  }

  def desHeaders(
    authToken: String,
    env: String
  )(implicit requestHeader: RequestHeader): Seq[(String, String)] = Seq(
    Environment -> env,
    HeaderNames.authorisation -> s"Bearer $authToken",
    CorrelationId -> randomUuidGenerator.makeCorrelationId()
  )

  private def getWithDesHeaders(
    apiName: String,
    url: URL,
    authToken: String = desAuthToken,
    env: String = desEnv
  )(implicit request: RequestHeader): Future[HttpResponse] =
    monitor(s"ConsumedAPI-DES-$apiName-GET") {
      httpClient.get(url = url).setHeader(desHeaders(authToken, env): _*).execute[HttpResponse]

    }

}
