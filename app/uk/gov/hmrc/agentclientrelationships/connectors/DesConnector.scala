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
import play.api.libs.json._
import play.utils.UriEncoding
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.agentclientrelationships.UriPathEncoding.encodePathSegment
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.services.AgentCacheProvider
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.{Nino, SaAgentReference, TaxIdentifier}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{Authorization, HeaderCarrier, HeaderNames, HttpClient, HttpReads, HttpResponse}

import java.net.URL
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DesConnector @Inject() (httpClient: HttpClient, metrics: Metrics, agentCacheProvider: AgentCacheProvider)(implicit
  val appConfig: AppConfig
) extends HttpAPIMonitor
    with Logging {
  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  private val desBaseUrl = appConfig.desUrl
  private val desAuthToken = appConfig.desToken
  private val desEnv = appConfig.desEnv

  private val Environment = "Environment"
  private val CorrelationId = "CorrelationId"

  def getClientSaAgentSaReferences(
    nino: Nino
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[SaAgentReference]] = {
    val url = new URL(s"${appConfig.desUrl}/registration/relationship/nino/${encodePathSegment(nino.value)}")

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

  def getAgentRecord(
    agentId: TaxIdentifier
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[AgentRecord]] =
    getWithDesHeaders("GetAgentRecord", new URL(getAgentRecordUrl(agentId))).map { response =>
      response.status match {
        case Status.OK =>
          Option(response.json.as[AgentRecord])
        case status =>
          logger.error(s"Error in GetAgentRecord. $status, ${response.body}")
          None
      }
    }

  private def getAgentRecordUrl(agentId: TaxIdentifier) =
    agentId match {
      case Arn(arn) =>
        val encodedArn = UriEncoding.encodePathSegment(arn, "UTF-8")
        s"$desBaseUrl/registration/personal-details/arn/$encodedArn"
      case Utr(utr) =>
        val encodedUtr = UriEncoding.encodePathSegment(utr, "UTF-8")
        s"$desBaseUrl/registration/personal-details/utr/$encodedUtr"
      case _ =>
        throw new Exception(s"The client identifier $agentId is not supported.")
    }

  // DES API #1363  Get Vat Customer Information
  def vrnIsKnownInEtmp(vrn: Vrn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Boolean] = {
    val url = new URL(s"$desBaseUrl/vat/customer/vrn/${encodePathSegment(vrn.value)}/information")
    getWithDesHeaders("GetVatCustomerInformation", url, desAuthToken, desEnv).map { response =>
      response.status match {
        case Status.OK if response.json.as[JsObject].fields.isEmpty => false
        case Status.OK                                              => true
        case Status.NOT_FOUND                                       => false
        case other: Int =>
          logger.error(s"Error in GetVatCustomerInformation. $other, ${response.body}")
          false
      }
    }
  }

  /*
   * If the service being called is external (e.g. DES/IF in QA or Prod):
   * headers from HeaderCarrier are removed (except user-agent header).
   * Therefore, required headers must be explicitly set.
   * See https://github.com/hmrc/http-verbs?tab=readme-ov-file#propagation-of-headers
   * */

  def desHeaders(authToken: String, env: String, isInternalHost: Boolean)(implicit
    hc: HeaderCarrier
  ): Seq[(String, String)] = {

    val additionalHeaders =
      if (isInternalHost) Seq.empty
      else
        Seq(
          HeaderNames.authorisation -> s"Bearer $authToken",
          HeaderNames.xSessionId    -> hc.sessionId.map(_.value).getOrElse("sessionId not available"),
          HeaderNames.xRequestId    -> hc.requestId.map(_.value).getOrElse(UUID.randomUUID().toString)
        )
    val commonHeaders = Seq(Environment -> env, CorrelationId -> UUID.randomUUID().toString)
    commonHeaders ++ additionalHeaders
  }

  private def getWithDesHeaders(apiName: String, url: URL, authToken: String = desAuthToken, env: String = desEnv)(
    implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[HttpResponse] = {

    val isInternalHost = appConfig.internalHostPatterns.exists(_.pattern.matcher(url.getHost).matches())

    monitor(s"ConsumedAPI-DES-$apiName-GET") {
      httpClient
        .GET(url.toString, Nil, desHeaders(authToken, env, isInternalHost))(
          implicitly[HttpReads[HttpResponse]],
          if (isInternalHost) hc else hc.copy(authorization = Some(Authorization(desAuthToken))),
          ec
        )
    }
  }
}
