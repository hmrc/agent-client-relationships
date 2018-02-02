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
import java.time.{LocalDateTime, ZoneOffset}
import javax.inject.{Inject, Named, Singleton}

import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import play.api.libs.json.Reads._
import play.api.libs.json._
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.agentclientrelationships.UriPathEncoding.encodePathSegment
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.domain.{Nino, SaAgentReference}

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.logging.Authorization


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

object ClientRelationship {
  implicit val agentReads = Json.reads[Agent]

  implicit val readClientRelationship =
    (JsPath \ "agents").readNullable[Seq[Agent]]
      .map(optionalAgents => ClientRelationship(optionalAgents.getOrElse(Seq.empty)))
}

case class RegistrationRelationshipResponse(processingDate: String)

object RegistrationRelationshipResponse {
  implicit val reads = Json.reads[RegistrationRelationshipResponse]
}

@Singleton
class DesConnector @Inject()(@Named("des-baseUrl") baseUrl: URL,
                             @Named("des.authorizationToken") authorizationToken: String,
                             @Named("des.environment") environment: String,
                             @Named("stub.test.createUpdateAgentRelationshipRosm.response") stubResponseCreateUpdateAgentRelationshipRosm: Int,
                             httpGet: HttpGet,
                             httpPost: HttpPost,
                             metrics: Metrics)
  extends HttpAPIMonitor {
  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  def getNinoFor(mtdbsa: MtdItId)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Nino] = {
    val url = new URL(baseUrl, s"/registration/business-details/mtdbsa/${encodePathSegment(mtdbsa.value)}")
    getWithDesHeaders[NinoBusinessDetails]("GetRegistrationBusinessDetailsByMtdbsa", url).map(_.nino)
  }

  def getMtdIdFor(nino: Nino)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[MtdItId] = {
    val url = new URL(baseUrl, s"/registration/business-details/nino/${encodePathSegment(nino.value)}")
    getWithDesHeaders[MtdItIdBusinessDetails]("GetRegistrationBusinessDetailsByNino", url).map(_.mtdbsa)
  }

  def getClientSaAgentSaReferences(nino: Nino)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[SaAgentReference]] = {
    val url = new URL(baseUrl, s"/registration/relationship/nino/${encodePathSegment(nino.value)}")
    getWithDesHeaders[ClientRelationship]("GetStatusAgentRelationship", url).map(_.agents
      .filter(agent => agent.hasAgent && agent.agentCeasedDate.isEmpty)
      .flatMap(_.agentId))
  }

  def createAgentRelationship(mtdbsa: MtdItId, arn: Arn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[RegistrationRelationshipResponse] = {
    val url = new URL(baseUrl, s"/registration/relationship")
    postWithDesHeaders[JsValue, RegistrationRelationshipResponse]("CreateAgentRelationship", url, createAgentRelationshipInputJson(mtdbsa.value, arn.value))
  }

  def deleteAgentRelationship(mtdbsa: MtdItId, arn: Arn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[RegistrationRelationshipResponse] = {
    val url = new URL(baseUrl, s"/registration/relationship")
    postWithDesHeaders[JsValue, RegistrationRelationshipResponse]("DeleteAgentRelationship", url, deleteAgentRelationshipInputJson(mtdbsa.value, arn.value))
  }

  def createUpdateAgentRelationshipRosm(vrn: Vrn, arn: Arn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[RegistrationRelationshipResponse] = {
    val requestBody = createUpdateAgentRelationshipRosmInputJson(vrn.value, arn.value)

    stubResponseCreateUpdateAgentRelationshipRosm match {
      case response2xx if (200 until 300) contains(response2xx) => {
        Future.successful(RegistrationRelationshipResponse(LocalDateTime.now().atZone(ZoneOffset.UTC).toString))
      }
      case 400 => Future.failed(new BadRequestException("Stubbed bad request response"))
      case 404 => Future.failed(new NotFoundException("Stubbed not found response"))
      case code => Future.failed(new HttpException("Stubbed failure response", code))
    }
  }

  private def getWithDesHeaders[A: HttpReads](apiName: String, url: URL)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[A] = {
    val desHeaderCarrier = hc.copy(
      authorization = Some(Authorization(s"Bearer $authorizationToken")),
      extraHeaders = hc.extraHeaders :+ "Environment" -> environment)
    monitor(s"ConsumedAPI-DES-$apiName-GET") {
      httpGet.GET[A](url.toString)(implicitly[HttpReads[A]], desHeaderCarrier, ec)
    }
  }

  private def postWithDesHeaders[A: Writes, B: HttpReads](apiName: String, url: URL, body: A)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[B] = {
    val desHeaderCarrier = hc.copy(
      authorization = Some(Authorization(s"Bearer $authorizationToken")),
      extraHeaders = hc.extraHeaders :+ "Environment" -> environment)
    monitor(s"ConsumedAPI-DES-$apiName-POST") {
      httpPost.POST[A, B](url.toString, body)(implicitly[Writes[A]], implicitly[HttpReads[B]], desHeaderCarrier, ec)
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

  private def deleteAgentRelationshipInputJson(refNum: String, agentRefNum: String) = Json.parse(
    s"""{
         "acknowledgmentReference": "${java.util.UUID.randomUUID().toString.replace("-", "").take(32)}",
          "refNumber": "$refNum",
          "agentReferenceNumber": "$agentRefNum",
          "regime": "ITSA",
          "authorisation": {
            "action": "De-Authorise"
          }
     }""")

  private def createUpdateAgentRelationshipRosmInputJson(refNum: String, agentRefNum: String) = Json.parse(
    s"""{
         "acknowledgmentReference": "${java.util.UUID.randomUUID().toString.replace("-", "").take(32)}",
          "refNumber": "$refNum",
          "agentReferenceNumber": "$agentRefNum",
          "regime": "TAVC",
          "authorisation": {
            "action": "Authorise",
            "isExclusiveAgent": true
          }
       }""")
}
