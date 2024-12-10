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

import play.api.http.Status._
import play.api.libs.json.{JsObject, Json}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.util.HttpAPIMonitor
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Service}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HttpClient, _}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.{Inject, Singleton}
import scala.annotation.unused
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PirRelationshipConnector @Inject() (http: HttpClient)(implicit
  val appConfig: AppConfig,
  val metrics: Metrics,
  val ec: ExecutionContext
) extends HttpAPIMonitor {

  val baseUrl = new URL(appConfig.afiBaseUrl)

  val ISO_LOCAL_DATE_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS"

  @unused
  def createRelationship(arn: Arn, service: String, clientId: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Int] =
    monitor(s"ConsumedAPI-Put-TestOnlyRelationship-PUT") {
      val url = craftUrl(createAndDeleteRelationshipUrl(arn, service, clientId))
      val body = Json.obj("startDate" -> LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
      http
        .PUT[JsObject, HttpResponse](url.toString, body)
        .map(_.status)
    }

  def deleteRelationship(arn: Arn, service: Service, clientId: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[Boolean]] =
    monitor(s"ConsumedAPI-Delete-TestOnlyRelationship-DELETE") {
      val url = craftUrl(createAndDeleteRelationshipUrl(arn, service.id, clientId))
      http.DELETE[HttpResponse](url.toString).map { r =>
        r.status match {
          case OK                => Some(true)
          case NOT_FOUND         => Some(false)
          case s if s / 100 == 5 => None
          case _                 => None
        }
      }
    }

  private def createAndDeleteRelationshipUrl(arn: Arn, service: String, clientId: String) =
    s"/agent-fi-relationship/relationships/agent/${arn.value}/service/$service/client/$clientId"

  private def craftUrl(location: String) = new URL(baseUrl, location)

}
