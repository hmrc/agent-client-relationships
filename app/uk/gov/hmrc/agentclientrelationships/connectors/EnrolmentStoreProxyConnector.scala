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
import com.kenshoo.play.metrics.Metrics
import play.api.Logging
import play.api.http.Status
import play.api.libs.json.{Format, JsObject, Json, OWrites}
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.support.RelationshipNotFound
import uk.gov.hmrc.agentclientrelationships.support.TaxIdentifierSupport._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Enrolment, Identifier, Service, Vrn}
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse, UpstreamErrorResponse}

import java.net.URL
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

case class ES8Request(userId: String, `type`: String)
object ES8Request {
  implicit val writes: OWrites[ES8Request] = Json.writes[ES8Request]
}

case class ES2Response(enrolments: Seq[Enrolment])
object ES2Response {
  implicit val format: Format[ES2Response] = Json.format[ES2Response]
}

// Note: knownFacts accepts identifier or verifier (key/value object), but we only need by identifier
case class ES20Request(service: String, knownFacts: Seq[Identifier])
object ES20Request {
  implicit val format: Format[ES20Request] = Json.format[ES20Request]
  def forCbcId(cbcId: String): ES20Request = ES20Request("HMRC-CBC-ORG", Seq(Identifier("cbcId", cbcId)))
}

@Singleton
class EnrolmentStoreProxyConnector @Inject()(http: HttpClient, metrics: Metrics)(implicit appConfig: AppConfig)
    extends HttpAPIMonitor
    with Logging {
  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  val espBaseUrl = new URL(appConfig.enrolmentStoreProxyUrl)
  val teBaseUrl = new URL(appConfig.taxEnrolmentsUrl)

  // ES1 - principal
  def getPrincipalGroupIdFor(arn: Arn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[String] = {
    val enrolmentKey = EnrolmentKey(s"HMRC-AS-AGENT~AgentReferenceNumber~${arn.value}")
    val url =
      new URL(espBaseUrl, s"/enrolment-store-proxy/enrolment-store/enrolments/$enrolmentKey/groups?type=principal")
    monitor(s"ConsumedAPI-ES-getPrincipalGroupIdFor-${enrolmentKey.service}-GET") {
      http.GET[HttpResponse](url.toString).map { response =>
        response.status match {
          case Status.NO_CONTENT =>
            throw RelationshipNotFound(s"UNKNOWN_${identifierNickname(arn)}")
          case Status.OK =>
            val groupIds = (response.json \ "principalGroupIds").as[Seq[String]]
            if (groupIds.isEmpty)
              throw RelationshipNotFound(s"UNKNOWN_${identifierNickname(arn)}")
            else {
              if (groupIds.lengthCompare(1) > 0)
                logger.warn(s"Multiple groupIds found for ${enrolmentKey.service}")
              groupIds.head
            }
          case other =>
            throw UpstreamErrorResponse(response.body, other, other)
        }
      }
    }
  }

  // ES1 - delegated
  def getDelegatedGroupIdsFor(
    enrolmentKey: EnrolmentKey)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Set[String]] = {
    val url =
      new URL(espBaseUrl, s"/enrolment-store-proxy/enrolment-store/enrolments/$enrolmentKey/groups?type=delegated")
    monitor(s"ConsumedAPI-ES-getDelegatedGroupIdsFor-${enrolmentKey.service}-GET") {
      http.GET[HttpResponse](url.toString).map { response =>
        response.status match {
          case Status.OK         => (response.json \ "delegatedGroupIds").as[Seq[String]].toSet
          case Status.NO_CONTENT => Set.empty
          case other =>
            throw UpstreamErrorResponse(response.body, other, other)
        }
      }
    }
  }

  def getDelegatedGroupIdsForHMCEVATDECORG(
    vrn: Vrn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Set[String]] =
    getDelegatedGroupIdsFor(EnrolmentKey("HMCE-VATDEC-ORG", Seq(Identifier("VATRegNo", vrn.value))))

  //ES2 - delegated
  def getEnrolmentsAssignedToUser(userId: String, service: Option[String])(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Seq[Enrolment]] = {

    val url: String =
      s"$espBaseUrl/enrolment-store-proxy/enrolment-store/users/$userId/enrolments?type=delegated" + service.fold("")(
        svc => s"&service=$svc")
    monitor(s"ConsumedAPI-ES-getEnrolmentsAssignedToUser-GET") {
      http.GET[HttpResponse](url).map { response =>
        response.status match {
          case Status.OK =>
            response.json
              .as[ES2Response]
              .enrolments
              .filter(e => e.state.toLowerCase == "activated" || e.state.toLowerCase == "unknown")
          // Note: Checking for activation may be redundant as EACD API documentation claims:
          // "A delegated enrolment is always activated, it can never be non-activated."
          case Status.NO_CONTENT => Seq.empty
          case other             => throw UpstreamErrorResponse(response.body, other, other)
        }
      }
    }
  }

  //ES3 - Query Enrolments allocated to a Group
  def getAgentReferenceNumberFor(
    groupId: String)(implicit hc: HeaderCarrier, executionContext: ExecutionContext): Future[Option[Arn]] = {
    val url =
      new URL(
        espBaseUrl,
        s"/enrolment-store-proxy/enrolment-store/groups/$groupId/enrolments?type=principal&service=HMRC-AS-AGENT")
    monitor(s"ConsumedAPI-ES-getEnrolmentsForGroupId-$groupId-GET") {
      http.GET[HttpResponse](url.toString).map { response =>
        response.status match {
          case Status.OK =>
            (response.json \ "enrolments")
              .as[Seq[JsObject]]
              .headOption
              .map(obj => (obj \ "identifiers" \ 0 \ "value").as[String])
              .map(Arn.apply)
          case Status.NO_CONTENT => None
          case other =>
            throw UpstreamErrorResponse(response.body, other, other)
        }
      }
    }
  }

  // ES8
  def allocateEnrolmentToAgent(groupId: String, userId: String, enrolmentKey: EnrolmentKey, agentCode: AgentCode)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Unit] = {
    val url = new URL(
      teBaseUrl,
      s"/tax-enrolments/groups/$groupId/enrolments/$enrolmentKey?legacy-agentCode=${agentCode.value}")
    monitor(s"ConsumedAPI-TE-allocateEnrolmentToAgent-${enrolmentKey.service}-POST") {
      http.POST[ES8Request, HttpResponse](url.toString, ES8Request(userId, "delegated")).map { response =>
        response.status match {
          case Status.CREATED => ()
          case Status.CONFLICT =>
            logger.warn(
              s"An attempt to allocate new enrolment for ${enrolmentKey.service} resulted in conflict with an existing one.")
            ()
          case other =>
            throw UpstreamErrorResponse(response.body, other, other)
        }
      }
    }
  }

  // ES9
  def deallocateEnrolmentFromAgent(groupId: String, enrolmentKey: EnrolmentKey)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Unit] = {
    val url = new URL(teBaseUrl, s"/tax-enrolments/groups/$groupId/enrolments/${enrolmentKey.tag}")
    monitor(s"ConsumedAPI-TE-deallocateEnrolmentFromAgent-${enrolmentKey.service}-DELETE") {
      http.DELETE[HttpResponse](url.toString).map { response =>
        response.status match {
          case Status.NO_CONTENT => ()
          case other =>
            throw UpstreamErrorResponse(response.body, other, other)
        }
      }
    }
  }

  // ES20 - query known facts by verifiers or identifiers
  def queryKnownFacts(service: Service, knownFacts: Seq[Identifier])(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Option[Seq[Identifier]]] = {
    val url = new URL(espBaseUrl, s"/enrolment-store-proxy/enrolment-store/enrolments")
    val request = ES20Request(service.id, knownFacts)
    monitor("ConsumedAPI-ES-queryKnownFactsByIdentifiersOrVerifiers-POST") {
      http.POST[ES20Request, HttpResponse](url.toString, request).map { response =>
        response.status match {
          case Status.OK =>
            (response.json \ "enrolments")
              .as[Seq[JsObject]]
              .headOption
              .map(obj => (obj \ "identifiers").as[Seq[Identifier]])
          case Status.NO_CONTENT => None
          case other =>
            throw UpstreamErrorResponse(response.body, other, other)
        }
      }
    }
  }
}
