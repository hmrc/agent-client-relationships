package uk.gov.hmrc.agentclientrelationships.connectors

import play.api.http.Status.{CREATED, NOT_FOUND, OK}
import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.util.HttpAPIMonitor
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Service}
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

  def createAfiRelationship(invitation: Invitation, acceptedDate: LocalDateTime)(implicit
    hc: HeaderCarrier
  ): Future[Boolean] = {
    val body = Json.obj("startDate" -> acceptedDate.toString)
    monitor(s"ConsumedAPI-AgentFiRelationship-${invitation.service}-PUT") {
      http
        .put(url"${afiRelationshipUrl(invitation.arn, invitation.service, invitation.clientId)}")
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

  def deleteRelationship(arn: Arn, service: Service, clientId: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Boolean] =
    monitor(s"ConsumedAPI-AgentFiRelationship-${service.id}-DELETE") {
      http
        .delete(url"${afiRelationshipUrl(arn.value, service.id, clientId)}")
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
}
