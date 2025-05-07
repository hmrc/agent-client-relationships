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

import play.api.Logging
import play.api.http.Status
import play.api.libs.json.{Format, JsObject, Json, OWrites}
import play.api.mvc.RequestHeader
import sttp.model.Uri.UriContext
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.support.RelationshipNotFound
import uk.gov.hmrc.agentclientrelationships.support.TaxIdentifierSupport._
import uk.gov.hmrc.agentclientrelationships.util.HttpApiMonitor
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport.hc
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HttpResponse, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

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

case class ES19Request(friendlyName: String)
object ES19Request {
  implicit val format: Format[ES19Request] = Json.format[ES19Request]
}
// Note: knownFacts accepts identifier or verifier (key/value object), but we only need by identifier
case class ES20Request(service: String, knownFacts: Seq[Identifier])
object ES20Request {
  implicit val format: Format[ES20Request] = Json.format[ES20Request]
  def forCbcId(cbcId: String): ES20Request = ES20Request("HMRC-CBC-ORG", Seq(Identifier("cbcId", cbcId)))
}

@Singleton
class EnrolmentStoreProxyConnector @Inject() (httpClient: HttpClientV2, val metrics: Metrics, appConfig: AppConfig)(
  implicit val ec: ExecutionContext
)
extends HttpApiMonitor
with Logging {

  val espBaseUrl = appConfig.enrolmentStoreProxyUrl
  val teBaseUrl = appConfig.taxEnrolmentsUrl

  // ES1 - principal
  // TODO: Replace String with a dedicated type for GroupId to improve readability and make the method's purpose clearer
  def getPrincipalGroupIdFor(arn: Arn)(implicit request: RequestHeader): Future[String] = {
    val enrolmentKey = EnrolmentKey(s"HMRC-AS-AGENT~AgentReferenceNumber~${arn.value}")
    monitor(s"ConsumedAPI-ES-getPrincipalGroupIdFor-${enrolmentKey.service}-GET") {
      httpClient
        .get(url"$espBaseUrl/enrolment-store-proxy/enrolment-store/enrolments/$enrolmentKey/groups?type=principal")
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            // TODO: Simplify how our backend responds for certain cases.
            // Don't rely on global HTTP status codes to reflect certain business cases
            // Currently
            // - NO_CONTENT suggest NotFound case
            // - OK with empty groupIds throws exception, this could be represented as None or handled by error handler if this is not expected
            // - OK with more then 1 groupId also is problematic, if there expectation is to have only one groupId
            case Status.NO_CONTENT => throw RelationshipNotFound(s"UNKNOWN_${identifierNickname(arn)}")
            case Status.OK =>
              val groupIds = (response.json \ "principalGroupIds").as[Seq[String]]
              if (groupIds.isEmpty)
                throw RelationshipNotFound(s"UNKNOWN_${identifierNickname(arn)}")
              else {
                if (groupIds.lengthCompare(1) > 0)
                  logger.warn(s"Multiple groupIds found for ${enrolmentKey.service}")
                groupIds.head
              }
            case other => throw UpstreamErrorResponse(response.body, other, other)
          }
        }
    }
  }

  // ES1 - delegated
  def getDelegatedGroupIdsFor(enrolmentKey: EnrolmentKey)(implicit request: RequestHeader): Future[Set[String]] =
    monitor(s"ConsumedAPI-ES-getDelegatedGroupIdsFor-${enrolmentKey.service}-GET") {
      httpClient
        .get(url"$espBaseUrl/enrolment-store-proxy/enrolment-store/enrolments/$enrolmentKey/groups?type=delegated")
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            // TODO: improve error handling as described above and don't use NO_CONTENT for the case of no enrolments
            case Status.OK         => (response.json \ "delegatedGroupIds").as[Seq[String]].toSet
            case Status.NO_CONTENT => Set.empty
            case other             => throw UpstreamErrorResponse(response.body, other, other)
          }
        }
    }

  def getDelegatedGroupIdsForHMCEVATDECORG(vrn: Vrn)(implicit request: RequestHeader): Future[Set[String]] =
    getDelegatedGroupIdsFor(EnrolmentKey("HMCE-VATDEC-ORG", Seq(Identifier("VATRegNo", vrn.value))))

  // ES2 - delegated
  def getEnrolmentsAssignedToUser(userId: String, service: Option[String])(implicit
    request: RequestHeader
  ): Future[Seq[Enrolment]] = {

    val url: URL =
      uri"$espBaseUrl/enrolment-store-proxy/enrolment-store/users/$userId/enrolments?type=delegated"
        .withParam("service", service)
        .toJavaUri
        .toURL

    monitor(s"ConsumedAPI-ES-getEnrolmentsAssignedToUser-GET") {
      httpClient
        .get(url)
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            // TODO: improve error handling as described above and don't use NO_CONTENT for the case of no enrolments
            case Status.OK =>
              response
                .json
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

  // ES3 - Query Enrolments allocated to a Group
  def getAgentReferenceNumberFor(groupId: String)(implicit request: RequestHeader): Future[Option[Arn]] =
    monitor(s"ConsumedAPI-ES-getEnrolmentsForGroupId-$groupId-GET") {
      httpClient
        .get(
          url"$espBaseUrl/enrolment-store-proxy/enrolment-store/groups/$groupId/enrolments?type=principal&service=HMRC-AS-AGENT"
        )
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            // TODO: improve error handling as described above and don't use NO_CONTENT for the case of no enrolments
            case Status.OK =>
              (response.json \ "enrolments")
                .as[Seq[JsObject]]
                .headOption
                .map(obj => (obj \ "identifiers" \ 0 \ "value").as[String])
                .map(Arn.apply)
            case Status.NO_CONTENT => None
            case other             => throw UpstreamErrorResponse(response.body, other, other)
          }
        }
    }

  // ES8
  def allocateEnrolmentToAgent(groupId: String, userId: String, enrolmentKey: EnrolmentKey, agentCode: AgentCode)(
    implicit request: RequestHeader
  ): Future[Unit] = {
    val url =
      url"$teBaseUrl/tax-enrolments/groups/$groupId/enrolments/$enrolmentKey?legacy-agentCode=${agentCode.value}"

    monitor(s"ConsumedAPI-TE-allocateEnrolmentToAgent-${enrolmentKey.service}-POST") {
      httpClient
        .post(url)
        .withBody(Json.toJson(ES8Request(userId, "delegated")))
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case Status.CREATED  => ()
            case Status.CONFLICT =>
              // TODO: it should fail not just log a warning which is mostlikely to be ignored leaving user with the problem alone
              logger.warn(
                s"An attempt to allocate new enrolment for ${enrolmentKey.service} resulted in conflict with an existing one."
              )
              ()
            case other => throw UpstreamErrorResponse(response.body, other, other)
          }
        }
    }
  }

  // ES9
  def deallocateEnrolmentFromAgent(groupId: String, enrolmentKey: EnrolmentKey)(implicit
    request: RequestHeader
  ): Future[Unit] = {
    val url = url"$teBaseUrl/tax-enrolments/groups/$groupId/enrolments/${enrolmentKey.tag}"
    monitor(s"ConsumedAPI-TE-deallocateEnrolmentFromAgent-${enrolmentKey.service}-DELETE") {
      httpClient
        .delete(url)
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case Status.NO_CONTENT => ()
            case other             =>
              // TODO: verify that other 2xx are rally errors, use HttpReadsImplicits to idiomatically handle that
              throw UpstreamErrorResponse(response.body, other, other)
          }
        }
    }
  }

  // ES19 - Update an enrolment's friendly name
  def updateEnrolmentFriendlyName(groupId: String, enrolmentKey: String, friendlyName: String)(implicit
    request: RequestHeader
  ): Future[Unit] =
    monitor(s"ConsumedAPI-ES-updateEnrolmentFriendlyName-PUT") {
      httpClient
        .put(
          url"$espBaseUrl/enrolment-store-proxy/enrolment-store/groups/$groupId/enrolments/$enrolmentKey/friendly_name"
        )
        .withBody(Json.toJson(ES19Request(friendlyName)))
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case Status.NO_CONTENT =>
            case other             =>
              // TODO: verify that other 2xx are rally errors, use HttpReadsImplicits to idiomatically handle that
              throw UpstreamErrorResponse(response.body, other, other)
          }
        }
    }

  // ES20 - query known facts by verifiers or identifiers
  def queryKnownFacts(service: Service, knownFacts: Seq[Identifier])(implicit
    request: RequestHeader
  ): Future[Option[Seq[Identifier]]] =
    monitor("ConsumedAPI-ES-queryKnownFactsByIdentifiersOrVerifiers-POST") {
      httpClient
        .post(url"$espBaseUrl/enrolment-store-proxy/enrolment-store/enrolments")
        .withBody(Json.toJson(ES20Request(service.id, knownFacts)))
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case Status.OK =>
              (response.json \ "enrolments")
                .as[Seq[JsObject]]
                .headOption
                .map(obj => (obj \ "identifiers").as[Seq[Identifier]])
            // TODO: The endpoint shoulud return OK with empty list for that case
            case Status.NO_CONTENT => None
            case other             => throw UpstreamErrorResponse(response.body, other, other)
          }
        }
    }
}
