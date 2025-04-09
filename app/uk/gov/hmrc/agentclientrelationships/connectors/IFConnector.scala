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
import play.api.http.Status.{NOT_FOUND, OK}
import play.api.libs.json._
import play.utils.UriEncoding
import uk.gov.hmrc.agentclientrelationships.UriPathEncoding.encodePathSegment
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.stride.{ClientRelationship, ClientRelationshipResponse}
import uk.gov.hmrc.agentclientrelationships.services.AgentCacheProvider
import uk.gov.hmrc.agentclientrelationships.util.HttpAPIMonitor
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{Authorization, HeaderCarrier, HeaderNames, HttpClient, HttpReads, HttpResponse}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.net.URL
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class IFConnector @Inject() (val httpClient: HttpClient, val ec: ExecutionContext)(implicit
  val metrics: Metrics,
  val appConfig: AppConfig
) extends IFConnectorCommon
    with HttpAPIMonitor
    with Logging {

  private val ifBaseUrl = appConfig.ifPlatformBaseUrl

  private val ifAPI1171Token = appConfig.ifAPI1171Token
  private val ifEnv = appConfig.ifEnvironment

  /*
  API#1171 Get Business Details (for ITSA customers)
  https://confluence.tools.tax.service.gov.uk/display/AG/API+1171+%28API+5%29+-+Get+Business+Details
   * */
  def getNinoFor(mtdId: MtdItId)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[Nino]] = {
    val url = new URL(s"$ifBaseUrl/registration/business-details/mtdId/${encodePathSegment(mtdId.value)}")

    getWithIFHeaders("GetBusinessDetailsByMtdId", url, ifAPI1171Token, ifEnv).map { result =>
      result.status match {
        case OK        => Option((result.json \ "taxPayerDisplayResponse" \ "nino").as[Nino])
        case NOT_FOUND => None
        case other =>
          logger.error(s"Error API#1171 GetBusinessDetailsByMtdIId. $other, ${result.body}")
          None
      }
    }
  }

  /*
    API#1171 Get Business Details (for ITSA customers)
    https://confluence.tools.tax.service.gov.uk/display/AG/API+1171+%28API+5%29+-+Get+Business+Details
   * */
  def getMtdIdFor(nino: Nino)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[MtdItId]] = {
    val url = new URL(s"$ifBaseUrl/registration/business-details/nino/${encodePathSegment(nino.value)}")

    getWithIFHeaders("GetBusinessDetailsByNino", url, ifAPI1171Token, ifEnv).map { result =>
      result.status match {
        case OK        => Option((result.json \ "taxPayerDisplayResponse" \ "mtdId").as[MtdItId])
        case NOT_FOUND => None
        case other =>
          logger.error(s"Error API#1171 GetBusinessDetailsByNino. $other, ${result.body}")
          None
      }
    }
  }

}

class IFRelationshipConnector @Inject() (
  val httpClient: HttpClient,
  agentCacheProvider: AgentCacheProvider,
  val ec: ExecutionContext
)(implicit
  val metrics: Metrics,
  val appConfig: AppConfig
) extends IFConnectorCommon
    with RelationshipConnector
    with HttpAPIMonitor
    with Logging {

  private val ifBaseUrl = appConfig.ifPlatformBaseUrl

  private val ifAuthToken = appConfig.ifAuthToken
  private val ifEnv = appConfig.ifEnvironment
  private val showInactiveRelationshipsDays = appConfig.inactiveRelationshipShowLastDays

  def isActive(r: ActiveRelationship): Boolean = r.dateTo match {
    case None    => true
    case Some(d) => d.isAfter(Instant.now().atZone(ZoneOffset.UTC).toLocalDate)
  }

  def isNotActive(r: InactiveRelationship): Boolean = r.dateTo match {
    case None => false
    case Some(d) =>
      d.isBefore(Instant.now().atZone(ZoneOffset.UTC).toLocalDate) || d.equals(
        Instant.now().atZone(ZoneOffset.UTC).toLocalDate
      )
  }

  // IF API #1168
  def getActiveClientRelationships(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[ActiveRelationship]] = {

    val authProfile = getAuthProfile(service.id)
    val url = relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = Some(authProfile), activeOnly = true)
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

  // IF API #1168 //url and error handling is different to getActiveClientRelationships
  override def getAllRelationships(
    taxIdentifier: TaxIdentifier,
    activeOnly: Boolean
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[RelationshipFailureResponse, Seq[ClientRelationship]]] = {
    val url = relationshipIFUrl(taxIdentifier = taxIdentifier, authProfileOption = None, activeOnly = activeOnly)
    getWithIFHeaders("GetActiveClientRelationships", url, ifAuthToken, ifEnv).map { response =>
      response.status match {
        case Status.OK =>
          Right(response.json.as[ClientRelationshipResponse].relationship)
        case Status.NOT_FOUND                               => Left(RelationshipFailureResponse.RelationshipNotFound)
        case _ if response.body.contains("AGENT_SUSPENDED") => Left(RelationshipFailureResponse.RelationshipSuspended)
        case Status.BAD_REQUEST =>
          logger.error(s"Error in IF 'GetActiveClientRelationships' with BadRequest response")
          Left(RelationshipFailureResponse.RelationshipBadRequest)
        case other: Int =>
          logger.error(s"Error in IF GetActiveClientRelationships: $other, ${response.body}")
          Left(
            RelationshipFailureResponse
              .ErrorRetrievingRelationship(other, s"Error in IF 'GetActiveClientRelationships' with status: $other")
          )
      }
    }
  }

  // IF API #1168
  def getInactiveClientRelationships(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[InactiveRelationship]] = {

    val authProfile = getAuthProfile(service.id)
    val url = relationshipIFUrl(taxIdentifier, Some(authProfile), activeOnly = false)

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
    arn: Arn
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[InactiveRelationship]] = {
    val encodedAgentId = UriEncoding.encodePathSegment(arn.value, "UTF-8")
    val now = LocalDate.now().toString
    val from: String = LocalDate.now().minusDays(showInactiveRelationshipsDays).toString
    val regime = "AGSV"
    val url = new URL(
      s"$ifBaseUrl/registration/relationship?arn=$encodedAgentId&agent=true&active-only=false&regime=$regime&from=$from&to=$now"
    )

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
  def createAgentRelationship(enrolmentKey: EnrolmentKey, arn: Arn)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[RegistrationRelationshipResponse]] = {

    val url = new URL(s"$ifBaseUrl/registration/relationship")
    val requestBody = createAgentRelationshipInputJson(enrolmentKey, arn.value)

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
  def deleteAgentRelationship(enrolmentKey: EnrolmentKey, arn: Arn)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[RegistrationRelationshipResponse]] = {

    val url = new URL(s"$ifBaseUrl/registration/relationship")
    postWithIFHeaders(
      "DeleteAgentRelationship",
      url,
      deleteAgentRelationshipInputJson(enrolmentKey: EnrolmentKey, arn.value),
      ifAuthToken,
      ifEnv
    ).map { response =>
      response.status match {
        case Status.OK => Option(response.json.as[RegistrationRelationshipResponse])
        case other: Int =>
          logger.error(s"Error in IF DeleteAgentRelationship: $other, ${response.body}")
          None
      }
    }
  }

  private def relationshipIFUrl(
    taxIdentifier: TaxIdentifier,
    authProfileOption: Option[String],
    activeOnly: Boolean
  ) = {

    val dateRangeParams =
      if (activeOnly) ""
      else {
        val fromDateString = appConfig.inactiveRelationshipsClientRecordStartDate
        val from = LocalDate.parse(fromDateString).toString
        val now = LocalDate.now().toString
        s"&from=$from&to=$now"
      }

    val authProfileParam = authProfileOption.map(x => s"auth-profile=$x").getOrElse("")
    val encodedClientId = UriEncoding.encodePathSegment(taxIdentifier.value, "UTF-8")

    taxIdentifier match {
      case MtdItId(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?referenceNumber=$encodedClientId&agent=false&active-only=$activeOnly&regime=${getRegimeFor(taxIdentifier)}$dateRangeParams&relationship=ZA01&$authProfileParam"
        )
      case Vrn(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=VRN&referenceNumber=$encodedClientId&agent=false&active-only=$activeOnly&regime=${getRegimeFor(taxIdentifier)}$dateRangeParams&relationship=ZA01&$authProfileParam"
        )
      case Utr(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=UTR&referenceNumber=$encodedClientId&agent=false&active-only=$activeOnly&regime=${getRegimeFor(taxIdentifier)}$dateRangeParams"
        )
      case Urn(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=URN&referenceNumber=$encodedClientId&agent=false&active-only=$activeOnly&regime=${getRegimeFor(taxIdentifier)}$dateRangeParams"
        )
      case CgtRef(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=ZCGT&referenceNumber=$encodedClientId&agent=false&active-only=$activeOnly&regime=${getRegimeFor(taxIdentifier)}$dateRangeParams&relationship=ZA01&$authProfileParam"
        )
      case PptRef(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=ZPPT&referenceNumber=$encodedClientId&agent=false&active-only=$activeOnly&regime=${getRegimeFor(taxIdentifier)}$dateRangeParams&relationship=ZA01&$authProfileParam"
        )
      case CbcId(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=CBC&referenceNumber=$encodedClientId&agent=false&active-only=$activeOnly&regime=${getRegimeFor(taxIdentifier)}$dateRangeParams"
        )
      case PlrId(_) =>
        new URL(
          s"$ifBaseUrl/registration/relationship?idType=ZPLR&referenceNumber=$encodedClientId&agent=false&active-only=$activeOnly&regime=${getRegimeFor(taxIdentifier)}$dateRangeParams"
        )
      case _ => throw new IllegalStateException(s"Unsupported Identifier $taxIdentifier")

    }
  }

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

  private def createAgentRelationshipInputJson(enrolmentKey: EnrolmentKey, arn: String) =
    includeIdTypeIfNeeded(enrolmentKey)(
      Json
        .parse(s"""{
         "acknowledgmentReference": "${java.util.UUID.randomUUID().toString.replace("-", "").take(32)}",
          "agentReferenceNumber": "$arn",
          "refNumber": "${enrolmentKey.oneIdentifier().value}",
          "regime": "${getRegimeFor(enrolmentKey.oneTaxIdentifier())}",
          "authorisation": {
            "action": "Authorise",
            "isExclusiveAgent": true
          }
       }""")
        .as[JsObject]
    )

  private def deleteAgentRelationshipInputJson(enrolmentKey: EnrolmentKey, arn: String) =
    includeIdTypeIfNeeded(enrolmentKey)(
      Json
        .parse(s"""{
         "acknowledgmentReference": "${java.util.UUID.randomUUID().toString.replace("-", "").take(32)}",
          "refNumber": "${enrolmentKey.oneIdentifier().value}",
          "agentReferenceNumber": "$arn",
          "regime": "${getRegimeFor(enrolmentKey.oneTaxIdentifier())}",
          "authorisation": {
            "action": "De-Authorise"
          }
     }""")
        .as[JsObject]
    )
  private def getAuthProfile(service: String): String = service match {
    case HMRCMTDITSUPP => "ITSAS001"
    case _             => "ALL00001"
  }

  private val includeIdTypeIfNeeded: EnrolmentKey => JsObject => JsObject = (enrolmentKey: EnrolmentKey) => { request =>
    val clientId = enrolmentKey.oneTaxIdentifier()
    val authProfileForService = getAuthProfile(enrolmentKey.service)

    (request \ "regime").asOpt[String] match {
      case Some("VATC") =>
        request +
          ((idType, JsString("VRN"))) +
          ((relationshipType, JsString("ZA01"))) +
          ((authProfile, JsString(authProfileForService)))
      case Some("TRS") =>
        clientId match {
          case Utr(_) => request + ((idType, JsString("UTR")))
          case Urn(_) => request + ((idType, JsString("URN")))
          case e      => throw new Exception(s"unsupported tax identifier $e for regime TRS")
        }
      case Some("CGT") =>
        request +
          ((relationshipType, JsString("ZA01"))) +
          ((authProfile, JsString(authProfileForService))) +
          ((idType, JsString("ZCGT")))
      case Some("PPT") =>
        request +
          ((relationshipType, JsString("ZA01"))) +
          ((authProfile, JsString(authProfileForService))) +
          ((idType, JsString("ZPPT")))
      case Some("ITSA") =>
        request +
          ((relationshipType, JsString("ZA01"))) +
          ((authProfile, JsString(authProfileForService))) +
          ((idType, JsString("MTDBSA")))
      case Some("CBC") =>
        request +
          ((idType, JsString("CBC")))
      case Some("PLR") =>
        request +
          ((relationshipType, JsString("ZA01"))) +
          ((authProfile, JsString(authProfileForService))) +
          ((idType, JsString("ZPLR")))
      case _ =>
        request
    }
  }
  private val idType = "idType"
  private val authProfile = "authProfile"
  private val relationshipType = "relationshipType"
}

trait IFConnectorCommon extends HttpAPIMonitor {

  val httpClient: HttpClient
  val ec: ExecutionContext
  val metrics: Metrics
  val appConfig: AppConfig

  private val Environment = "Environment"
  private val CorrelationId = "CorrelationId"

  private def isInternalHost(url: URL): Boolean =
    appConfig.internalHostPatterns.exists(_.pattern.matcher(url.getHost).matches())

  private[connectors] def getWithIFHeaders(apiName: String, url: URL, authToken: String, env: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[HttpResponse] = {

    val isInternal = isInternalHost(url)

    monitor(s"ConsumedAPI-IF-$apiName-GET") {
      httpClient.GET(url.toString, Nil, ifHeaders(authToken, env, isInternal))(
        implicitly[HttpReads[HttpResponse]],
        if (isInternal) hc.copy(authorization = Some(Authorization(s"Bearer $authToken"))) else hc,
        ec
      )
    }
  }

  private[connectors] def postWithIFHeaders(apiName: String, url: URL, body: JsValue, authToken: String, env: String)(
    implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[HttpResponse] = {

    val isInternal = isInternalHost(url)

    monitor(s"ConsumedAPI-IF-$apiName-POST") {
      httpClient.POST(url.toString, body, ifHeaders(authToken, env, isInternal))(
        implicitly[Writes[JsValue]],
        implicitly[HttpReads[HttpResponse]],
        if (isInternal) hc.copy(authorization = Some(Authorization(s"Bearer $authToken"))) else hc,
        ec
      )
    }
  }

  /*
   * If the service being called is external (e.g. DES/IF in QA or Prod):
   * headers from HeaderCarrier are removed (except user-agent header).
   * Therefore, required headers must be explicitly set.
   * See https://github.com/hmrc/http-verbs?tab=readme-ov-file#propagation-of-headers
   * */
  def ifHeaders(authToken: String, env: String, isInternalHost: Boolean)(implicit
    hc: HeaderCarrier
  ): Seq[(String, String)] = {

    val additionalHeaders =
      if (isInternalHost) Seq.empty
      else
        Seq(
          HeaderNames.authorisation -> s"Bearer $authToken",
          HeaderNames.xRequestId    -> hc.requestId.map(_.value).getOrElse(UUID.randomUUID().toString)
        ) ++ hc.sessionId.fold(Seq.empty[(String, String)])(x => Seq(HeaderNames.xSessionId -> x.value))
    val commonHeaders = Seq(Environment -> env, CorrelationId -> UUID.randomUUID().toString)
    commonHeaders ++ additionalHeaders
  }
}
