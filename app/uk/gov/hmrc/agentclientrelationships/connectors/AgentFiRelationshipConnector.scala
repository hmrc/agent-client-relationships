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

import play.api.http.Status.{CREATED, NOT_FOUND, OK}
import play.api.libs.json.{Json, Reads}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.{ActiveRelationship, InactiveRelationship, IrvRelationship}
import uk.gov.hmrc.agentclientrelationships.util.HttpAPIMonitor
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.time.LocalDateTime
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AgentFiRelationshipConnector @Inject() (appConfig: AppConfig, http: HttpClientV2, val metrics: Metrics)(implicit
  val ec: ExecutionContext
) extends HttpAPIMonitor {

  private def afiRelationshipUrl(arn: String, service: String, clientId: String): String =
    s"${appConfig.agentFiRelationshipBaseUrl}/agent-fi-relationship/relationships" +
      s"/agent/$arn/service/$service/client/$clientId"

  def getRelationship(arn: Arn, service: String, clientId: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[ActiveRelationship]] =
    monitor(s"ConsumedAPI-AgentFiRelationship-$service-GET") {
      http
        .get(url"${afiRelationshipUrl(arn.value, service, clientId)}")
        .execute[HttpResponse]
        .map { response =>
          implicit val reads: Reads[ActiveRelationship] = ActiveRelationship.irvReads
          response.status match {
            case OK        => response.json.as[Seq[ActiveRelationship]].headOption
            case NOT_FOUND => None
            case status =>
              throw UpstreamErrorResponse(
                s"Unexpected status $status received from AFI get active relationship",
                status
              )
          }
        }
    }

  def getInactiveRelationships(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Seq[InactiveRelationship]] =
    monitor(s"ConsumedAPI-AgentFiRelationship-PERSONAL-INCOME-RECORD-GET") {
      http
        .get(url"${appConfig.agentFiRelationshipBaseUrl}/agent-fi-relationship/relationships/inactive")
        .execute[HttpResponse]
        .map { response =>
          implicit val reads: Reads[InactiveRelationship] = InactiveRelationship.irvReads
          response.status match {
            case OK        => response.json.as[Seq[InactiveRelationship]]
            case NOT_FOUND => Nil
            case status =>
              throw UpstreamErrorResponse(
                s"Unexpected status $status received from AFI get inactive relationship",
                status
              )
          }
        }
    }

  def createRelationship(arn: Arn, service: String, clientId: String, acceptedDate: LocalDateTime)(implicit
    hc: HeaderCarrier
  ): Future[Boolean] = {
    val body = Json.obj("startDate" -> acceptedDate.toString)
    monitor(s"ConsumedAPI-AgentFiRelationship-$service-PUT") {
      http
        .put(url"${afiRelationshipUrl(arn.value, service, clientId)}")
        .withBody(body)
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case CREATED => true
            case status =>
              throw UpstreamErrorResponse(s"Unexpected status $status received from AFI create relationship", status)
          }
        }
    }
  }

  def deleteRelationship(arn: Arn, service: String, clientId: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Boolean] =
    monitor(s"ConsumedAPI-AgentFiRelationship-$service-DELETE") {
      http
        .delete(url"${afiRelationshipUrl(arn.value, service, clientId)}")
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case OK        => true
            case NOT_FOUND => false
            case status =>
              throw UpstreamErrorResponse(s"Unexpected status $status received from AFI delete relationship", status)
          }
        }
    }

  def findRelationshipForClient(clientId: String)(implicit hc: HeaderCarrier): Future[Option[IrvRelationship]] =
    monitor(s"ConsumedAPI-AgentFiRelationship-GET") {
      http
        .get(
          url"${appConfig.agentFiRelationshipBaseUrl}/agent-fi-relationship/relationships/service/PERSONAL-INCOME-RECORD/clientId/$clientId"
        )
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case OK        => response.json.as[List[IrvRelationship]].headOption
            case NOT_FOUND => None
            case status =>
              throw UpstreamErrorResponse(
                s"Unexpected status $status received from AFI get active relationship for client",
                status
              )
          }
        }
    }
}
