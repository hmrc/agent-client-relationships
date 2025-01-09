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
import play.api.libs.json._
import play.utils.UriEncoding
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.helpers.HIPHeaders
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentKey, _}
import uk.gov.hmrc.agentclientrelationships.services.AgentCacheProvider
import uk.gov.hmrc.agentclientrelationships.util.HttpAPIMonitor
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.net.URL
import java.time.{Instant, LocalDate, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

trait RelationshipConnector {
  def createAgentRelationship(enrolmentKey: EnrolmentKey, arn: Arn)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[RegistrationRelationshipResponse]]

  def deleteAgentRelationship(enrolmentKey: EnrolmentKey, arn: Arn)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[RegistrationRelationshipResponse]]

  def getActiveClientRelationships(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[ActiveRelationship]]

  def getInactiveClientRelationships(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[InactiveRelationship]]

  def getInactiveRelationships(
    arn: Arn
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[InactiveRelationship]]
}

@Singleton
class HIPConnector @Inject() (
  httpClient: HttpClientV2,
  agentCacheProvider: AgentCacheProvider,
  headers: HIPHeaders,
  val ec: ExecutionContext
)(implicit
  val metrics: Metrics,
  val appConfig: AppConfig
) extends RelationshipConnector
    with HttpAPIMonitor
    with Logging {

  private val baseUrl = appConfig.hipPlatformBaseUrl
  private val showInactiveRelationshipsDays = appConfig.inactiveRelationshipShowLastDays

  //HIP API #EPID1521 Create/Update Agent Relationship
  def createAgentRelationship(enrolmentKey: EnrolmentKey, arn: Arn)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[RegistrationRelationshipResponse]] = {

    val url = new URL(s"$baseUrl/RESTAdapter/rosm/agent-relationship")
    val isExclusiveAgent = getIsExclusiveAgent(enrolmentKey.service)
    val requestBody = createAgentRelationshipHipInputJson(enrolmentKey, arn.value, isExclusiveAgent)

    postRelationship("CreateAgentRelationship", url, requestBody)
      .map {
        case Right(response) =>
          Option(response.json.as[RegistrationRelationshipResponse])
        case Left(errorResponse) =>
          errorResponse.statusCode match {
            case _ =>
              logger.error(s"Error in HIP 'CreateAgentRelationship' with error: ${errorResponse.getMessage}")
              None
          }
      }
  }

  //HIP API #EPID1521 Create/Update Agent Relationship
  def deleteAgentRelationship(enrolmentKey: EnrolmentKey, arn: Arn)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[RegistrationRelationshipResponse]] = {

    val url = new URL(s"$baseUrl/RESTAdapter/rosm/agent-relationship")
    val isExclusiveAgent = getIsExclusiveAgent(enrolmentKey.service)
    val requestBody = deleteAgentRelationshipInputJson(enrolmentKey, arn.value, isExclusiveAgent)

    postRelationship("DeleteAgentRelationship", url, requestBody)
      .map {
        case Right(response) =>
          Option(response.json.as[RegistrationRelationshipResponse])
        case Left(errorResponse) =>
          errorResponse.statusCode match {
            case _ =>
              logger.error(s"Error in HIP 'DeleteAgentRelationship' with error: ${errorResponse.getMessage}")
              None
          }
      }
  }

  //HIP API #EPID1521 Create/Update Agent Relationship
  def getActiveClientRelationships(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[ActiveRelationship]] = {
    val authProfile = getAuthProfile(service.id)
    val url = getActiveClientRelationshipsHipUrl(taxIdentifier, authProfile)

    implicit val reads: Reads[ActiveRelationship] = ActiveRelationship.hipReads

    getRelationship(s"GetActiveClientRelationships", url)
      .map {
        case Right(response) =>
          (response.json \ "relationshipDisplayResponse").as[Seq[ActiveRelationship]].find(isActive)
        case Left(errorResponse) =>
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

  //HIP API #EPID1521 Create/Update Agent Relationship
  def getInactiveClientRelationships(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[InactiveRelationship]] = {

    val encodedClientId = UriEncoding.encodePathSegment(taxIdentifier.value, "UTF-8")
    val authProfile = getAuthProfile(service.id)
    val url = inactiveClientRelationshipHipUrl(taxIdentifier, encodedClientId, authProfile)
    implicit val reads: Reads[InactiveRelationship] = InactiveRelationship.hipReads

    getRelationship(s"GetInactiveClientRelationships", url)
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

  //HIP API #EPID1521 Create/Update Agent Relationship (agent)
  def getInactiveRelationships(
    arn: Arn
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[InactiveRelationship]] = {
    val encodedAgentId = UriEncoding.encodePathSegment(arn.value, "UTF-8")
    val now = LocalDate.now().toString
    val from: String = LocalDate.now().minusDays(showInactiveRelationshipsDays).toString
    val regime = "AGSV"
    implicit val reads: Reads[InactiveRelationship] = InactiveRelationship.hipReads

    val url = new URL(
      s"$baseUrl/RESTAdapter/rosm/agent-relationship?arn=$encodedAgentId&isAnAgent=true&activeOnly=false&regime=$regime&dateFrom=$from&dateTo=$now"
    )

    val cacheKey = s"${arn.value}-$now"
    agentCacheProvider.agentTrackPageCache(cacheKey) {
      getRelationship(s"GetInactiveRelationships", url)
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

  private def getRelationship(apiName: String, url: URL)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[UpstreamErrorResponse, HttpResponse]] =
    monitor(s"ConsumedAPI-HIP-$apiName-GET") {
      httpClient
        .get(url)
        .setHeader(headers.subscriptionHeaders(): _*)
        .execute[Either[UpstreamErrorResponse, HttpResponse]]
    }

  private def postRelationship(apiName: String, url: URL, body: JsValue)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[UpstreamErrorResponse, HttpResponse]] =
    monitor(s"ConsumedAPI-HIP-$apiName-POST") {
      httpClient
        .post(url)
        .setHeader(headers.subscriptionHeaders(): _*)
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

  // TODO WG - refactor
  private def inactiveClientRelationshipHipUrl(
    taxIdentifier: TaxIdentifier,
    encodedClientId: String,
    authProfile: String
  ) = {
    val fromDateString = appConfig.inactiveRelationshipsClientRecordStartDate
    val from = LocalDate.parse(fromDateString).toString
    val now = LocalDate.now().toString
    taxIdentifier match {
      case MtdItId(_) =>
        new URL(
          s"$baseUrl/RESTAdapter/rosm/agent-relationship?refNumber=$encodedClientId&isAnAgent=false&activeOnly=false&regime=${getRegimeFor(
            taxIdentifier
          )}&dateFrom=$from&dateTo=$now&relationshipType=ZA01&authProfile=$authProfile"
        )
      case Vrn(_) =>
        new URL(
          s"$baseUrl/RESTAdapter/rosm/agent-relationship?idType=VRN&refNumber=$encodedClientId&isAnAgent=false&activeOnly=false&regime=${getRegimeFor(
            taxIdentifier
          )}&dateFrom=$from&dateTo=$now&relationshipType=ZA01&authProfile=$authProfile"
        )
      case Utr(_) =>
        new URL(
          s"$baseUrl/RESTAdapter/rosm/agent-relationship?idType=UTR&refNumber=$encodedClientId&isAnAgent=false&activeOnly=false&regime=${getRegimeFor(taxIdentifier)}&dateFrom=$from&dateTo=$now"
        )
      case Urn(_) =>
        new URL(
          s"$baseUrl/RESTAdapter/rosm/agent-relationship?idType=URN&refNumber=$encodedClientId&isAnAgent=false&activeOnly=false&regime=${getRegimeFor(taxIdentifier)}&dateFrom=$from&dateTo=$now"
        )
      case CgtRef(_) =>
        new URL(
          s"$baseUrl/RESTAdapter/rosm/agent-relationship?idType=ZCGT&refNumber=$encodedClientId&isAnAgent=false&activeOnly=false&regime=${getRegimeFor(
            taxIdentifier
          )}&dateFrom=$from&dateTo=$now&relationshipType=ZA01&authProfile=$authProfile"
        )
      case PptRef(_) =>
        new URL(
          s"$baseUrl/RESTAdapter/rosm/agent-relationship?idType=ZPPT&refNumber=$encodedClientId&isAnAgent=false&activeOnly=false&regime=${getRegimeFor(
            taxIdentifier
          )}&dateFrom=$from&dateTo=$now&relationshipType=ZA01&authProfile=$authProfile"
        )
      case CbcId(_) =>
        new URL(
          s"$baseUrl/RESTAdapter/rosm/agent-relationship?idType=CBC&refNumber=$encodedClientId&isAnAgent=false&activeOnly=false&regime=${getRegimeFor(taxIdentifier)}&dateFrom=$from&dateTo=$now"
        )
      case PlrId(_) =>
        new URL(
          s"$baseUrl/RESTAdapter/rosm/agent-relationship?idType=ZPLR&refNumber=$encodedClientId&isAnAgent=false&activeOnly=false&regime=${getRegimeFor(taxIdentifier)}&dateFrom=$from&dateTo=$now"
        )
      case _ => throw new IllegalStateException(s"Unsupported Identifier $taxIdentifier")
    }
  }

  // TODO WG - refactor
  private def getActiveClientRelationshipsHipUrl(
    taxIdentifier: TaxIdentifier,
    authProfile: String
  ): URL = {
    val encodedClientId = UriEncoding.encodePathSegment(taxIdentifier.value, "UTF-8")
    taxIdentifier match {
      case MtdItId(_) =>
        new URL(
          s"$baseUrl/RESTAdapter/rosm/agent-relationship?refNumber=$encodedClientId&isAnAgent=false&activeOnly=true&regime=${getRegimeFor(taxIdentifier)}&relationshipType=ZA01&authProfile=$authProfile"
        )
      case Vrn(_) =>
        new URL(
          s"$baseUrl/RESTAdapter/rosm/agent-relationship?idType=VRN&refNumber=$encodedClientId&isAnAgent=false&activeOnly=true&regime=${getRegimeFor(taxIdentifier)}&relationshipType=ZA01&authProfile=$authProfile"
        )
      case Utr(_) =>
        new URL(
          s"$baseUrl/RESTAdapter/rosm/agent-relationship?idType=UTR&refNumber=$encodedClientId&isAnAgent=false&activeOnly=true&regime=${getRegimeFor(taxIdentifier)}"
        )
      case Urn(_) =>
        new URL(
          s"$baseUrl/RESTAdapter/rosm/agent-relationship?idType=URN&refNumber=$encodedClientId&isAnAgent=false&activeOnly=true&regime=${getRegimeFor(taxIdentifier)}"
        )
      case CgtRef(_) =>
        new URL(
          s"$baseUrl/RESTAdapter/rosm/agent-relationship?idType=ZCGT&refNumber=$encodedClientId&isAnAgent=false&activeOnly=true&regime=${getRegimeFor(taxIdentifier)}&relationshipType=ZA01&authProfile=$authProfile"
        )
      case PptRef(_) =>
        new URL(
          s"$baseUrl/RESTAdapter/rosm/agent-relationship?idType=ZPPT&refNumber=$encodedClientId&isAnAgent=false&activeOnly=true&regime=${getRegimeFor(taxIdentifier)}&relationshipType=ZA01&authProfile=$authProfile"
        )
      case CbcId(_) =>
        new URL(
          s"$baseUrl/RESTAdapter/rosm/agent-relationship?idType=CBC&refNumber=$encodedClientId&isAnAgent=false&activeOnly=true&regime=${getRegimeFor(taxIdentifier)}"
        )
      case PlrId(_) =>
        new URL(
          s"$baseUrl/RESTAdapter/rosm/agent-relationship?idType=ZPLR&refNumber=$encodedClientId&isAnAgent=false&activeOnly=true&regime=${getRegimeFor(taxIdentifier)}"
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

  private def getIsExclusiveAgent(service: String): Boolean = service match {
    case HMRCMTDITSUPP => false
    case _             => true
  }

  private val includeIdTypeIfNeeded: EnrolmentKey => JsObject => JsObject = (enrolmentKey: EnrolmentKey) => { request =>
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
