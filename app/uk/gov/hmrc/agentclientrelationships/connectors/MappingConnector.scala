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

import uk.gov.hmrc.agentclientrelationships.util.{ConsumesAPI, RequestAwareLogging}
import play.api.libs.json._
import play.api.mvc.RequestHeader
import play.api.http.Status
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport.hc
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.domain.SaAgentReference
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.client.HttpClientV2

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

case class SaMappings(mappings: Seq[SaMapping])
case class SaMapping(
  arn: Arn,
  saAgentReference: SaAgentReference
)

object SaMappings {

  implicit val mappingReads: Reads[SaMapping] = Json.reads[SaMapping]
  implicit val reads: Reads[SaMappings] = Json.reads[SaMappings]

}

@Singleton
class MappingConnector @Inject() (
  httpClient: HttpClientV2,
  appConfig: AppConfig
)(implicit
  val ec: ExecutionContext
)
extends RequestAwareLogging {

  @ConsumesAPI(apiId = "AM09", service = "agent-mapping")
  def getSaAgentReferencesFor(arn: Arn)(implicit rh: RequestHeader): Future[Seq[SaAgentReference]] = httpClient
    .get(url"${appConfig.agentMappingUrl}/agent-mapping/mappings/sa/${arn.value}")
    .execute[HttpResponse]
    .map { response =>
      // TODO: Fix error handling
      // Currently
      // - Only correctly handles 404 status codes
      // - Incorrectly reports Seq.empty for all other error cases
      response.status match {
        case Status.OK => response.json.as[SaMappings].mappings.map(_.saAgentReference)
        case other =>
          logger.error(s"Error in Digital-Mappings getSaAgentReferences: $other, ${response.body}")
          Seq.empty

      }
    }
}
