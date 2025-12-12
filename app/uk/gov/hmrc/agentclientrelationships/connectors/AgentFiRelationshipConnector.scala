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

import org.apache.pekko.Done
import play.api.http.Status.CREATED
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.ActiveRelationship
import uk.gov.hmrc.agentclientrelationships.model.InactiveRelationship
import uk.gov.hmrc.agentclientrelationships.model.RelationshipFailureResponse
import uk.gov.hmrc.agentclientrelationships.model.stride.ClientRelationship
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport._
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw

import java.net.URL
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class AgentFiRelationshipConnector @Inject() (
  appConfig: AppConfig,
  httpClient: HttpClientV2,
  val metrics: Metrics
)(implicit val ec: ExecutionContext) {

  private def afiRelationshipUrl(
    arn: Arn,
    service: String,
    clientId: String
  ): URL = url"${appConfig.agentFiRelationshipBaseUrl}/agent-fi-relationship/relationships/agent/${arn.value}/service/$service/client/$clientId"

  def getRelationship(
    arn: Arn,
    service: String,
    clientId: String
  )(implicit rh: RequestHeader): Future[Option[ActiveRelationship]] = {
    implicit val reads: Reads[ActiveRelationship] = ActiveRelationship.irvReads
    httpClient
      .get(
        afiRelationshipUrl(
          arn,
          service,
          clientId
        )
      )
      .execute[Option[Seq[ActiveRelationship]]]
      .map(_.flatMap(_.headOption))
  }

  def getInactiveRelationships(implicit rh: RequestHeader): Future[Seq[InactiveRelationship]] = {
    implicit val reads: Reads[InactiveRelationship] = InactiveRelationship.irvReads
    httpClient
      .get(url"${appConfig.agentFiRelationshipBaseUrl}/agent-fi-relationship/relationships/inactive")
      .execute[Option[Seq[InactiveRelationship]]]
      .map(_.fold(Seq[InactiveRelationship]())(identity))
  }

  def createRelationship(
    arn: Arn,
    service: String,
    clientId: String,
    acceptedDate: LocalDateTime
  )(implicit rh: RequestHeader): Future[Done] = {
    val body = Json.obj("startDate" -> acceptedDate.toString)
    httpClient
      .put(
        afiRelationshipUrl(
          arn,
          service,
          clientId
        )
      )
      .withBody(body)
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case CREATED => Done
          case status => throw UpstreamErrorResponse(s"Unexpected status $status received from AFI create relationship", status)
        }
      }

  }

  def deleteRelationship(
    arn: Arn,
    service: String,
    clientId: String
  )(implicit
    rh: RequestHeader
  ): Future[Boolean] // TODO: Verify the boolean is really needed. It seems that NotFound is transformed into false, which is then transformed into NotFound ...
  = httpClient
    .delete(
      afiRelationshipUrl(
        arn,
        service,
        clientId
      )
    )
    .execute[HttpResponse]
    .map { response =>
      response.status match {
        case OK => true
        case NOT_FOUND => false
        case status => throw UpstreamErrorResponse(s"Unexpected status $status received from AFI delete relationship", status)
      }
    }

  def findIrvActiveRelationshipForClient(clientId: String)(implicit rh: RequestHeader): Future[Either[RelationshipFailureResponse, Seq[ClientRelationship]]] = {
    implicit val reads: Reads[ClientRelationship] = ClientRelationship.irvReads(IsActive = true)
    httpClient
      .get(url"${appConfig.agentFiRelationshipBaseUrl}/agent-fi-relationship/relationships/service/PERSONAL-INCOME-RECORD/clientId/$clientId")
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case OK => Right(response.json.as[List[ClientRelationship]])
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

  def findIrvInactiveRelationshipForClient(implicit
    rh: RequestHeader
  ): Future[Either[RelationshipFailureResponse, Seq[ClientRelationship]]] = {
    implicit val reads: Reads[ClientRelationship] = ClientRelationship.irvReads(IsActive = false)
    httpClient
      .get(url"${appConfig.agentFiRelationshipBaseUrl}/agent-fi-relationship/relationships/inactive")
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case OK => Right(response.json.as[List[ClientRelationship]])
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
