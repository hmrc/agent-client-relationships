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
import play.api.libs.json.JsArray
import uk.gov.hmrc.agentclientrelationships.UriPathEncoding.encodePathSegment
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentKey, SetRelationshipEndedPayload}
import uk.gov.hmrc.agentclientrelationships.util.HttpAPIMonitor
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.net.URL
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AgentClientAuthorisationConnector @Inject() (httpClient: HttpClient)(implicit
  val metrics: Metrics,
  val appConfig: AppConfig,
  val ec: ExecutionContext
) extends HttpAPIMonitor
    with Logging {

  private val acaBaseUrl: URL = new URL(appConfig.agentClientAuthorisationUrl)

  def getPartialAuth(clientId: TaxIdentifier, arn: Arn)(implicit
    hc: HeaderCarrier
  ): Future[List[String]] = {
    val url: URL = new URL(
      acaBaseUrl,
      s"/agent-client-authorisation/agencies/${encodePathSegment(arn.value)}/invitations/sent"
    )

    monitor(s"ConsumedAPI-ACA-getPartialAuthExistsFor-GET") {
      httpClient
        .GET[HttpResponse](
          url = url.toString,
          queryParams = List("status" -> "PartialAuth", "clientId" -> clientId.value)
        )
        .map { response =>
          response.status match {
            case Status.OK =>
              ((response.json \ "_embedded" \ "invitations")
                .as[JsArray]
                .value
                .map(x => (x \ "service").as[String])
                .toList)
            case _ =>
              logger.warn(s"no partialAuth found in ACA")
              List.empty
          }
        }
    }
  }

  def updateAltItsaFor(nino: Nino, service: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Boolean] = {

    val url: URL = new URL(
      acaBaseUrl,
      s"/agent-client-authorisation/alt-itsa/$service/update/${encodePathSegment(nino.value)}"
    )

    monitor(s"ConsumedAPI-ACA-updateAltItsaFor$service-PUT") {
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

  /*
  Updates the invitation record to Deauthorised.
   */
  def setRelationshipEnded(arn: Arn, enrolmentKey: EnrolmentKey, endedBy: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Boolean] = {
    val url: URL = new URL(
      acaBaseUrl,
      "/agent-client-authorisation/invitations/set-relationship-ended"
    )
    val payload = SetRelationshipEndedPayload(
      arn = arn,
      clientId = enrolmentKey.oneIdentifier().value,
      service = enrolmentKey.service,
      endedBy = Some(endedBy)
    )
    monitor(s"ConsumedAPI-ACA-setRelationshipEnded-PUT") {
      httpClient
        .PUT[SetRelationshipEndedPayload, HttpResponse](url = url.toString, payload)
        .map { response =>
          response.status match {
            case Status.NO_CONTENT => true
            case _                 => false
          }
        }
    }

  }

  def updateStatusToAccepted(nino: Nino, service: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Boolean] = {
    val url: URL = new URL(
      acaBaseUrl,
      s"/agent-client-authorisation/agent/alt-itsa/$service/update-status/accepted/${nino.value}"
    )
    monitor(s"ConsumedAPI-ACA-updateStatusToAccepted-PUT") {
      httpClient
        .PUT[String, HttpResponse](url = url.toString, "")
        .map { response =>
          response.status match {
            case Status.NO_CONTENT => true
            case _                 => false
          }
        }
    }

  }

}
