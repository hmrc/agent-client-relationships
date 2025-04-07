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
import uk.gov.hmrc.agentclientrelationships.UriPathEncoding.encodePathSegment
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.helpers.IfHeaders
import uk.gov.hmrc.agentclientrelationships.util.HttpApiMonitor
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, StringContextOps}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}


/**
 * API#1171 Get Business Details (for ITSA customers)
 * https://confluence.tools.tax.service.gov.uk/display/AG/API+1171+%28API+5%29+-+Get+Business+Details
 */
@Singleton
class GetBusinessDetailsConnector @Inject()(
                                             httpClient: HttpClientV2,
                                             appConfig: AppConfig,
                                             ifHeaders: IfHeaders
                                             )(implicit
                                               val ec: ExecutionContext,
                                                                                                     val metrics: Metrics

) extends HttpApiMonitor
    with Logging {

  private val ifBaseUrl = appConfig.ifPlatformBaseUrl
  private val ifAPI1171Token = appConfig.ifAPI1171Token


  def getNinoFor(mtdId: MtdItId)(implicit hc: HeaderCarrier): Future[Option[Nino]] = monitor(s"ConsumedAPI-IF-GetBusinessDetailsByMtdId"){

    httpClient
      .get(url"$ifBaseUrl/registration/business-details/mtdId/${mtdId.value}")
      .setHeader(ifHeaders.makeHeaders(ifAPI1171Token): _*)
      .execute[HttpResponse]
      .map{ result =>
        result.status match {
        case OK        => Option((result.json \ "taxPayerDisplayResponse" \ "nino").as[Nino])
        case NOT_FOUND => None
        case other =>
          //TODO: error handling missing, now it returns None when there is 5xx and 4xx response from the server
          logger.error(s"Error API#1171 GetBusinessDetailsByMtdIId. $other, ${result.body}")
          None
      }
    }
  }


  def getMtdIdFor(nino: Nino)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[MtdItId]] =  monitor(s"ConsumedAPI-IF-GetBusinessDetailsByNino"){
    httpClient
      .get(url"$ifBaseUrl/registration/business-details/nino/${encodePathSegment(nino.value)}")
      .setHeader(ifHeaders.makeHeaders(ifAPI1171Token): _*)
      .execute[HttpResponse]
      .map { result =>
        result.status match {
        case OK        => Option((result.json \ "taxPayerDisplayResponse" \ "mtdId").as[MtdItId])
        case NOT_FOUND => None
        case other =>
          //TODO: error handling missing, now it returns None when there is 5xx and 4xx response from the server
          logger.error(s"Error API#1171 GetBusinessDetailsByNino. $other, ${result.body}")
          None
      }
    }
  }
}




