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
import play.api.libs.json._
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.util.HttpApiMonitor
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport.hc
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.{AgentCode, SaAgentReference}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse, StringContextOps}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.net.URL
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

case class SaMappings(mappings: Seq[SaMapping])
case class SaMapping(arn: Arn, saAgentReference: SaAgentReference)

object SaMappings {
  implicit val mappingReads: Reads[SaMapping] = Json.reads[SaMapping]
  implicit val reads: Reads[SaMappings] = Json.reads[SaMappings]
}

case class AgentCodeMappings(mappings: Seq[AgentCodeMapping])

case class AgentCodeMapping(arn: Arn, agentCode: AgentCode)

object AgentCodeMappings {
  implicit val mappingReads: Reads[AgentCodeMapping] = Json.reads[AgentCodeMapping]
  implicit val reads: Reads[AgentCodeMappings] = Json.reads[AgentCodeMappings]
}

@Singleton
class MappingConnector @Inject() (httpClient: HttpClientV2)(implicit
  val metrics: Metrics,
  val appConfig: AppConfig,
  val ec: ExecutionContext
) extends HttpApiMonitor
    with Logging {

  def getSaAgentReferencesFor(
    arn: Arn
  )(implicit rh: RequestHeader): Future[Seq[SaAgentReference]] = {
    monitor(s"ConsumedAPI-Digital-Mappings-GET") {
      httpClient
        .get(url"${appConfig.agentMappingUrl}/agent-mapping/mappings/${arn.value}")
        .execute[SaMappings]
        .map(_.mappings.map(_.saAgentReference)) //TODO: this transformation should happen in service layer, connector should return plain SaMappings
    }
  }

  def getAgentCodesFor(arn: Arn)(implicit rh: RequestHeader): Future[Seq[AgentCode]] = {
    monitor(s"ConsumedAPI-Digital-Mappings-GET") {
      httpClient
        .get(url"${appConfig.agentMappingUrl}/agent-mapping/mappings/agentcode/${arn.value}")
        .execute[AgentCodeMappings]
        .map(_.mappings.map(_.agentCode)) //TODO: this transformation should happen in service layer, connector should return plain SaMappings
    }
  }
}
