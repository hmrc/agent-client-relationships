/*
 * Copyright 2020 HM Revenue & Customs
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
import uk.gov.hmrc.http.logging.Authorization
import uk.gov.hmrc.http.{HttpClient, _}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DesConnector @Inject()(httpClient: HttpClient, metrics: Metrics, agentCacheProvider: AgentCacheProvider)(
  implicit val appConfig: AppConfig)
    extends HttpAPIMonitor
    with Logging {
  override val kenshooRegistry: MetricRegistry = metrics.defaultRegistry

  val desAuthToken = appConfig.desToken
  val desEnv = appConfig.desEnv
  val showInactiveRelationshipsDays = appConfig.inactiveRelationshipShowLastDays

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

  def getActiveClientRelationships(taxIdentifier: TaxIdentifier)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Option[ActiveRelationship]] = {
    val baseUrl: String = if (appConfig.desIFEnabled)appConfig.ifPlatformBaseUrl else appConfig.desUrl
    val env: String = if (appConfig.desIFEnabled)appConfig.ifEnvironment else desEnv
    val authToken: String = if (appConfig.desIFEnabled)appConfig.ifAuthToken else desAuthToken

    val encodedClientId = UriEncoding.encodePathSegment(taxIdentifier.value, "UTF-8")
    val url = taxIdentifier match {
      case MtdItId(_) =>
        new URL(
          s"$baseUrl/registration/relationship?ref-no=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}")
      case Vrn(_) =>
        new URL(
          s"$baseUrl/registration/relationship?idtype=VRN&ref-no=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}&relationship=ZA01&auth-profile=ALL00001")
      case Utr(_) =>
        new URL(
          s"$baseUrl/registration/relationship?idtype=UTR&ref-no=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}")
      case Urn(_) =>
        new URL(
          s"$baseUrl/registration/relationship?idtype=URN&ref-no=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}")
      case CgtRef(_) =>
        new URL(
          s"$baseUrl/registration/relationship?idtype=ZCGT&ref-no=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}&relationship=ZA01&auth-profile=ALL00001")
    }


    getWithDesHeaders("GetActiveClientItSaRelationships", url, authToken, env).map { response =>
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

  def getInactiveClientRelationships(taxIdentifier: TaxIdentifier)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Seq[InactiveRelationship]] = {
    val encodedClientId = UriEncoding.encodePathSegment(taxIdentifier.value, "UTF-8")

    val url = inactiveClientRelationshipDesUrl(taxIdentifier, encodedClientId)

    getWithDesHeaders("GetInactiveClientRelationships", url).map { response =>
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
          s"${appConfig.desUrl}/registration/relationship?ref-no=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now")
      case Vrn(_) =>
        new URL(
          s"${appConfig.desUrl}/registration/relationship?idtype=VRN&ref-no=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now&relationship=ZA01&auth-profile=ALL00001")
      case Utr(_) =>
        new URL(
          s"${appConfig.desUrl}/registration/relationship?idtype=UTR&ref-no=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now")
      case Urn(_) =>
        new URL(
          s"${appConfig.desUrl}/registration/relationship?idtype=URN&ref-no=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now")
      case CgtRef(_) =>
        new URL(
          s"${appConfig.desUrl}/registration/relationship?idtype=ZCGT&ref-no=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now&relationship=ZA01&auth-profile=ALL00001")
    }
  }

  def getInactiveRelationships(
    arn: Arn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[InactiveRelationship]] = {
    val encodedAgentId = UriEncoding.encodePathSegment(arn.value, "UTF-8")
    val now = LocalDate.now().toString
    val from: String = LocalDate.now().minusDays(showInactiveRelationshipsDays).toString
    val regime = "AGSV"
    val url = new URL(
      s"${appConfig.desUrl}/registration/relationship?arn=$encodedAgentId&agent=true&active-only=false&regime=$regime&from=$from&to=$now")

    val cacheKey = s"${arn.value}-$now"
    agentCacheProvider.agentTrackPageCache(cacheKey) {
      getWithDesHeaders(s"GetInactiveRelationships", url).map { response =>
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

  def createAgentRelationship(clientId: TaxIdentifier, arn: Arn)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[RegistrationRelationshipResponse] = {

    val url = new URL(s"${appConfig.desUrl}/registration/relationship")
    val requestBody = createAgentRelationshipInputJson(clientId.value, arn.value, getRegimeFor(clientId))

    postWithDesHeaders("CreateAgentRelationship", url, requestBody).map { response =>
      response.status match {
        case Status.OK => response.json.as[RegistrationRelationshipResponse]
        case other: Int =>
          throw UpstreamErrorResponse(response.body, other, other)
      }
    }
  }

  def deleteAgentRelationship(clientId: TaxIdentifier, arn: Arn)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[RegistrationRelationshipResponse] = {

    val url = new URL(s"${appConfig.desUrl}/registration/relationship")
    postWithDesHeaders(
      "DeleteAgentRelationship",
      url,
      deleteAgentRelationshipInputJson(clientId.value, arn.value, getRegimeFor(clientId))).map { response =>
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
      case Urn(_)     => "TRS"
      case CgtRef(_)  => "CGT"
      case _          => throw new IllegalArgumentException(s"Tax identifier not supported $clientId")
    }

  private def getWithDesHeaders(apiName: String, url: URL, authToken: String = desAuthToken, env: String = desEnv)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[HttpResponse] = {
    val desHeaderCarrier = hc.copy(
      authorization = Some(Authorization(s"Bearer $desAuthToken")),
      extraHeaders = hc.extraHeaders :+ "Environment" -> desEnv)
    monitor(s"ConsumedAPI-DES-$apiName-GET") {
      httpClient.GET(url.toString)(implicitly[HttpReads[HttpResponse]], desHeaderCarrier, ec)
    }
  }

  private def postWithDesHeaders(apiName: String, url: URL, body: JsValue)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[HttpResponse] = {
    val desHeaderCarrier = hc.copy(
      authorization = Some(Authorization(s"Bearer ${appConfig.desToken}")),
      extraHeaders = hc.extraHeaders :+ "Environment" -> appConfig.desEnv)
    monitor(s"ConsumedAPI-DES-$apiName-POST") {
      httpClient.POST(url.toString, body)(
        implicitly[Writes[JsValue]],
        implicitly[HttpReads[HttpResponse]],
        desHeaderCarrier,
        ec)
    }
  }

  private def createAgentRelationshipInputJson(refNum: String, agentRefNum: String, regime: String) =
    includeIdTypeIfNeeded(Json.parse(s"""{
         "acknowledgmentReference": "${java.util.UUID.randomUUID().toString.replace("-", "").take(32)}",
          "agentReferenceNumber": "$agentRefNum",
          "refNumber": "$refNum",
          "regime": "$regime",
          "authorisation": {
            "action": "Authorise",
            "isExclusiveAgent": true
          }
       }""").as[JsObject])

  private def deleteAgentRelationshipInputJson(refNum: String, agentRefNum: String, regime: String) =
    includeIdTypeIfNeeded(Json.parse(s"""{
         "acknowledgmentReference": "${java.util.UUID.randomUUID().toString.replace("-", "").take(32)}",
          "refNumber": "$refNum",
          "agentReferenceNumber": "$agentRefNum",
          "regime": "$regime",
          "authorisation": {
            "action": "De-Authorise"
          }
     }""").as[JsObject])

  private val includeIdTypeIfNeeded: JsObject => JsObject = { request =>
    (request \ "regime").asOpt[String] match {
      case Some("VATC") =>
        request +
          (("idType", JsString("VRN"))) +
          (("relationshipType", JsString("ZA01"))) +
          (("authProfile", JsString("ALL00001")))
      case Some("TRS") =>
        request +
          (("idType", JsString("UTR")))
      case Some("TrustNT") =>
        request +
          (("idType", JsString("URN")))
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
