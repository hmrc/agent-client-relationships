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

import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import play.api.libs.json.Reads._
import play.api.libs.json._
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.agentclientrelationships.UriPathEncoding.encodePathSegment
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.{Nino, SaAgentReference}
import uk.gov.hmrc.play.http.logging.Authorization
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._
import uk.gov.hmrc.play.http.{HeaderCarrier, HttpGet, HttpPost, HttpReads}

import scala.concurrent.Future


case class NinoBusinessDetails(nino: Nino)

object NinoBusinessDetails {
  implicit val reads = Json.reads[NinoBusinessDetails]
}

case class MtdItIdBusinessDetails(mtdbsa: MtdItId)

object MtdItIdBusinessDetails {
  implicit val reads = Json.reads[MtdItIdBusinessDetails]
}

case class ClientRelationship(agents: Seq[Agent])

case class Agent(hasAgent: Boolean, agentId: Option[SaAgentReference], agentCeasedDate: Option[String])

case class CreateAgentRelationshipResponse(processingDate: String)

object CreateAgentRelationshipResponse {
  implicit val reads = Json.reads[CreateAgentRelationshipResponse]
}

object ClientRelationship {
  implicit val agentReads = Json.reads[Agent]

  implicit val readClientRelationship =
    (JsPath \ "agents").readNullable[Seq[Agent]]
      .map(optionalAgents => ClientRelationship(optionalAgents.getOrElse(Seq.empty)))
}

@Singleton
class DesConnector @Inject()(@Named("des-baseUrl") baseUrl: URL,
                             @Named("des.authorizationToken") authorizationToken: String,
                             @Named("des.environment") environment: String,
                             httpGet: HttpGet,
                             httpPost: HttpPost,
                             metrics: Metrics)
  extends HttpAPIMonitor {
  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  def getNinoFor(mtdbsa: MtdItId)(implicit hc: HeaderCarrier): Future[Nino] = {
    val url = new URL(baseUrl, s"/registration/business-details/mtdbsa/${ encodePathSegment(mtdbsa.value) }")
    getWithDesHeaders[NinoBusinessDetails]("GetRegistrationBusinessDetailsByMtdbsa", url).map(_.nino)
  }

  def getMtdIdFor(nino: Nino)(implicit hc: HeaderCarrier): Future[MtdItId] = {
    val url = new URL(baseUrl, s"/registration/business-details/nino/${ encodePathSegment(nino.value) }")
    getWithDesHeaders[MtdItIdBusinessDetails]("GetRegistrationBusinessDetailsByNino", url).map(_.mtdbsa)
  }

  def getClientSaAgentSaReferences(nino: Nino)(implicit hc: HeaderCarrier): Future[Seq[SaAgentReference]] = {
    val url = new URL(baseUrl, s"/registration/relationship/nino/${ encodePathSegment(nino.value) }")
    getWithDesHeaders[ClientRelationship]("GetStatusAgentRelationship", url).map(_.agents.flatMap {
      case Agent(true, Some(agentId), None) => Some(agentId)
      case _ => None
    })
  }

  def createAgentRelationship(mtdbsa: MtdItId, arn: Arn)(implicit hc: HeaderCarrier): Future[CreateAgentRelationshipResponse] = {
    val url = new URL(baseUrl, s"/registration/relationship")
    postWithDesHeaders[JsValue,CreateAgentRelationshipResponse]("CreateAgentRelationship", url, createAgentRelationshipInputJson(mtdbsa.value,arn.value))
  }

  private def getWithDesHeaders[A: HttpReads](apiName: String, url: URL)(implicit hc: HeaderCarrier): Future[A] = {
    val desHeaderCarrier = hc.copy(
      authorization = Some(Authorization(s"Bearer $authorizationToken")),
      extraHeaders = hc.extraHeaders :+ "Environment" -> environment)
    monitor(s"ConsumedAPI-DES-$apiName-GET") {
      httpGet.GET[A](url.toString)(implicitly[HttpReads[A]], desHeaderCarrier)
    }
  }

  private def postWithDesHeaders[A: Writes, B: HttpReads](apiName: String, url: URL, body: A)(implicit hc: HeaderCarrier): Future[B] = {
    val desHeaderCarrier = hc.copy(
      authorization = Some(Authorization(s"Bearer $authorizationToken")),
      extraHeaders = hc.extraHeaders :+ "Environment" -> environment)
    monitor(s"ConsumedAPI-DES-$apiName-POST") {
      httpPost.POST[A,B](url.toString, body)(implicitly[Writes[A]], implicitly[HttpReads[B]], desHeaderCarrier)
    }
  }

  private def createAgentRelationshipInputJson(refNum: String, agentRefNum: String) = Json.parse(
    s"""{
         "acknowledgmentReference": "${java.util.UUID.randomUUID().toString.replace("-", "").take(32)}",
          "refNumber": "$refNum",
          "agentReferenceNumber": "$agentRefNum",
          "regime": "ITSA",
          "authorisation": {
            "action": "Authorise",
            "isExclusiveAgent": true
          }
       }""")
}
