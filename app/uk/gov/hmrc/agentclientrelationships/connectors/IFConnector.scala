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

import javax.inject.{Inject, Singleton}
import com.codahale.metrics.MetricRegistry
import play.api.Logging
import play.api.http.Status
import play.api.libs.json._
import play.utils.UriEncoding
import uk.gov.hmrc.agent.kenshoo.monitoring.HttpAPIMonitor
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.{ActiveRelationship, ActiveRelationshipResponse, InactiveRelationship, InactiveRelationshipResponse, RegistrationRelationshipResponse}
import uk.gov.hmrc.agentclientrelationships.services.AgentCacheProvider
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, CbcId, CgtRef, MtdItId, PlrId, PptRef, Urn, Utr, Vrn}
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames, HttpClient, HttpReads, HttpResponse}

import java.net.URL
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class IFConnector @Inject()(
  httpClient: HttpClient,
  override val kenshooRegistry: MetricRegistry,
  agentCacheProvider: AgentCacheProvider)(implicit val appConfig: AppConfig)
    extends HttpAPIMonitor
    with Logging {

  private val ifBaseUrl = appConfig.ifPlatformBaseUrl
  private val ifAuthToken = appConfig.ifAuthToken
  private val ifEnv = appConfig.ifEnvironment
  private val showInactiveRelationshipsDays = appConfig.inactiveRelationshipShowLastDays

  private val Environment = "Environment"
  private val CorrelationId = "CorrelationId"

  def isActive(r: ActiveRelationship): Boolean = r.dateTo match {
    case None    => true
    case Some(d) => d.isAfter(Instant.now().atZone(ZoneOffset.UTC).toLocalDate)
  }

  def isNotActive(r: InactiveRelationship): Boolean = r.dateTo match {
    case None => false
    case Some(d) =>
      d.isBefore(Instant.now().atZone(ZoneOffset.UTC).toLocalDate) || d.equals(
        Instant.now().atZone(ZoneOffset.UTC).toLocalDate)
  }

  private def getActiveClientRelationshipsUrl(taxIdentifier: TaxIdentifier): URL = {
    val encodedClientId = UriEncoding.encodePathSegment(taxIdentifier.value, "UTF-8")
    taxIdentifier match {
      case MtdItId(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?referenceNumber=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}&relationship=ZA01&auth-profile=ALL00001")
      case Vrn(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=VRN&referenceNumber=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}&relationship=ZA01&auth-profile=ALL00001")
      case Utr(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=UTR&referenceNumber=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}")
      case Urn(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=URN&referenceNumber=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}")
      case CgtRef(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=ZCGT&referenceNumber=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}&relationship=ZA01&auth-profile=ALL00001")
      case PptRef(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=ZPPT&referenceNumber=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}&relationship=ZA01&auth-profile=ALL00001")
      case CbcId(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=CBC&referenceNumber=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}")
      case PlrId(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=PLR&referenceNumber=$encodedClientId&agent=false&active-only=true&regime=${getRegimeFor(taxIdentifier)}")
      case e =>
        val message = s"getActiveClientRelationshipsUrl: Unexpected tax identifier type $e"
        logger.error(message)
        throw new IllegalArgumentException(message)
    }
  }

  // IF API #1168
  def getActiveClientRelationships(taxIdentifier: TaxIdentifier)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Option[ActiveRelationship]] = {
    val url = getActiveClientRelationshipsUrl(taxIdentifier)
    getWithIFHeaders("GetActiveClientRelationships", url, ifAuthToken, ifEnv).map { response =>
      response.status match {
        case Status.OK =>
          response.json.as[ActiveRelationshipResponse].relationship.find(isActive)
        case Status.BAD_REQUEST                             => None
        case Status.NOT_FOUND                               => None
        case _ if response.body.contains("AGENT_SUSPENDED") => None
        case other: Int =>
          logger.error(s"Error in IF GetActiveClientRelationships: $other, ${response.body}")
          None
      }
    }
  }

  // IF API #1168
  def getInactiveClientRelationships(taxIdentifier: TaxIdentifier)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Seq[InactiveRelationship]] = {
    val encodedClientId = UriEncoding.encodePathSegment(taxIdentifier.value, "UTF-8")

    val url = inactiveClientRelationshipIFUrl(taxIdentifier, encodedClientId)

    getWithIFHeaders("GetInactiveClientRelationships", url, ifAuthToken, ifEnv).map { response =>
      response.status match {
        case Status.OK =>
          response.json.as[InactiveRelationshipResponse].relationship.filter(isNotActive)
        case Status.BAD_REQUEST                             => Seq.empty
        case Status.NOT_FOUND                               => Seq.empty
        case _ if response.body.contains("AGENT_SUSPENDED") => Seq.empty
        case other: Int =>
          logger.error(s"Error in IF GetInactiveClientRelationships: $other, ${response.body}")
          Seq.empty
      }
    }
  }

  // IF API #1168 (for agent)
  def getInactiveRelationships(
    arn: Arn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[InactiveRelationship]] = {
    val encodedAgentId = UriEncoding.encodePathSegment(arn.value, "UTF-8")
    val now = LocalDate.now().toString
    val from: String = LocalDate.now().minusDays(showInactiveRelationshipsDays).toString
    val regime = "AGSV"
    val url = new URL(
      s"$ifBaseUrl/registration/relationship?arn=$encodedAgentId&agent=true&active-only=false&regime=$regime&from=$from&to=$now")

    val cacheKey = s"${arn.value}-$now"
    agentCacheProvider.agentTrackPageCache(cacheKey) {
      getWithIFHeaders(s"GetInactiveRelationships", url, ifAuthToken, ifEnv).map { response =>
        response.status match {
          case Status.OK =>
            response.json.as[InactiveRelationshipResponse].relationship.filter(isNotActive)
          case Status.BAD_REQUEST                             => Seq.empty
          case Status.NOT_FOUND                               => Seq.empty
          case _ if response.body.contains("AGENT_SUSPENDED") => Seq.empty
          case other: Int =>
            logger.error(s"Error in IF GetInactiveRelationships: $other, ${response.body}")
            Seq.empty
        }
      }
    }
  }

  // IF API #1167 Create/Update Agent Relationship
  def createAgentRelationship(clientId: TaxIdentifier, arn: Arn)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Option[RegistrationRelationshipResponse]] = {

    val url = new URL(s"$ifBaseUrl/registration/relationship")
    val requestBody = createAgentRelationshipInputJson(clientId.value, arn.value, clientId)

    postWithIFHeaders("CreateAgentRelationship", url, requestBody, ifAuthToken, ifEnv)
      .map { response =>
        response.status match {
          case Status.OK => Option(response.json.as[RegistrationRelationshipResponse])
          case other: Int =>
            logger.error(s"Error in IF CreateAgentRelationship: $other, ${response.body}")
            None
        }
      }
  }

  // IF API #1167 Create/Update Agent Relationship
  def deleteAgentRelationship(clientId: TaxIdentifier, arn: Arn)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Option[RegistrationRelationshipResponse]] = {

    val url = new URL(s"$ifBaseUrl/registration/relationship")
    postWithIFHeaders(
      "DeleteAgentRelationship",
      url,
      deleteAgentRelationshipInputJson(clientId.value, arn.value, clientId),
      ifAuthToken,
      ifEnv).map { response =>
      response.status match {
        case Status.OK => Option(response.json.as[RegistrationRelationshipResponse])
        case other: Int =>
          logger.error(s"Error in IF DeleteAgentRelationship: $other, ${response.body}")
          None
      }
    }
  }

  private def inactiveClientRelationshipIFUrl(taxIdentifier: TaxIdentifier, encodedClientId: String) = {
    val fromDateString = appConfig.inactiveRelationshipsClientRecordStartDate
    val from = LocalDate.parse(fromDateString).toString
    val now = LocalDate.now().toString
    taxIdentifier match {
      case MtdItId(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?referenceNumber=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now&relationship=ZA01&auth-profile=ALL00001")
      case Vrn(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=VRN&referenceNumber=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now&relationship=ZA01&auth-profile=ALL00001")
      case Utr(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=UTR&referenceNumber=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now")
      case Urn(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=URN&referenceNumber=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now")
      case CgtRef(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=ZCGT&referenceNumber=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now&relationship=ZA01&auth-profile=ALL00001")
      case PptRef(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=ZPPT&referenceNumber=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now&relationship=ZA01&auth-profile=ALL00001")
      case CbcId(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=CBC&referenceNumber=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now")
      case PlrId(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=PLR&referenceNumber=$encodedClientId&agent=false&active-only=false&regime=${getRegimeFor(
            taxIdentifier)}&from=$from&to=$now")
      case e =>
        val message = s"inactiveClientRelationshipIFUrl: Unexpected tax identifier type $e"
        logger.error(message)
        throw new IllegalArgumentException(message)
    }
  }

  private def getWithIFHeaders(apiName: String, url: URL, authToken: String, env: String)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[HttpResponse] =
    monitor(s"ConsumedAPI-IF-$apiName-GET") {
      httpClient.GET(url.toString, Nil, ifHeaders(authToken, env))(implicitly[HttpReads[HttpResponse]], hc, ec)
    }

  private def postWithIFHeaders(apiName: String, url: URL, body: JsValue, authToken: String, env: String)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[HttpResponse] =
    monitor(s"ConsumedAPI-IF-$apiName-POST") {
      httpClient.POST(url.toString, body, ifHeaders(authToken, env))(
        implicitly[Writes[JsValue]],
        implicitly[HttpReads[HttpResponse]],
        hc,
        ec)
    }

  private def ifHeaders(authToken: String, env: String) = Seq(
    HeaderNames.authorisation -> s"Bearer $authToken",
    Environment               -> env,
    CorrelationId             -> UUID.randomUUID().toString
  )

  private def getRegimeFor(clientId: TaxIdentifier): String =
    clientId match {
      case MtdItId(_) => "ITSA"
      case Vrn(_)     => "VATC"
      case Utr(_)     => "TRS"
      case Urn(_)     => "TRS"
      case CgtRef(_)  => "CGT"
      case PptRef(_)  => "PPT"
      case CbcId(_)   => "CBC"
      case PlrId(_)   => "PLR"
      case _          => throw new IllegalArgumentException(s"Tax identifier not supported $clientId")
    }

  private def createAgentRelationshipInputJson(referenceNumber: String, agentRefNum: String, clientId: TaxIdentifier) =
    includeIdTypeIfNeeded(clientId)(Json.parse(s"""{
         "acknowledgmentReference": "${java.util.UUID.randomUUID().toString.replace("-", "").take(32)}",
          "agentReferenceNumber": "$agentRefNum",
          "refNumber": "$referenceNumber",
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
        clientId match {
          case Utr(_) => request + (("idType", JsString("UTR")))
          case Urn(_) => request + (("idType", JsString("URN")))
          case e      => throw new Exception(s"unsupported tax identifier $e for regime TRS")
        }
      case Some("CGT") =>
        request +
          (("relationshipType", JsString("ZA01"))) +
          (("authProfile", JsString("ALL00001"))) +
          (("idType", JsString("ZCGT")))
      case Some("PPT") =>
        request +
          (("relationshipType", JsString("ZA01"))) +
          (("authProfile", JsString("ALL00001"))) +
          (("idType", JsString("ZPPT")))
      case Some("ITSA") =>
        request +
          (("relationshipType", JsString("ZA01"))) +
          (("authProfile", JsString("ALL00001")))
      case Some("CBC") =>
        request +
          (("idType", JsString("CBC")))
      case Some("PLR") =>
        request +
          (("idType", JsString("PLR")))
      case _ =>
        request
    }
  }

}
