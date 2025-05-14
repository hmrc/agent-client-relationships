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
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import play.api.libs.json._
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.helpers.CorrelationIdGenerator
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientDetailsFailureResponse
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientDetailsNotFound
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ErrorRetrievingClientDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa.ItsaBusinessDetails
import uk.gov.hmrc.agentclientrelationships.util.HttpApiMonitor
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport._
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.Nino
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
import scala.util.Failure
import scala.util.Success
import scala.util.Try

@Singleton
class IfConnector @Inject() (
  httpClient: HttpClientV2,
  randomUuidGenerator: CorrelationIdGenerator,
  appConfig: AppConfig
)(implicit
  val metrics: Metrics,
  val ec: ExecutionContext
)
extends HttpApiMonitor
with Logging {

  private val ifBaseUrl = appConfig.ifPlatformBaseUrl

  private val ifAPI1171Token = appConfig.ifAPI1171Token
  private val ifEnv = appConfig.ifEnvironment

  /*
API#1171 Get Business Details (for ITSA customers)
https://confluence.tools.tax.service.gov.uk/display/AG/API+1171+%28API+5%29+-+Get+Business+Details
   * */
  def getNinoFor(mtdId: MtdItId)(implicit request: RequestHeader): Future[Option[Nino]] = {
    val url = url"$ifBaseUrl/registration/business-details/mtdId/${mtdId.value}"

    getWithIFHeaders(
      "GetBusinessDetailsByMtdId",
      url,
      ifAPI1171Token,
      ifEnv
    ).map { result =>
      result.status match {
        case OK => Option((result.json \ "taxPayerDisplayResponse" \ "nino").as[Nino])
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
  def getMtdIdFor(nino: Nino)(implicit request: RequestHeader): Future[Option[MtdItId]] = {
    val url = url"$ifBaseUrl/registration/business-details/nino/${nino.value}"

    getWithIFHeaders(
      "GetBusinessDetailsByNino",
      url,
      ifAPI1171Token,
      ifEnv
    ).map { result =>
      result.status match {
        case OK => Option((result.json \ "taxPayerDisplayResponse" \ "mtdId").as[MtdItId])
        case NOT_FOUND => None
        case other =>
          logger.error(s"Error API#1171 GetBusinessDetailsByNino. $other, ${result.body}")
          None
      }
    }
  }

  // API#1171 Get Business Details
  def getItsaBusinessDetails(nino: String)(implicit
    request: RequestHeader,
    ec: ExecutionContext
  ): Future[Either[ClientDetailsFailureResponse, ItsaBusinessDetails]] = {

    val url = url"$ifBaseUrl/registration/business-details/nino/$nino"

    getWithIFHeaders(
      "ConsumedAPI-IF-GetBusinessDetails-GET",
      url,
      ifAPI1171Token,
      ifEnv
    ).map { result =>
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

  private def isInternalHost(url: URL): Boolean = appConfig.internalHostPatterns
    .exists(_.pattern.matcher(url.getHost).matches())

  private[connectors] def getWithIFHeaders(
    apiName: String,
    url: URL,
    authToken: String,
    env: String
  )(implicit
    request: RequestHeader,
    ec: ExecutionContext
  ): Future[HttpResponse] =
    monitor(s"ConsumedAPI-IF-$apiName-GET") {
      httpClient.get(url).setHeader(ifHeaders(authToken, env): _*).execute[HttpResponse]
    }

  private[connectors] def postWithIFHeaders(
    apiName: String,
    url: URL,
    body: JsValue,
    authToken: String,
    env: String
  )(implicit
    request: RequestHeader,
    ec: ExecutionContext
  ): Future[HttpResponse] =
    monitor(s"ConsumedAPI-IF-$apiName-POST") {
      httpClient.post(url).withBody(body).setHeader(ifHeaders(authToken, env): _*).execute[HttpResponse]
    }

  def ifHeaders(
    authToken: String,
    env: String
  ): Seq[(String, String)] = Seq(
    Environment -> env,
    CorrelationId -> randomUuidGenerator.makeCorrelationId(),
    HeaderNames.authorisation -> s"Bearer $authToken"
  )

}
