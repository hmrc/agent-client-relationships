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
import uk.gov.hmrc.agentclientrelationships.connectors.helpers.CorrelationIdGenerator
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.util.ConsumesAPI
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport._
import uk.gov.hmrc.domain.SaAgentReference
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.HeaderNames
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.client.HttpClientV2

import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class DesConnector @Inject() (
  httpClient: HttpClientV2,
  randomUuidGenerator: CorrelationIdGenerator,
  appConfig: AppConfig
)(implicit
  val ec: ExecutionContext
)
extends RequestAwareLogging {

  private val desAuthToken = appConfig.desToken
  private val desEnv = appConfig.desEnv

  private val Environment = "Environment"
  private val CorrelationId = "CorrelationId"

  @ConsumesAPI(apiId = "DES08", service = "des")
  def getClientSaAgentSaReferences(nino: NinoWithoutSuffix)(implicit request: RequestHeader): Future[Seq[SaAgentReference]] = {
    val url = url"${appConfig.desUrl}/registration/relationship/nino/${nino.anySuffixValue}" // TODO review CESA api behaviour around suffixless NINOs

    getWithDesHeaders(url).map { response =>
      response.status match {
        case Status.OK =>
          response.json
            .as[Agents]
            .agents
            .filter(agent => agent.hasAgent && agent.agentCeasedDate.isEmpty)
            .flatMap(_.agentId)
        case other =>
          logger.error(s"Error in GetStatusAgentRelationship. $other, ${response.body}")
          Seq.empty
      }
    }
  }

  def desHeaders(
    authToken: String,
    env: String
  )(implicit requestHeader: RequestHeader): Seq[(String, String)] = Seq(
    Environment -> env,
    HeaderNames.authorisation -> s"Bearer $authToken",
    CorrelationId -> randomUuidGenerator.makeCorrelationId()
  )

  private def getWithDesHeaders(
    url: URL,
    authToken: String = desAuthToken,
    env: String = desEnv
  )(implicit request: RequestHeader): Future[HttpResponse] = httpClient.get(url = url).setHeader(desHeaders(authToken, env): _*).execute[HttpResponse]

}
