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
import play.api.http.Status.{NOT_FOUND, OK}
import play.api.libs.json._
import uk.gov.hmrc.agentclientrelationships.UriPathEncoding.encodePathSegment
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa.ItsaBusinessDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.{ClientDetailsFailureResponse, ClientDetailsNotFound, ErrorRetrievingClientDetails}
import uk.gov.hmrc.agentclientrelationships.util.HttpAPIMonitor
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{Authorization, HeaderCarrier, HeaderNames, HttpClient, HttpReads, HttpResponse}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.net.URL
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

@Singleton
class IfConnector @Inject() (
  val httpClient: HttpClient,
  val ec: ExecutionContext
)(implicit
  val metrics: Metrics,
  val appConfig: AppConfig
) extends HttpAPIMonitor
    with Logging {

  private val ifBaseUrl = appConfig.ifPlatformBaseUrl

  private val ifAPI1171Token = appConfig.ifAPI1171Token
  private val ifEnv = appConfig.ifEnvironment

  /*
API#1171 Get Business Details (for ITSA customers)
https://confluence.tools.tax.service.gov.uk/display/AG/API+1171+%28API+5%29+-+Get+Business+Details
   * */
  def getNinoFor(mtdId: MtdItId)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[Nino]] = {
    val url = new URL(s"$ifBaseUrl/registration/business-details/mtdId/${encodePathSegment(mtdId.value)}")

    getWithIFHeaders("GetBusinessDetailsByMtdId", url, ifAPI1171Token, ifEnv).map { result =>
      result.status match {
        case OK        => Option((result.json \ "taxPayerDisplayResponse" \ "nino").as[Nino])
        case NOT_FOUND => None
        case other =>
          logger.error(s"Error API#1171 GetBusinessDetailsByMtdIId. $other, ${result.body}")
          None
      }
    }
  }

  /*
    API#1171 Get Business Details (for ITSA customers)
    https://confluence.tools.tax.service.gov.uk/display/AG/API+1171+%28API+5%29+-+Get+Business+Details
   * */
  def getMtdIdFor(nino: Nino)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[MtdItId]] = {
    val url = new URL(s"$ifBaseUrl/registration/business-details/nino/${encodePathSegment(nino.value)}")

    getWithIFHeaders("GetBusinessDetailsByNino", url, ifAPI1171Token, ifEnv).map { result =>
      result.status match {
        case OK        => Option((result.json \ "taxPayerDisplayResponse" \ "mtdId").as[MtdItId])
        case NOT_FOUND => None
        case other =>
          logger.error(s"Error API#1171 GetBusinessDetailsByNino. $other, ${result.body}")
          None
      }
    }
  }

  // API#1171 Get Business Details
  def getItsaBusinessDetails(
    nino: String
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[ClientDetailsFailureResponse, ItsaBusinessDetails]] = {

    val url = new URL(s"$ifBaseUrl/registration/business-details/nino/${encodePathSegment(nino)}")

    getWithIFHeaders("ConsumedAPI-IF-GetBusinessDetails-GET", url, ifAPI1171Token, ifEnv).map { result =>
      result.status match {
        case OK =>
          val optionalData = Try(result.json \ "taxPayerDisplayResponse" \ "businessData").map(_(0))
          optionalData match {
            case Success(businessData) => Right(businessData.as[ItsaBusinessDetails])
            case Failure(_) =>
              logger.warn("Unable to retrieve relevant details as the businessData array was empty")
              Left(ClientDetailsNotFound)
          }
        case NOT_FOUND => Left(ClientDetailsNotFound)
        case status =>
          logger.warn(s"Unexpected error during 'getItsaBusinessDetails', statusCode=$status")
          Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getItsaBusinessDetails'"))
      }
    }

  }

  private val Environment = "Environment"
  private val CorrelationId = "CorrelationId"

  private def isInternalHost(url: URL): Boolean =
    appConfig.internalHostPatterns.exists(_.pattern.matcher(url.getHost).matches())

  private[connectors] def getWithIFHeaders(apiName: String, url: URL, authToken: String, env: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[HttpResponse] = {

    val isInternal = isInternalHost(url)

    monitor(s"ConsumedAPI-IF-$apiName-GET") {
      httpClient.GET(url.toString, Nil, ifHeaders(authToken, env, isInternal))(
        implicitly[HttpReads[HttpResponse]],
        if (isInternal) hc.copy(authorization = Some(Authorization(s"Bearer $authToken"))) else hc,
        ec
      )
    }
  }

  private[connectors] def postWithIFHeaders(apiName: String, url: URL, body: JsValue, authToken: String, env: String)(
    implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[HttpResponse] = {

    val isInternal = isInternalHost(url)

    monitor(s"ConsumedAPI-IF-$apiName-POST") {
      httpClient.POST(url.toString, body, ifHeaders(authToken, env, isInternal))(
        implicitly[Writes[JsValue]],
        implicitly[HttpReads[HttpResponse]],
        if (isInternal) hc.copy(authorization = Some(Authorization(s"Bearer $authToken"))) else hc,
        ec
      )
    }
  }

  /*
   * If the service being called is external (e.g. DES/IF in QA or Prod):
   * headers from HeaderCarrier are removed (except user-agent header).
   * Therefore, required headers must be explicitly set.
   * See https://github.com/hmrc/http-verbs?tab=readme-ov-file#propagation-of-headers
   * */
  def ifHeaders(authToken: String, env: String, isInternalHost: Boolean)(implicit
    hc: HeaderCarrier
  ): Seq[(String, String)] = {

    val additionalHeaders =
      if (isInternalHost) Seq.empty
      else
        Seq(
          HeaderNames.authorisation -> s"Bearer $authToken",
          HeaderNames.xRequestId    -> hc.requestId.map(_.value).getOrElse(UUID.randomUUID().toString)
        ) ++ hc.sessionId.fold(Seq.empty[(String, String)])(x => Seq(HeaderNames.xSessionId -> x.value))
    val commonHeaders = Seq(Environment -> env, CorrelationId -> UUID.randomUUID().toString)
    commonHeaders ++ additionalHeaders
  }

}
