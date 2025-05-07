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

import cats.data.EitherT
import play.api.http.Status.{CREATED, NOT_FOUND, OK}
import play.api.libs.json.{Json, Reads}
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.stride.ClientRelationship
import uk.gov.hmrc.agentclientrelationships.model.{ActiveRelationship, InactiveRelationship, RelationshipFailureResponse}
import uk.gov.hmrc.agentclientrelationships.util.HttpApiMonitor
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport._
import uk.gov.hmrc.agentmtdidentifiers.model.Arn

import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HttpResponse, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import uk.gov.hmrc.agentclientrelationships.util.HttpReadsImplicits._
import java.net.URL
import java.time.LocalDateTime
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AgentFiRelationshipConnector @Inject() (appConfig: AppConfig, httpClient: HttpClientV2, val metrics: Metrics)(
  implicit val ec: ExecutionContext
)
extends HttpApiMonitor {

  private def afiRelationshipUrl(arn: Arn, service: String, clientId: String): URL =
    url"${appConfig.agentFiRelationshipBaseUrl}/agent-fi-relationship/relationships/agent/${arn.value}/service/$service/client/$clientId"

  def getRelationship(arn: Arn, service: String, clientId: String)(implicit
    rh: RequestHeader
  ): Future[Option[ActiveRelationship]] =
    monitor(s"ConsumedAPI-AgentFiRelationship-$service-GET") {
      implicit val reads: Reads[ActiveRelationship] = ActiveRelationship.irvReads
      httpClient
        .get(afiRelationshipUrl(arn, service, clientId))
        .execute[Option[Seq[ActiveRelationship]]]
        .map(_.flatMap(_.headOption))
    }

  def getInactiveRelationships(implicit rh: RequestHeader): Future[Seq[InactiveRelationship]] =
    monitor(s"ConsumedAPI-AgentFiRelationship-PERSONAL-INCOME-RECORD-GET") {
      implicit val reads: Reads[InactiveRelationship] = InactiveRelationship.irvReads
      httpClient
        .get(url"${appConfig.agentFiRelationshipBaseUrl}/agent-fi-relationship/relationships/inactive")
        // TODO: it would be easier if the underlying endpoint could return emtpy list instead of NOT_FOUND. Easier to implement and no problem when to distinguish between URL not found and records are not there
        .execute[Option[Seq[InactiveRelationship]]]
        .map(_.fold(Seq[InactiveRelationship]())(identity))
    }

  def createRelationship(arn: Arn, service: String, clientId: String, acceptedDate: LocalDateTime)(implicit
    rh: RequestHeader
  ): Future[Unit] = {
    val body = Json.obj("startDate" -> acceptedDate.toString)
    monitor(s"ConsumedAPI-AgentFiRelationship-$service-PUT") {
      httpClient.put(afiRelationshipUrl(arn, service, clientId)).withBody(body).execute[Unit]
    }
  }

  def deleteRelationship(arn: Arn, service: String, clientId: String)(implicit
    rh: RequestHeader
  ): Future[Boolean] // TODO: Verify the boolean is really needed. It seems that NotFound is transformed into false, which is then transformed into NotFound ...
  =
    monitor(s"ConsumedAPI-AgentFiRelationship-$service-DELETE") {
      httpClient
        .delete(afiRelationshipUrl(arn, service, clientId))
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

  def findIrvActiveRelationshipForClient(
    nino: String
  )(implicit rh: RequestHeader): Future[Either[RelationshipFailureResponse, ClientRelationship]] = EitherT
    .fromOptionF(fopt = findIrvRelationshipForClient(nino), ifNone = RelationshipFailureResponse.RelationshipNotFound)
    .value
    .recover { case ex: UpstreamErrorResponse =>
      Left(RelationshipFailureResponse.ErrorRetrievingRelationship(ex.statusCode, ex.getMessage))
    }

  def findIrvRelationshipForClient(clientId: String)(implicit rh: RequestHeader): Future[Option[ClientRelationship]] = {
    implicit val reads: Reads[ClientRelationship] = ClientRelationship.irvReads(IsActive = true)
    monitor(s"ConsumedAPI-AgentFiRelationship-GET") {
      httpClient
        .get(
          url"${appConfig.agentFiRelationshipBaseUrl}/agent-fi-relationship/relationships/service/PERSONAL-INCOME-RECORD/clientId/$clientId"
        )
        .execute[Option[List[ClientRelationship]]]
        .map(_.flatMap(_.headOption))
    }
  }

  def findIrvInactiveRelationshipForClient(implicit
    rh: RequestHeader
  ): Future[Either[RelationshipFailureResponse, Seq[ClientRelationship]]] = {
    implicit val reads: Reads[ClientRelationship] = ClientRelationship.irvReads(IsActive = false)
    monitor(s"ConsumedAPI-AgentInactiveFiRelationship-GET") {
      httpClient
        .get(url"${appConfig.agentFiRelationshipBaseUrl}/agent-fi-relationship/relationships/inactive")
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case OK        => Right(response.json.as[List[ClientRelationship]])
            case NOT_FOUND => Left(RelationshipFailureResponse.RelationshipNotFound)
            case status =>
              Left(
                RelationshipFailureResponse.ErrorRetrievingRelationship(
                  status = status,
                  message = s"Unexpected status $status received from AFI get active relationship for client"
                )
              )
          }
        }
    }
  }

}
