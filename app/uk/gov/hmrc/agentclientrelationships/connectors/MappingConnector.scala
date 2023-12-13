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

import com.codahale.metrics.MetricRegistry
import play.api.Logging
import play.api.http.Status
import play.api.libs.json._
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.{AgentCode, SaAgentReference}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse}

import java.net.URL
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

case class SaMappings(mappings: Seq[SaMapping])
case class SaMapping(arn: Arn, saAgentReference: SaAgentReference)

object SaMappings {
  implicit val mappingReads = Json.reads[SaMapping]
  implicit val reads = Json.reads[SaMappings]
}

case class AgentCodeMappings(mappings: Seq[AgentCodeMapping])

case class AgentCodeMapping(arn: Arn, agentCode: AgentCode)

object AgentCodeMappings {
  implicit val mappingReads = Json.reads[AgentCodeMapping]
  implicit val reads = Json.reads[AgentCodeMappings]
}

@Singleton
class MappingConnector @Inject()(httpClient: HttpClient, override val kenshooRegistry: MetricRegistry)(
  implicit appConfig: AppConfig)
    extends HttpAPIMonitor
    with Logging {

  def getSaAgentReferencesFor(
    arn: Arn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[SaAgentReference]] = {
    val url = new URL(s"${appConfig.agentMappingUrl}/agent-mapping/mappings/${arn.value}")
    monitor(s"ConsumedAPI-Digital-Mappings-GET") {
      httpClient.GET[HttpResponse](url.toString).map { response =>
        response.status match {
          case Status.OK => response.json.as[SaMappings].mappings.map(_.saAgentReference)
          case other =>
            logger.error(s"Error in Digital-Mappings getSaAgentReferences: $other, ${response.body}")
            Seq.empty
        }
      }
    }
  }

  def getAgentCodesFor(arn: Arn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[AgentCode]] = {
    val url = new URL(s"${appConfig.agentMappingUrl}/agent-mapping/mappings/agentcode/${arn.value}")
    monitor(s"ConsumedAPI-Digital-Mappings-GET") {
      httpClient.GET[HttpResponse](url.toString).map { response =>
        response.status match {
          case Status.OK => response.json.as[AgentCodeMappings].mappings.map(_.agentCode)
          case other =>
            logger.error(s"Error in Digital-Mappings getAgentCodes: $other, ${response.body}")
            Seq.empty
        }
      }
    }
  }
}
