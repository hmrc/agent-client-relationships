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

import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.UriPathEncoding.encodePathSegment
import uk.gov.hmrc.agentmtdidentifiers.model.MtdItId
import uk.gov.hmrc.domain.{Nino, SaAgentReference}
import uk.gov.hmrc.play.http.logging.Authorization
import uk.gov.hmrc.play.http.{HeaderCarrier, HttpGet, HttpReads}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


case class BusinessDetails(nino: Nino)

case class ClientRelationship(agents: Option[Seq[Agent]])

case class Agent(hasAgent: Boolean, agentId: Option[SaAgentReference], agentCeasedDate: Option[String])

object BusinessDetails {
  implicit val reads = Json.reads[BusinessDetails]
}

object ClientRelationship {
  implicit val agentReads = Json.reads[Agent]
  implicit val reads = Json.reads[ClientRelationship]
}

@Singleton
class DesConnector @Inject()(@Named("des-baseUrl") baseUrl: URL,
                             @Named("des.authorizationToken") authorizationToken: String,
                             @Named("des.environment") environment: String,
                             httpGet: HttpGet) {

  def getNinoFor(mtdbsa: MtdItId)(implicit hc: HeaderCarrier): Future[Nino] = {
    val url = new URL(baseUrl, s"/registration/business-details/mtdbsa/${encodePathSegment(mtdbsa.value)}")
    getWithDesHeaders[BusinessDetails](url).map(_.nino)
  }

  def getClientAgentsSaReferences(nino: Nino)(implicit hc: HeaderCarrier): Future[Seq[SaAgentReference]] = {
    val url = new URL(baseUrl, s"/registration/relationship/nino/${encodePathSegment(nino.value)}")
    getWithDesHeaders[ClientRelationship](url).map(_.agents.map(_.collect {
      case Agent(true, Some(agentId), None) => agentId
    }).getOrElse(Seq.empty))
  }

  private def getWithDesHeaders[A: HttpReads](url: URL)(implicit hc: HeaderCarrier): Future[A] = {
    val desHeaderCarrier = hc.copy(
      authorization = Some(Authorization(s"Bearer $authorizationToken")),
      extraHeaders = hc.extraHeaders :+ "Environment" -> environment)
    httpGet.GET[A](url.toString)(implicitly[HttpReads[A]], desHeaderCarrier)
  }
}
