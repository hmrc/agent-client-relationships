package uk.gov.hmrc.agentclientrelationships.connectors.clientDetails.itsa

import play.api.Logging
import play.api.http.Status.{NOT_FOUND, OK}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa.{CitizenDetails, DesignatoryDetails}
import uk.gov.hmrc.agentclientrelationships.util.HttpAPIMonitor
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CitizenDetailsConnector @Inject() (appConfig: AppConfig, http: HttpClient, val metrics: Metrics)(implicit
  val ec: ExecutionContext
) extends HttpAPIMonitor
    with Logging {

  def getDesignatoryDetails(
    nino: String
  )(implicit
    c: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[ClientDetailsFailureResponse, DesignatoryDetails]] =
    monitor(s"ConsumedAPI-DesignatoryDetails-GET") {
      http
        .GET[HttpResponse](s"${appConfig.citizenDetailsBaseUrl}/citizen-details/$nino/designatory-details")
        .map { response =>
          response.status match {
            case OK        => Right(response.json.as[DesignatoryDetails])
            case NOT_FOUND => Left(ClientDetailsNotFound)
            case status =>
              logger.error(s"Unexpected error during 'getDesignatoryDetails', statusCode=$status")
              Left(UnexpectedErrorRetrievingClientDetails(status, "Unexpected error during 'getDesignatoryDetails'"))
          }
        }
    }

  def getCitizenDetails(nino: String)(implicit
    c: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[ClientDetailsFailureResponse, CitizenDetails]] =
    monitor(s"ConsumedAPI-CitizenDetails-GET") {
      val url = s"${appConfig.citizenDetailsBaseUrl}/citizen-details/nino/$nino"
      http.GET[HttpResponse](url).map { response =>
        response.status match {
          case OK        => Right(response.json.as[CitizenDetails])
          case NOT_FOUND => Left(ClientDetailsNotFound)
          case status =>
            logger.error(s"Unexpected error during 'getCitizenDetails', statusCode=$status")
            Left(UnexpectedErrorRetrievingClientDetails(status, "Unexpected error during 'getCitizenDetails'"))
        }
      }
    }

}
