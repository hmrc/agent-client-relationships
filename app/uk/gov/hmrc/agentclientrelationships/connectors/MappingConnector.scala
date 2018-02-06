/*
 * Copyright 2018 HM Revenue & Customs
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
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Vrn}
import uk.gov.hmrc.domain.SaAgentReference
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._

import scala.concurrent.Future
import uk.gov.hmrc.http.{HeaderCarrier, HttpGet}

case class Mappings(mappings: Seq[Mapping])

case class Mapping(arn: Arn, saAgentReference: SaAgentReference)

object Mappings {
  implicit val mappingReads = Json.reads[Mapping]
  implicit val reads = Json.reads[Mappings]
}

case class VatMappings(mappings: Seq[VatMapping])

case class VatMapping(arn: Arn, vrn: Vrn)

object VatMappings {
  implicit val mappingReads = Json.reads[VatMapping]
  implicit val reads = Json.reads[VatMappings]
}

@Singleton
class MappingConnector @Inject()(
                                  @Named("agent-mapping-baseUrl") baseUrl: URL,
                                  httpGet: HttpGet,
                                  metrics: Metrics)
  extends HttpAPIMonitor {
  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  def getSaAgentReferencesFor(arn: Arn)(implicit hc: HeaderCarrier): Future[Seq[SaAgentReference]] = {
    val url = new URL(baseUrl, s"/agent-mapping/mappings/${arn.value}")
    monitor(s"ConsumedAPI-Digital-Mappings-GET") {httpGet.GET[Mappings](url.toString)}
      .map(_.mappings.map(_.saAgentReference))
  }

  def getAgentVrnsFor(arn: Arn)(implicit hc: HeaderCarrier): Future[Seq[Vrn]] = {
    val url = new URL(baseUrl, s"/agent-mapping/mappings/vat/${arn.value}")
    monitor(s"ConsumedAPI-Digital-Mappings-GET") {httpGet.GET[VatMappings](url.toString)}
      .map(_.mappings.map(_.vrn))
  }
}
