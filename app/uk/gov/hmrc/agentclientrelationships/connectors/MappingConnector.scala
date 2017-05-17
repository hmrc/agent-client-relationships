/*
 * Copyright 2017 HM Revenue & Customs
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

import play.api.libs.json._
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.SaAgentReference
import uk.gov.hmrc.play.http._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class Mappings(mappings: Seq[Mapping])

case class Mapping(arn: Arn, saAgentReference: SaAgentReference)

object Mappings {
  implicit val mappingReads = Json.reads[Mapping]
  implicit val reads = Json.reads[Mappings]
}

@Singleton
class MappingConnector @Inject()(@Named("agent-mapping-baseUrl") baseUrl: URL, httpGet: HttpGet) {

  def getSaAgentReferencesFor(arn: Arn)(implicit hc: HeaderCarrier): Future[Seq[SaAgentReference]] = {
    val url = new URL(baseUrl, s"/agent-mapping/mappings/${arn.value}")
    httpGet.GET[Mappings](url.toString).map(_.mappings.map(_.saAgentReference))
  }

}
