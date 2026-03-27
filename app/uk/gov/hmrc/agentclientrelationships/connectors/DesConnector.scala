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

import play.api.http.Status
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.helpers.CommonHeaders
import uk.gov.hmrc.agentclientrelationships.connectors.helpers.CorrelationIdGenerator
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport._
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.domain.SaAgentReference
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.HeaderNames
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.Retries
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import com.typesafe.config.Config
import org.apache.pekko.actor.ActorSystem

import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Success

@Singleton
class DesConnector @Inject() (
  httpClient: HttpClientV2,
  randomUuidGenerator: CorrelationIdGenerator,
  appConfig: AppConfig,
  val configuration: Config,
  val actorSystem: ActorSystem
)(implicit
  val ec: ExecutionContext
)
extends RequestAwareLogging
with Retries {

  private val desAuthToken = appConfig.desToken
  private val desEnv = appConfig.desEnv

  private val Environment = "Environment"
  private val CorrelationId = "CorrelationId"

  private def retryCondition: PartialFunction[Exception, Boolean] = {
    case e: UpstreamErrorResponse if e.statusCode >= 500 || e.statusCode == 429 => true // Retry on server errors and rate limiting
  }

  def getClientSaAgentSaReferences(nino: NinoWithoutSuffix)(implicit request: RequestHeader): Future[Seq[SaAgentReference]] = {
    val otherSuffixVariants = Nino.validSuffixes :+ ""
    val otherNinoVariants = otherSuffixVariants.map(suffix => NinoWithoutSuffix(nino.value + suffix)).filterNot(_.rawEquals(nino))
    val ninoVariants = nino +: otherNinoVariants

    def execute(nino: NinoWithoutSuffix) = {
      val url = url"${appConfig.desUrl}/registration/relationship/nino/${nino.rawValue}"

      retryFor(s"CESA agent lookup for NINO: $nino")(retryCondition) {
        getWithDesHeaders(url).map { response =>
          response.status match {
            case Status.OK =>
              Some(response.json
                .as[Agents]
                .agents
                .filter(agent => agent.hasAgent && agent.agentCeasedDate.isEmpty) // API returns agent history, so filter to only active agents
                .flatMap(_.agentId))
            case Status.NOT_FOUND if (response.json \ "code").asOpt[String].contains("NOT_FOUND_NINO") => None
            case other => throw UpstreamErrorResponse(response.body, other)
          }
        }
      }
    }

    ninoVariants.foldLeft(Future.successful(Option.empty[Seq[SaAgentReference]])) {
      case (result, nextNino) =>
        result.flatMap {
          case None =>
            execute(nextNino).andThen { case Success(Some(_)) =>
              if (nextNino != nino) {
                logger.warn(s"[getClientSaAgentSaReferences] Found details for NINO '$nino', but not for the original NINO '${nino.anySuffixValue}'")
              }
            }
          case Some(value) => Future.successful(Some(value))
        }
    }.andThen { case Success(None) => logger.warn(s"[getClientSaAgentSaReferences] No details found for any NINO variant of '${nino.anySuffixValue}'") }
      .map(_.getOrElse(Seq.empty))
  }

  def desHeaders(
    authToken: String,
    env: String
  )(implicit requestHeader: RequestHeader): Seq[(String, String)] =
    CommonHeaders() ++ Seq(
      Environment -> env,
      HeaderNames.authorisation -> s"Bearer $authToken",
      CorrelationId -> randomUuidGenerator.makeCorrelationId()
    )

  private def getWithDesHeaders(
    url: URL,
    authToken: String = desAuthToken,
    env: String = desEnv
  )(implicit request: RequestHeader): Future[HttpResponse] = httpClient
    .get(url = url)
    .setHeader(desHeaders(authToken, env): _*)
    .execute[HttpResponse]

}
