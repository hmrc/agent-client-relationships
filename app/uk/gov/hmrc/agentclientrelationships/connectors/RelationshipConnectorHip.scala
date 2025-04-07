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

import cats.data.EitherT
import play.api.Logging
import play.api.http.Status
import play.api.libs.json._
import play.api.mvc.RequestHeader
import play.utils.UriEncoding
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.helpers.HipHeaders
import uk.gov.hmrc.agentclientrelationships.model.stride.ClientRelationship
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentKey, _}
import uk.gov.hmrc.agentclientrelationships.services.AgentCacheProvider
import uk.gov.hmrc.agentclientrelationships.util.{HttpApiMonitor, HttpReadsImplicits}
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport.hc
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.TaxIdentifier
import HttpReadsImplicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpErrorFunctions, HttpExceptions, HttpReads, HttpResponse, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.net.URL
import java.time.{Instant, LocalDate, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}


@Singleton
class RelationshipConnectorHip @Inject()(
                                          httpClient: HttpClientV2,
                                          agentCacheProvider: AgentCacheProvider,
                                          hipHeaders: HipHeaders,
                                          val metrics: Metrics,
                                          appConfig: AppConfig)(implicit
  val ec: ExecutionContext
) extends RelationshipConnector
    with HttpApiMonitor
    with Logging {

  private val baseUrl = appConfig.hipPlatformBaseUrl
  private val showInactiveRelationshipsDays = appConfig.inactiveRelationshipShowLastDays

  // HIP API #EPID1521
  def createAgentRelationship(enrolmentKey: EnrolmentKey, arn: Arn)(implicit
    rh: RequestHeader
  ): Future[Unit] = monitor(s"ConsumedAPI-HIP-CreateAgentRelationship-POST"){
    val requestBody: JsObject = createAgentRelationshipHipInputJson(
      enrolmentKey = enrolmentKey,
      arn = arn.value,
      isExclusiveAgent = isExclusiveAgent(enrolmentKey.service)
    )
    httpClient
      .post(url"$baseUrl/etmp/RESTAdapter/rosm/agent-relationship")
      .withBody(requestBody)
      .setHeader(hipHeaders.makeHeaders(): _*)
      .execute[Unit]
  }

  // HIP API #EPID1521
  def deleteAgentRelationship(enrolmentKey: EnrolmentKey, arn: Arn)(implicit
    rh: RequestHeader
  ): Future[Unit] = monitor(s"ConsumedAPI-HIP-DeleteAgentRelationship-POST"){

    val requestBody: JsObject = deleteAgentRelationshipInputJson(
      enrolmentKey = enrolmentKey,
      arn = arn.value,
      isExclusiveAgent = isExclusiveAgent(enrolmentKey.service)
    )

    httpClient
      .post(url"$baseUrl/etmp/RESTAdapter/rosm/agent-relationship")
      .withBody(requestBody)
      .setHeader(hipHeaders.makeHeaders(): _*)
      .execute[Unit]
  }

  // HIP API #EPID1521
  def getActiveClientRelationship(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit rh: RequestHeader): Future[Option[ActiveRelationship]] = monitor(s"ConsumedAPI-HIP-GetActiveClientRelationships-GET"){

    val authProfile = getAuthProfile(service.id)
    val url: URL =
      relationshipHipUrl(taxIdentifier = taxIdentifier, authProfileOption = Some(authProfile), activeOnly = true)

    implicit val reads: Reads[ActiveRelationship] = ActiveRelationship.hipReads

    PAV HERE ...

    HttpReads.ask.flatMap { t =>
      import HttpErrorFunctions._
      val (method: String, url: String, response: HttpResponse) = t

      response.status match {
        case status if is2xx(status) => HttpReads[ActiveRelationship].read(method, url, response)
        case Status.NOT_FOUND => HttpReads.pure(None)
        case other =>
          throw UpstreamErrorResponse(
            message    = upstreamResponseMessage(method, url, other, response.body),
            statusCode = other,
            reportAs   = if (is4xx(other)) Status.INTERNAL_SERVER_ERROR else Status.BAD_GATEWAY,
            headers    = response.headers
          )
      }

    }


    val readsNone = HttpReads[HttpResponse].map(r => r.status match {case Status.NOT_FOUND => })
    httpClient
      .get(url)
      .setHeader(hipHeaders.makeHeaders(): _*)
      .execute[Option[JsValue]]
      .map(json => (json \ "relationshipDisplayResponse").as[Seq[ActiveRelationship]].find(isActive))
      .recoverWith()
      .map {
        case Right(response) =>
          (response.json \ "relationshipDisplayResponse").as[Seq[ActiveRelationship]].find(isActive)
        case Left(errorResponse: UpstreamErrorResponse) =>
          errorResponse.statusCode match {
            case Status.BAD_REQUEST | Status.NOT_FOUND => None
            case Status.UNPROCESSABLE_ENTITY if errorResponse.getMessage.contains("suspended") =>
              None
            case _ =>
              logger.error(s"Error in HIP 'GetActiveClientRelationships' with error: ${errorResponse.getMessage}")
              // TODO WG - check - that looks so wrong to rerun any value, should be an exception
              None
          }
      }
  }

  // HIP API #EPID1521 Create/Update Agent Relationship //url and error handling is different to getActiveClientRelationships
  override def getAllRelationships(
    taxIdentifier: TaxIdentifier,
    activeOnly: Boolean
  )(implicit
    rh: RequestHeader,
    ec: ExecutionContext
  ): Future[Either[RelationshipFailureResponse, Seq[ClientRelationship]]] = {
    val url = relationshipHipUrl(taxIdentifier = taxIdentifier, None, activeOnly = activeOnly)

    implicit val reads: Reads[ClientRelationship] = ClientRelationship.hipReads

    EitherT(getRelationshipDeleteMe(s"GetAllActiveClientRelationships", url))
      .map(response => (response.json \ "relationshipDisplayResponse").as[Seq[ClientRelationship]])
      .leftMap[RelationshipFailureResponse] { errorResponse =>
        errorResponse.statusCode match {
          case Status.NOT_FOUND => RelationshipFailureResponse.RelationshipNotFound
          case Status.UNPROCESSABLE_ENTITY if errorResponse.getMessage.contains("009") =>
            RelationshipFailureResponse.RelationshipNotFound
          case Status.UNPROCESSABLE_ENTITY if errorResponse.getMessage.contains("suspended") =>
            RelationshipFailureResponse.RelationshipSuspended
          case Status.BAD_REQUEST =>
            logger.error(s"Error in HIP 'GetActiveClientRelationships' with error: ${errorResponse.getMessage}")
            RelationshipFailureResponse.RelationshipBadRequest
          case s =>
            logger.error(s"Error in HIP 'GetActiveClientRelationships' with error: ${errorResponse.getMessage}")
            RelationshipFailureResponse.ErrorRetrievingRelationship(
              s,
              s"Error in HIP 'GetActiveClientRelationships' with error: ${errorResponse.getMessage}"
            )
        }
      }
      .value
  }

  // HIP API #EPID1521 Create/Update Agent Relationship
  def getInactiveClientRelationships(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit rh: RequestHeader, ec: ExecutionContext): Future[Seq[InactiveRelationship]] = {

    val authProfile = getAuthProfile(service.id)
    val url =
      relationshipHipUrl(taxIdentifier = taxIdentifier, authProfileOption = Some(authProfile), activeOnly = false)
    implicit val reads: Reads[InactiveRelationship] = InactiveRelationship.hipReads

    getRelationshipDeleteMe(s"GetInactiveClientRelationships", url)
      .map {
        case Right(response) =>
          (response.json \ "relationshipDisplayResponse").as[Seq[InactiveRelationship]].filter(isNotActive)
        case Left(errorResponse) =>
          errorResponse.statusCode match {
            case Status.BAD_REQUEST | Status.NOT_FOUND => Seq.empty[InactiveRelationship]
            case Status.UNPROCESSABLE_ENTITY if errorResponse.getMessage.contains("suspended") =>
              Seq.empty[InactiveRelationship]
            case _ =>
              logger.error(s"Error in HIP 'GetInactiveClientRelationships' with error: ${errorResponse.getMessage}")
              // TODO WG - check - that looks so wrong to rerun any value, should be an exception
              Seq.empty[InactiveRelationship]
          }
      }
  }

  // HIP API #EPID1521 Create/Update Agent Relationship (agent)
  def getInactiveRelationships(
    arn: Arn
  )(implicit rh: RequestHeader, ec: ExecutionContext): Future[Seq[InactiveRelationship]] = {
    val encodedAgentId = UriEncoding.encodePathSegment(arn.value, "UTF-8")
    val now = LocalDate.now().toString
    val from: String = LocalDate.now().minusDays(showInactiveRelationshipsDays).toString
    val regime = "AGSV"
    implicit val reads: Reads[InactiveRelationship] = InactiveRelationship.hipReads

    val url = new URL(
      s"$baseUrl/etmp/RESTAdapter/rosm/agent-relationship?arn=$encodedAgentId&isAnAgent=true&activeOnly=false&regime=$regime&dateFrom=$from&dateTo=$now"
    )

    val cacheKey = s"${arn.value}-$now"
    agentCacheProvider.agentTrackPageCache(cacheKey) {
      getRelationshipDeleteMe(s"GetInactiveRelationships", url)
        .map {
          case Right(response) =>
            (response.json \ "relationshipDisplayResponse").as[Seq[InactiveRelationship]].filter(isNotActive)
          case Left(errorResponse) =>
            errorResponse.statusCode match {
              case Status.BAD_REQUEST | Status.NOT_FOUND => Seq.empty[InactiveRelationship]
              case Status.UNPROCESSABLE_ENTITY if errorResponse.getMessage.contains("suspended") =>
                Seq.empty[InactiveRelationship]
              case _ =>
                logger.error(s"Error in HIP 'GetInactiveRelationships' with error: ${errorResponse.getMessage}")
                // TODO WG - check - that looks so wrong to rerun any value, should be an exception
                Seq.empty[InactiveRelationship]
            }
        }
    }
  }

  private def getRelationshipDeleteMe(apiName: String, url: URL)(implicit
    rh: RequestHeader,
    ec: ExecutionContext
  ): Future[Either[UpstreamErrorResponse, HttpResponse]] =
    monitor(s"ConsumedAPI-HIP-$apiName-GET") {
      httpClient
        .get(url)
        .setHeader(hipHeaders.makeHeaders(): _*)
        .execute[Either[UpstreamErrorResponse, HttpResponse]]
    }

  private def postRelationshipDeleteMe(apiName: String, url: URL, body: JsValue)(implicit
    rh: RequestHeader,
    ec: ExecutionContext
  ): Future[Either[UpstreamErrorResponse, HttpResponse]] =
    monitor(s"ConsumedAPI-HIP-$apiName-POST") {
      httpClient
        .post(url)
        .setHeader(hipHeaders.makeHeaders(): _*)
        .withBody(body)
        .execute[Either[UpstreamErrorResponse, HttpResponse]]
    }

  private[connectors] def isActive(r: ActiveRelationship): Boolean = r.dateTo match {
    case None    => true
    case Some(d) => d.isAfter(Instant.now().atZone(ZoneOffset.UTC).toLocalDate)
  }

  private[connectors] def isNotActive(r: InactiveRelationship): Boolean = r.dateTo match {
    case None => false
    case Some(d) =>
      d.isBefore(Instant.now().atZone(ZoneOffset.UTC).toLocalDate) || d.equals(
        Instant.now().atZone(ZoneOffset.UTC).toLocalDate
      )
  }

  private def relationshipHipUrl(
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
        s"&dateFrom=$from&dateTo=$now"
      }

    val authProfileParam = authProfileOption.map(x => s"&authProfile=$x").getOrElse("")

    val encodedClientId = UriEncoding.encodePathSegment(taxIdentifier.value, "UTF-8")

    taxIdentifier match {
      case MtdItId(_) =>
        new URL(
          s"$baseUrl/etmp/RESTAdapter/rosm/agent-relationship?refNumber=$encodedClientId&isAnAgent=false&activeOnly=$activeOnly&regime=${getRegimeFor(taxIdentifier)}$dateRangeParams&relationshipType=ZA01$authProfileParam"
        )
      case Vrn(_) =>
        new URL(
          s"$baseUrl/etmp/RESTAdapter/rosm/agent-relationship?idType=VRN&refNumber=$encodedClientId&isAnAgent=false&activeOnly=$activeOnly&regime=${getRegimeFor(taxIdentifier)}$dateRangeParams&relationshipType=ZA01$authProfileParam"
        )
      case Utr(_) =>
        new URL(
          s"$baseUrl/etmp/RESTAdapter/rosm/agent-relationship?idType=UTR&refNumber=$encodedClientId&isAnAgent=false&activeOnly=$activeOnly&regime=${getRegimeFor(taxIdentifier)}$dateRangeParams"
        )
      case Urn(_) =>
        new URL(
          s"$baseUrl/etmp/RESTAdapter/rosm/agent-relationship?idType=URN&refNumber=$encodedClientId&isAnAgent=false&activeOnly=$activeOnly&regime=${getRegimeFor(taxIdentifier)}$dateRangeParams"
        )
      case CgtRef(_) =>
        new URL(
          s"$baseUrl/etmp/RESTAdapter/rosm/agent-relationship?idType=ZCGT&refNumber=$encodedClientId&isAnAgent=false&activeOnly=$activeOnly&regime=${getRegimeFor(taxIdentifier)}$dateRangeParams&relationshipType=ZA01$authProfileParam"
        )
      case PptRef(_) =>
        new URL(
          s"$baseUrl/etmp/RESTAdapter/rosm/agent-relationship?idType=ZPPT&refNumber=$encodedClientId&isAnAgent=false&activeOnly=$activeOnly&regime=${getRegimeFor(taxIdentifier)}$dateRangeParams&relationshipType=ZA01$authProfileParam"
        )
      case CbcId(_) =>
        new URL(
          s"$baseUrl/etmp/RESTAdapter/rosm/agent-relationship?idType=CBC&refNumber=$encodedClientId&isAnAgent=false&activeOnly=$activeOnly&regime=${getRegimeFor(taxIdentifier)}$dateRangeParams"
        )
      case PlrId(_) =>
        new URL(
          s"$baseUrl/etmp/RESTAdapter/rosm/agent-relationship?idType=ZPLR&refNumber=$encodedClientId&isAnAgent=false&activeOnly=$activeOnly&regime=${getRegimeFor(taxIdentifier)}$dateRangeParams"
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

  private def createAgentRelationshipHipInputJson(enrolmentKey: EnrolmentKey, arn: String, isExclusiveAgent: Boolean) =
    includeIdTypeIfNeeded(enrolmentKey)(
      Json
        .parse(s"""{
          "arn": "$arn",
          "refNumber": "${enrolmentKey.oneIdentifier().value}",
          "regime": "${getRegimeFor(enrolmentKey.oneTaxIdentifier())}",
          "action": "0001",
          "isExclusiveAgent": $isExclusiveAgent
       }""")
        .as[JsObject]
    )

  private def deleteAgentRelationshipInputJson(enrolmentKey: EnrolmentKey, arn: String, isExclusiveAgent: Boolean) =
    includeIdTypeIfNeeded(enrolmentKey)(
      Json
        .parse(s"""{
          "refNumber": "${enrolmentKey.oneIdentifier().value}",
          "arn": "$arn",
          "regime": "${getRegimeFor(enrolmentKey.oneTaxIdentifier())}",
          "action": "0002",
          "isExclusiveAgent": $isExclusiveAgent
     }""")
        .as[JsObject]
    )

  private def getAuthProfile(service: String): String = service match {
    case HMRCMTDITSUPP => "ITSAS001"
    case _             => "ALL00001"
  }

  private def isExclusiveAgent(service: String): Boolean = service match {
    case HMRCMTDITSUPP => false
    case _             => true
  }

  private val includeIdTypeIfNeeded: EnrolmentKey => JsObject => JsObject = (enrolmentKey: EnrolmentKey) => { request: JsObject =>
    val idType = "idType"
    val authProfile = "authProfile"
    val relationshipType = "relationshipType"

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

}
