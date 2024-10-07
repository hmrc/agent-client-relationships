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

package uk.gov.hmrc.agentclientrelationships.connectors.clientDetails.itsa

import play.api.Logging
import play.api.http.Status.{NOT_FOUND, OK}
import uk.gov.hmrc.agentclientrelationships.UriPathEncoding.encodePathSegment
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa.BusinessDetails
import uk.gov.hmrc.agentclientrelationships.util.HttpAPIMonitor
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{Authorization, HeaderCarrier, HeaderNames, HttpClient, HttpReads, HttpResponse}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.net.URL
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class GetBusinessDetailsConnector @Inject() (
  httpClient: HttpClient,
  val ec: ExecutionContext
)(implicit
  val metrics: Metrics,
  val appConfig: AppConfig,
  ec: ExecutionContext
) extends HttpAPIMonitor
    with Logging {

  def getDetails(
    nino: String
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, BusinessDetails]] = {

    val url = new URL(s"${appConfig.ifPlatformBaseUrl}/registration/business-details/nino/${encodePathSegment(nino)}")

    val isInternal = appConfig.internalHostPatterns.exists(_.pattern.matcher(url.getHost).matches())

    val additionalHeaders =
      if (isInternal) Seq.empty
      else
        Seq(
          HeaderNames.authorisation -> s"Bearer ${appConfig.ifAPI1171Token}",
          HeaderNames.xRequestId    -> hc.requestId.map(_.value).getOrElse(UUID.randomUUID().toString)
        ) ++ hc.sessionId.map(sessionId => Seq(HeaderNames.xSessionId -> sessionId)).getOrElse(Seq.empty)

    val commonHeaders = Seq("Environment" -> appConfig.ifEnvironment, "CorrelationId" -> UUID.randomUUID().toString)

    monitor(s"ConsumedAPI-IF-GetBusinessDetails-GET") {
      httpClient
        .GET(url.toString, commonHeaders ++ additionalHeaders)(
          implicitly[HttpReads[HttpResponse]],
          if (isInternal) hc.copy(authorization = Some(Authorization(s"Bearer ${appConfig.ifAPI1171Token}"))) else hc,
          ec
        )
        .map { response =>
          response.status match {
            case OK => Right(response.json.as[BusinessDetails])
            case NOT_FOUND => Left(ClientDetailsNotFound)
            case status =>
              logger.error(s"Unexpected error during 'getItsaBusinessDetails', statusCode=$status")
              Left(UnexpectedErrorRetrievingClientDetails(status, "Unexpected error during 'getItsaBusinessDetails'"))
          }
        }
    }
  }

}
