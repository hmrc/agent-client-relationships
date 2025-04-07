package uk.gov.hmrc.agentclientrelationships.connectors

import play.api.libs.json.{JsValue, Writes}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.util.HttpApiMonitor
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{Authorization, HeaderCarrier, HeaderNames, HttpClient, HttpReads, HttpResponse}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.net.URL
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}


trait IFConnectorCommonDeleteMe extends HttpApiMonitor {

  val httpClient: HttpClient
  val ec: ExecutionContext
  val metrics: Metrics
  val appConfig: AppConfig

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
          HeaderNames.xRequestId -> hc.requestId.map(_.value).getOrElse(UUID.randomUUID().toString)
        ) ++ hc.sessionId.fold(Seq.empty[(String, String)])(x => Seq(HeaderNames.xSessionId -> x.value))

    val commonHeaders = Seq(Environment -> env, CorrelationId -> UUID.randomUUID().toString)
    commonHeaders ++ additionalHeaders
  }
}
