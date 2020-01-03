/*
 * Copyright 2020 HM Revenue & Customs
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

import java.net.URL

import javax.inject.{Inject, Named, Singleton}
import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import play.api.libs.json._
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.{AgentCode, SaAgentReference}

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.http.{HeaderCarrier, HttpGet}

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
class MappingConnector @Inject()(@Named("agent-mapping-baseUrl") baseUrl: URL, httpGet: HttpGet, metrics: Metrics)
    extends HttpAPIMonitor {
  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  def getSaAgentReferencesFor(
    arn: Arn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[SaAgentReference]] = {
    val url = new URL(baseUrl, s"/agent-mapping/mappings/${arn.value}")
    monitor(s"ConsumedAPI-Digital-Mappings-GET") { httpGet.GET[SaMappings](url.toString) }
      .map(_.mappings.map(_.saAgentReference))

  }

  def getAgentCodesFor(arn: Arn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[AgentCode]] = {
    val url = new URL(baseUrl, s"/agent-mapping/mappings/agentcode/${arn.value}")
    monitor(s"ConsumedAPI-Digital-Mappings-GET") { httpGet.GET[AgentCodeMappings](url.toString) }
      .map(_.mappings.map(_.agentCode))
  }
}
