/*
 * Copyright 2021 HM Revenue & Customs
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
import org.joda.time.{DateTimeZone, LocalDate}
import play.api.Logging
import play.api.http.Status
import play.api.libs.json._
import play.utils.UriEncoding
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.agentclientrelationships.UriPathEncoding.encodePathSegment
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.services.AgentCacheProvider
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.{Nino, SaAgentReference, TaxIdentifier}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HttpClient, _}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DesConnector @Inject()(httpClient: HttpClient, metrics: Metrics, agentCacheProvider: AgentCacheProvider)(
  implicit val appConfig: AppConfig)
    extends HttpAPIMonitor
    with Logging {
  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  private val desBaseUrl = appConfig.desUrl
  private val desAuthToken = appConfig.desToken
  private val desEnv = appConfig.desEnv
  private val showInactiveRelationshipsDays = appConfig.inactiveRelationshipShowLastDays

  private val Environment = "Environment"
  private val CorrelationId = "CorrelationId"
  private val headerNames = Seq(Environment, CorrelationId, HeaderNames.authorisation)

  def getNinoFor(mtdbsa: MtdItId)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Nino] = {
    val url = new URL(s"${appConfig.desUrl}/registration/business-details/mtdbsa/${encodePathSegment(mtdbsa.value)}")

    getWithDesHeaders("GetRegistrationBusinessDetailsByMtdbsa", url).map { result =>
      result.status match {
        case Status.OK => (result.json \ "nino").as[Nino]
        case other =>
          throw UpstreamErrorResponse(result.body, other, other)
      }
    }
  }

  def getMtdIdFor(nino: Nino)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[MtdItId] = {
    val url = new URL(s"${appConfig.desUrl}/registration/business-details/nino/${encodePathSegment(nino.value)}")

    getWithDesHeaders("GetRegistrationBusinessDetailsByNino", url).map { result =>
      result.status match {
        case Status.OK => (result.json \ "mtdbsa").as[MtdItId]
        case other =>
          throw UpstreamErrorResponse(result.body, other, other)
      }
    }
  }

  def getClientSaAgentSaReferences(
    nino: Nino)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[SaAgentReference]] = {
    val url = new URL(s"${appConfig.desUrl}/registration/relationship/nino/${encodePathSegment(nino.value)}")

    getWithDesHeaders("GetStatusAgentRelationship", url).map { response =>
      response.status match {
        case Status.OK =>
          response.json
            .as[Agents]
            .agents
            .filter(agent => agent.hasAgent && agent.agentCeasedDate.isEmpty)
            .flatMap(_.agentId)
        case other =>
          throw UpstreamErrorResponse(response.body, other, other)
      }
    }
  }

  // DES API #1168 Get Agent Status Relationship. This API is moving to IF platform hence baseUrl is feature switched
  def getActiveClientRelationships(taxIdentifier: TaxIdentifier)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Option[ActiveRelationship]] = {
    val encodedClientId = UriEncoding.encodePathSegment(taxIdentifier.value, "UTF-8")
    val url = taxIdentifier match {
      case MtdItId(_) =>
        new URL(
          s"$desBaseUrl/registration/relationship?ref-no=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}")
      case Vrn(_) =>
        new URL(
          s"$desBaseUrl/registration/relationship?idtype=VRN&ref-no=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}&relationship=ZA01&auth-profile=ALL00001")
      case Utr(_) =>
        new URL(
          s"$desBaseUrl/registration/relationship?idtype=UTR&ref-no=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}")
      case Urn(_) =>
        throw new Exception(s"URN is not supported on DES platform")
      case CgtRef(_) =>
        new URL(
          s"$desBaseUrl/registration/relationship?idtype=ZCGT&ref-no=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}&relationship=ZA01&auth-profile=ALL00001")
    }

    getWithDesHeaders("GetActiveClientItSaRelationships", url, desAuthToken, desEnv).map { response =>
      response.status match {
        case Status.OK =>
          response.json.as[ActiveRelationshipResponse].relationship.find(isActive)
        case Status.BAD_REQUEST                             => None
        case Status.NOT_FOUND                               => None
        case _ if response.body.contains("AGENT_SUSPENDED") => None
        case other: Int =>
          throw UpstreamErrorResponse(response.body, other, other)
      }
    }
  }

  def getAgentRecord(agentId: TaxIdentifier)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[AgentRecord] =
    getWithDesHeaders("GetAgentRecord", new URL(getAgentRecordUrl(agentId))).map { response =>
      response.status match {
        case Status.OK =>
          response.json.as[AgentRecord]
        case status => throw UpstreamErrorResponse(response.body, status, status)
      }
    }

  private def getAgentRecordUrl(agentId: TaxIdentifier) =
    agentId match {
      case Arn(arn) =>
        val encodedArn = UriEncoding.encodePathSegment(arn, "UTF-8")
        s"$desBaseUrl/registration/personal-details/arn/$encodedArn"
      case Utr(utr) =>
        val encodedUtr = UriEncoding.encodePathSegment(utr, "UTF-8")
        s"$desBaseUrl/registration/personal-details/utr/$encodedUtr"
      case _ =>
        throw new Exception(s"The client identifier $agentId is not supported.")
    }

  // DES API #1168 (for client)
  def getInactiveClientRelationships(taxIdentifier: TaxIdentifier)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Seq[InactiveRelationship]] = {
    val encodedClientId = UriEncoding.encodePathSegment(taxIdentifier.value, "UTF-8")

    val url = inactiveClientRelationshipDesUrl(taxIdentifier, encodedClientId)

    getWithDesHeaders("GetInactiveClientRelationships", url, desAuthToken, desEnv).map { response =>
      response.status match {
        case Status.OK =>
          response.json.as[InactiveRelationshipResponse].relationship.filter(isNotActive)
        case Status.BAD_REQUEST => Seq.empty
        case Status.NOT_FOUND   => Seq.empty
        case other: Int =>
          throw UpstreamErrorResponse(response.body, other, other)
      }
    }
  }

  private def inactiveClientRelationshipDesUrl(taxIdentifier: TaxIdentifier, encodedClientId: String) = {
    val fromDateString = appConfig.inactiveRelationshipsClientRecordStartDate
    val from = LocalDate.parse(fromDateString).toString
    val now = LocalDate.now().toString
    taxIdentifier match {
      case MtdItId(_) =>
        new URL(
          s"$desBaseUrl/registration/relationship?ref-no=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now")
      case Vrn(_) =>
        new URL(
          s"$desBaseUrl/registration/relationship?idtype=VRN&ref-no=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now&relationship=ZA01&auth-profile=ALL00001")
      case Utr(_) =>
        new URL(
          s"$desBaseUrl/registration/relationship?idtype=UTR&ref-no=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now")
      case Urn(_) =>
        throw new Exception(s"URN is not supported on DES platform")
      case CgtRef(_) =>
        new URL(
          s"$desBaseUrl/registration/relationship?idtype=ZCGT&ref-no=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now&relationship=ZA01&auth-profile=ALL00001")
    }
  }

  // DES API #1168 (for agent)
  def getInactiveRelationships(
    arn: Arn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[InactiveRelationship]] = {
    val encodedAgentId = UriEncoding.encodePathSegment(arn.value, "UTF-8")
    val now = LocalDate.now().toString
    val from: String = LocalDate.now().minusDays(showInactiveRelationshipsDays).toString
    val regime = "AGSV"
    val url = new URL(
      s"$desBaseUrl/registration/relationship?arn=$encodedAgentId&agent=true&active-only=false&regime=$regime&from=$from&to=$now")

    val cacheKey = s"${arn.value}-$now"
    agentCacheProvider.agentTrackPageCache(cacheKey) {
      getWithDesHeaders(s"GetInactiveRelationships", url, desAuthToken, desEnv).map { response =>
        response.status match {
          case Status.OK =>
            response.json.as[InactiveRelationshipResponse].relationship.filter(isNotActive)
          case Status.BAD_REQUEST                             => Seq.empty
          case Status.NOT_FOUND                               => Seq.empty
          case _ if response.body.contains("AGENT_SUSPENDED") => Seq.empty
          case other: Int =>
            throw UpstreamErrorResponse(response.body, other, other)
        }
      }
    }
  }

  def isActive(r: ActiveRelationship): Boolean = r.dateTo match {
    case None    => true
    case Some(d) => d.isAfter(LocalDate.now(DateTimeZone.UTC))
  }

  def isNotActive(r: InactiveRelationship): Boolean = r.dateTo match {
    case None    => false
    case Some(d) => d.isBefore(LocalDate.now(DateTimeZone.UTC)) || d.equals(LocalDate.now(DateTimeZone.UTC))
  }

  // DES API #1167 Create/Update Agent Relationship
  def createAgentRelationship(clientId: TaxIdentifier, arn: Arn)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[RegistrationRelationshipResponse] = {

    val url = new URL(s"$desBaseUrl/registration/relationship")
    val requestBody = createAgentRelationshipInputJson(clientId.value, arn.value, clientId)

    postWithDesHeaders("CreateAgentRelationship", url, requestBody, desAuthToken, desEnv)
      .map { response =>
        response.status match {
          case Status.OK => response.json.as[RegistrationRelationshipResponse]
          case other: Int =>
            throw UpstreamErrorResponse(response.body, other, other)
        }
      }
  }

  // DES API #1167 Create/Update Agent Relationship
  def deleteAgentRelationship(clientId: TaxIdentifier, arn: Arn)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[RegistrationRelationshipResponse] = {

    val url = new URL(s"$desBaseUrl/registration/relationship")
    postWithDesHeaders(
      "DeleteAgentRelationship",
      url,
      deleteAgentRelationshipInputJson(clientId.value, arn.value, clientId),
      desAuthToken,
      desEnv).map { response =>
      response.status match {
        case Status.OK => response.json.as[RegistrationRelationshipResponse]
        case other: Int =>
          throw UpstreamErrorResponse(response.body, other, other)
      }
    }
  }

  private def getRegimeFor(clientId: TaxIdentifier): String =
    clientId match {
      case MtdItId(_) => "ITSA"
      case Vrn(_)     => "VATC"
      case Utr(_)     => "TRS"
      case CgtRef(_)  => "CGT"
      case _          => throw new IllegalArgumentException(s"Tax identifier not supported $clientId")
    }

  private def getWithDesHeaders(apiName: String, url: URL, authToken: String = desAuthToken, env: String = desEnv)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[HttpResponse] = {
    val desHeaderCarrier = hc.copy(
      authorization = Some(Authorization(s"Bearer $authToken")),
      extraHeaders =
        hc.extraHeaders :+
          Environment   -> env :+
          CorrelationId -> UUID.randomUUID().toString
    )
    monitor(s"ConsumedAPI-DES-$apiName-GET") {
      httpClient
        .GET(url.toString, Nil, desHeaderCarrier.headers(headerNames))(
          implicitly[HttpReads[HttpResponse]],
          desHeaderCarrier,
          ec)
    }
  }

  private def postWithDesHeaders(apiName: String, url: URL, body: JsValue, authToken: String, env: String)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[HttpResponse] = {
    val desHeaderCarrier = hc.copy(
      authorization = Some(Authorization(s"Bearer $authToken")),
      extraHeaders =
        hc.extraHeaders :+
          Environment   -> env :+
          CorrelationId -> UUID.randomUUID().toString
    )
    monitor(s"ConsumedAPI-DES-$apiName-POST") {
      httpClient.POST(url.toString, body, desHeaderCarrier.headers(headerNames))(
        implicitly[Writes[JsValue]],
        implicitly[HttpReads[HttpResponse]],
        desHeaderCarrier,
        ec)
    }
  }

  private def createAgentRelationshipInputJson(refNum: String, agentRefNum: String, clientId: TaxIdentifier) =
    includeIdTypeIfNeeded(clientId)(Json.parse(s"""{
         "acknowledgmentReference": "${java.util.UUID.randomUUID().toString.replace("-", "").take(32)}",
          "agentReferenceNumber": "$agentRefNum",
          "refNumber": "$refNum",
          "regime": "${getRegimeFor(clientId)}",
          "authorisation": {
            "action": "Authorise",
            "isExclusiveAgent": true
          }
       }""").as[JsObject])

  private def deleteAgentRelationshipInputJson(refNum: String, agentRefNum: String, clientId: TaxIdentifier) =
    includeIdTypeIfNeeded(clientId)(Json.parse(s"""{
         "acknowledgmentReference": "${java.util.UUID.randomUUID().toString.replace("-", "").take(32)}",
          "refNumber": "$refNum",
          "agentReferenceNumber": "$agentRefNum",
          "regime": "${getRegimeFor(clientId)}",
          "authorisation": {
            "action": "De-Authorise"
          }
     }""").as[JsObject])

  private val includeIdTypeIfNeeded: TaxIdentifier => JsObject => JsObject = (clientId: TaxIdentifier) => { request =>
    (request \ "regime").asOpt[String] match {
      case Some("VATC") =>
        request +
          (("idType", JsString("VRN"))) +
          (("relationshipType", JsString("ZA01"))) +
          (("authProfile", JsString("ALL00001")))
      case Some("TRS") =>
        request + (("idType", JsString("UTR")))
      case Some("CGT") =>
        request +
          (("relationshipType", JsString("ZA01"))) +
          (("authProfile", JsString("ALL00001"))) +
          (("idType", JsString("ZCGT")))
      case _ =>
        request
    }
  }
}
