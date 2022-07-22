/*
 * Copyright 2022 HM Revenue & Customs
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
import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics

import javax.inject.{Inject, Singleton}
import play.api.Logging
import play.api.http.Status
import play.api.libs.json.{Format, JsObject, Json}
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.support.{RelationshipNotFound, TaxIdentifierSupport}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Enrolment, Vrn}
import uk.gov.hmrc.domain.{AgentCode, TaxIdentifier}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse, UpstreamErrorResponse}

import scala.concurrent.{ExecutionContext, Future}

case class ES8Request(userId: String, `type`: String)
object ES8Request {
  implicit val writes = Json.writes[ES8Request]
}

case class ES2Response(enrolments: Seq[Enrolment])
object ES2Response {
  implicit val format: Format[ES2Response] = Json.format[ES2Response]
}

@Singleton
class EnrolmentStoreProxyConnector @Inject()(http: HttpClient, metrics: Metrics)(implicit appConfig: AppConfig)
    extends TaxIdentifierSupport
    with HttpAPIMonitor
    with Logging {
  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  val espBaseUrl = new URL(appConfig.enrolmentStoreProxyUrl)
  val teBaseUrl = new URL(appConfig.taxEnrolmentsUrl)

  // ES1 - principal
  def getPrincipalGroupIdFor(
    taxIdentifier: TaxIdentifier)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[String] = {
    val enrolmentKeyPrefix = enrolmentKeyPrefixFor(taxIdentifier)
    val enrolmentKey = enrolmentKeyPrefix + "~" + taxIdentifier.value
    val url =
      new URL(espBaseUrl, s"/enrolment-store-proxy/enrolment-store/enrolments/$enrolmentKey/groups?type=principal")
    monitor(s"ConsumedAPI-ES-getPrincipalGroupIdFor-${enrolmentKeyPrefix.replace("~", "_")}-GET") {
      http.GET[HttpResponse](url.toString).map { response =>
        response.status match {
          case Status.NO_CONTENT => throw RelationshipNotFound(s"UNKNOWN_${identifierNickname(taxIdentifier)}")
          case Status.OK =>
            val groupIds = (response.json \ "principalGroupIds").as[Seq[String]]
            if (groupIds.isEmpty)
              throw RelationshipNotFound(s"UNKNOWN_${identifierNickname(taxIdentifier)}")
            else {
              if (groupIds.lengthCompare(1) > 0)
                logger.warn(s"Multiple groupIds found for $enrolmentKeyPrefix")
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
    taxIdentifier: TaxIdentifier)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Set[String]] = {
    val enrolmentKey = enrolmentKeyPrefixFor(taxIdentifier) + "~" + taxIdentifier.value
    getDelegatedGroupIdsFor(enrolmentKey)
  }

  def getDelegatedGroupIdsForHMCEVATDECORG(
    vrn: Vrn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Set[String]] =
    getDelegatedGroupIdsFor(s"HMCE-VATDEC-ORG~VATRegNo~${vrn.value}")

  protected def getDelegatedGroupIdsFor(
    enrolmentKey: String)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Set[String]] = {
    val url =
      new URL(espBaseUrl, s"/enrolment-store-proxy/enrolment-store/enrolments/$enrolmentKey/groups?type=delegated")
    monitor(s"ConsumedAPI-ES-getDelegatedGroupIdsFor-${enrolmentKey.split("~").take(2).mkString("_")}-GET") {
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
  def allocateEnrolmentToAgent(groupId: String, userId: String, taxIdentifier: TaxIdentifier, agentCode: AgentCode)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Unit] = {
    val enrolmentKeyPrefix = enrolmentKeyPrefixFor(taxIdentifier)
    val enrolmentKey = enrolmentKeyPrefix + "~" + taxIdentifier.value
    val url = new URL(
      teBaseUrl,
      s"/tax-enrolments/groups/$groupId/enrolments/$enrolmentKey?legacy-agentCode=${agentCode.value}")
    monitor(s"ConsumedAPI-TE-allocateEnrolmentToAgent-${enrolmentKeyPrefix.replace("~", "_")}-POST") {
      http.POST[ES8Request, HttpResponse](url.toString, ES8Request(userId, "delegated")).map { response =>
        response.status match {
          case Status.CREATED => ()
          case Status.CONFLICT =>
            logger.warn(
              s"An attempt to allocate new enrolment $enrolmentKeyPrefix resulted in conflict with an existing one.")
            ()
          case other =>
            throw UpstreamErrorResponse(response.body, other, other)
        }
      }
    }
  }

  // ES9
  def deallocateEnrolmentFromAgent(groupId: String, taxIdentifier: TaxIdentifier)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Unit] = {
    val enrolmentKeyPrefix = enrolmentKeyPrefixFor(taxIdentifier)
    val enrolmentKey = enrolmentKeyPrefix + "~" + taxIdentifier.value
    val url = new URL(teBaseUrl, s"/tax-enrolments/groups/$groupId/enrolments/$enrolmentKey")
    monitor(s"ConsumedAPI-TE-deallocateEnrolmentFromAgent-${enrolmentKeyPrefix.replace("~", "_")}-DELETE") {
      http.DELETE[HttpResponse](url.toString).map { response =>
        response.status match {
          case Status.NO_CONTENT => ()
          case other =>
            throw UpstreamErrorResponse(response.body, other, other)
        }
      }
    }
  }
}
