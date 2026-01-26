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
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import play.api.http.Status
import play.api.libs.json._
import play.api.mvc.RequestHeader
import play.utils.UriEncoding
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.helpers.HipHeaders
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientDetailsFailureResponse
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientDetailsNotFound
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ErrorRetrievingClientDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa.ItsaBusinessDetails
import uk.gov.hmrc.agentclientrelationships.model.stride.ClientRelationship
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport._
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2

import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

@Singleton
class HipConnector @Inject() (
  httpClient: HttpClientV2,
  headers: HipHeaders,
  appConfig: AppConfig
)(implicit
  val ec: ExecutionContext
)
extends RequestAwareLogging {

  private val baseUrl = appConfig.hipPlatformBaseUrl

  // HIP API #EPID1521 Create/Update Agent Relationship
  def createAgentRelationship(
    enrolmentKey: EnrolmentKey,
    arn: Arn
  )(implicit request: RequestHeader): Future[RegistrationRelationshipResponse] = {

    val url = new URL(s"$baseUrl/etmp/RESTAdapter/rosm/agent-relationship")
    val isExclusiveAgent = getIsExclusiveAgent(enrolmentKey.service)
    val requestBody = createAgentRelationshipHipInputJson(
      enrolmentKey,
      arn.value,
      isExclusiveAgent
    )

    postWithHipHeaders(
      "CreateAgentRelationship",
      url,
      requestBody,
      () => headers.makeSubscriptionHeaders()
    ).map {
      case Right(response) => response.json.as[RegistrationRelationshipResponse]
      case Left(errorResponse) =>
        logger.error(s"Error in HIP 'CreateAgentRelationship' with error: ${errorResponse.getMessage}")
        throw errorResponse
    }
  }

  // HIP API #EPID1521 Create/Update Agent Relationship
  def deleteAgentRelationship(
    enrolmentKey: EnrolmentKey,
    arn: Arn
  )(implicit request: RequestHeader): Future[Option[RegistrationRelationshipResponse]] = {

    val url = new URL(s"$baseUrl/etmp/RESTAdapter/rosm/agent-relationship")
    val isExclusiveAgent = getIsExclusiveAgent(enrolmentKey.service)
    val requestBody = deleteAgentRelationshipInputJson(
      enrolmentKey,
      arn.value,
      isExclusiveAgent
    )

    postWithHipHeaders(
      "DeleteAgentRelationship",
      url,
      requestBody,
      () => headers.makeSubscriptionHeaders()
    ).map {
      case Right(response) => Some(response.json.as[RegistrationRelationshipResponse])
      case Left(ex: UpstreamErrorResponse) if ex.getMessage.contains("No active relationship found") => None
      case Left(errorResponse) =>
        logger.error(s"Error in HIP 'DeleteAgentRelationship' with error: ${errorResponse.getMessage}")
        throw errorResponse
    }
  }

  // HIP API #EPID1521 Create/Update Agent Relationship
  def getActiveClientRelationships(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit request: RequestHeader): Future[Option[ActiveRelationship]] = {
    val authProfile = getAuthProfile(service.id)
    val url = relationshipHipUrl(
      taxIdentifier = taxIdentifier,
      authProfileOption = Some(authProfile),
      activeOnly = true
    )

    implicit val reads: Reads[ActiveRelationship] = ActiveRelationship.hipReads

    getWithHipHeaders(
      s"GetActiveClientRelationships",
      url,
      () => headers.makeSubscriptionHeaders()
    ).map {
      case Right(response) => (response.json \ "relationshipDisplayResponse").as[Seq[ActiveRelationship]].find(isActive)
      case Left(errorResponse) =>
        errorResponse.statusCode match {
          case Status.BAD_REQUEST | Status.NOT_FOUND => None
          case Status.UNPROCESSABLE_ENTITY if errorResponse.getMessage.contains("suspended") => None
          case Status.UNPROCESSABLE_ENTITY if errorResponse.getMessage.contains("009") => None
          case _ =>
            logger.error(s"Error in HIP 'GetActiveClientRelationships' with error: ${errorResponse.getMessage}")
            // TODO WG - check - that looks so wrong to rerun any value, should be an exception
            None
        }
    }
  }

  // HIP API #EPID1521 Create/Update Agent Relationship //url and error handling is different to getActiveClientRelationships
  def getAllRelationships(
    taxIdentifier: TaxIdentifier,
    activeOnly: Boolean
  )(implicit request: RequestHeader): Future[Either[RelationshipFailureResponse, Seq[ClientRelationship]]] = {
    val url = relationshipHipUrl(
      taxIdentifier = taxIdentifier,
      None,
      activeOnly = activeOnly
    )

    implicit val reads: Reads[ClientRelationship] = ClientRelationship.hipReads

    EitherT(
      getWithHipHeaders(
        s"GetAllActiveClientRelationships",
        url,
        () => headers.makeSubscriptionHeaders()
      )
    ).map(response => (response.json \ "relationshipDisplayResponse").as[Seq[ClientRelationship]])
      .leftMap[RelationshipFailureResponse] { errorResponse =>
        errorResponse.statusCode match {
          case Status.NOT_FOUND => RelationshipFailureResponse.RelationshipNotFound
          case Status.UNPROCESSABLE_ENTITY if errorResponse.getMessage.contains("009") => RelationshipFailureResponse.RelationshipNotFound
          case Status.UNPROCESSABLE_ENTITY if errorResponse.getMessage.contains("suspended") => RelationshipFailureResponse.RelationshipSuspended
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

  // API#5266 https://admin.tax.service.gov.uk/integration-hub/apis/details/e54e8843-c146-4551-a499-c93ecac4c6fd#Endpoints
  def getNinoFor(mtdId: MtdItId)(implicit request: RequestHeader): Future[Option[NinoWithoutSuffix]] = {
    val encodedMtdId = UriEncoding.encodePathSegment(mtdId.value, "UTF-8")
    val url = new URL(s"$baseUrl/etmp/RESTAdapter/itsa/taxpayer/business-details?mtdReference=$encodedMtdId")

    getWithHipHeaders(
      s"GetBusinessDetailsByMtdId",
      url,
      () => headers.makeSubscriptionBusinessDetailsHeaders()
    ).map {
      case Right(response) => Option((response.json \ "success" \ "taxPayerDisplayResponse" \ "nino").as[NinoWithoutSuffix])
      case Left(errorResponse) =>
        errorResponse.statusCode match {
          case Status.NOT_FOUND => None
          case Status.UNPROCESSABLE_ENTITY
              if errorResponse.getMessage.contains("008") | errorResponse.getMessage.contains("006") =>
            None
          case _ =>
            val msg = s"Error in HIP API#5266 'GetBusinessDetailsByMtdId ${errorResponse.getMessage()}"
            throw new RuntimeException(msg)
        }
    }
  }

  // API#5266 https://admin.tax.service.gov.uk/integration-hub/apis/details/e54e8843-c146-4551-a499-c93ecac4c6fd#Endpoints
  def getMtdIdFor(nino: NinoWithoutSuffix)(implicit request: RequestHeader): Future[Option[MtdItId]] = {
    val encodedNino = UriEncoding.encodePathSegment(nino.value, "UTF-8")
    val url = new URL(s"$baseUrl/etmp/RESTAdapter/itsa/taxpayer/business-details?nino=$encodedNino")

    getWithHipHeaders(
      s"GetBusinessDetailsByNino",
      url,
      () => headers.makeSubscriptionBusinessDetailsHeaders()
    ).map {
      case Right(response) => Option((response.json \ "success" \ "taxPayerDisplayResponse" \ "mtdId").as[MtdItId])
      case Left(errorResponse) =>
        errorResponse.statusCode match {
          case Status.NOT_FOUND => None
          case Status.UNPROCESSABLE_ENTITY
              if errorResponse.getMessage.contains("008") | errorResponse.getMessage.contains("006") =>
            None
          case _ =>
            val msg = s"Error in HIP API#5266 'GetBusinessDetailsByNino ${errorResponse.getMessage()}"
            throw new RuntimeException(msg)
        }
    }
  }

  // API#5266 https://admin.tax.service.gov.uk/integration-hub/apis/details/e54e8843-c146-4551-a499-c93ecac4c6fd#Endpoints
  def getItsaBusinessDetails(nino: NinoWithoutSuffix)(implicit
    request: RequestHeader
  ): Future[Either[ClientDetailsFailureResponse, ItsaBusinessDetails]] = {
    val encodedNino = UriEncoding.encodePathSegment(nino.value, "UTF-8")
    val url = new URL(s"$baseUrl/etmp/RESTAdapter/itsa/taxpayer/business-details?nino=$encodedNino")

    getWithHipHeaders(
      s"ConsumedAPI-IF-GetBusinessDetails-GET",
      url,
      () => headers.makeSubscriptionBusinessDetailsHeaders()
    ).map {
      case Right(response) =>
        Try(response.json \ "success" \ "taxPayerDisplayResponse" \ "businessData")
          .map(_(0))
          .map(_.as[ItsaBusinessDetails])
          .toOption
          .toRight {
            logger.warn("Unable to retrieve relevant details as the businessData array was empty")
            ClientDetailsNotFound
          }
      case Left(errorResponse) =>
        errorResponse.statusCode match {
          case Status.NOT_FOUND => Left(ClientDetailsNotFound)
          case Status.UNPROCESSABLE_ENTITY
              if errorResponse.getMessage.contains("008") | errorResponse.getMessage.contains("006") =>
            Left(ClientDetailsNotFound)
          case status =>
            logger.warn(
              s"Unexpected error during 'getItsaBusinessDetails', statusCode=$status message:${errorResponse.getMessage}"
            )
            Left(
              ErrorRetrievingClientDetails(
                status,
                s"Unexpected error during 'getItsaBusinessDetails' message:${errorResponse.getMessage}"
              )
            )
        }
    }
  }

  private def getWithHipHeaders(
    apiName: String,
    url: URL,
    getHeaders: () => Seq[(String, String)]
  )(implicit request: RequestHeader): Future[Either[UpstreamErrorResponse, HttpResponse]] = httpClient
    .get(url)
    .setHeader(getHeaders(): _*)
    .execute[Either[UpstreamErrorResponse, HttpResponse]]

  private def postWithHipHeaders(
    apiName: String,
    url: URL,
    body: JsValue,
    getHeaders: () => Seq[(String, String)]
  )(implicit
    request: RequestHeader
  ): Future[Either[UpstreamErrorResponse, HttpResponse]] = httpClient
    .post(url)
    .setHeader(getHeaders(): _*)
    .withBody(body)
    .execute[Either[UpstreamErrorResponse, HttpResponse]]

  private[connectors] def isActive(r: ActiveRelationship): Boolean =
    r.dateTo match {
      case None => true
      case Some(d) => d.isAfter(Instant.now().atZone(ZoneOffset.UTC).toLocalDate)
    }

  private[connectors] def isNotActive(r: InactiveRelationship): Boolean =
    r.dateTo match {
      case None => false
      case Some(d) =>
        d.isBefore(Instant.now().atZone(ZoneOffset.UTC).toLocalDate) ||
        d.equals(Instant.now().atZone(ZoneOffset.UTC).toLocalDate)
    }

  private def relationshipHipUrl(
    taxIdentifier: TaxIdentifier,
    authProfileOption: Option[String],
    activeOnly: Boolean
  ) = {

    val dateRangeParams =
      if (activeOnly)
        ""
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
      case Vrn(_) => "VATC"
      case Utr(_) => "TRS"
      case Urn(_) => "TRS"
      case CgtRef(_) => "CGT"
      case PptRef(_) => "PPT"
      case CbcId(_) => "CBC"
      case PlrId(_) => "PLR"
      case _ => throw new IllegalArgumentException(s"Tax identifier not supported $clientId")
    }

  private def createAgentRelationshipHipInputJson(
    enrolmentKey: EnrolmentKey,
    arn: String,
    isExclusiveAgent: Boolean
  ) =
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

  private def deleteAgentRelationshipInputJson(
    enrolmentKey: EnrolmentKey,
    arn: String,
    isExclusiveAgent: Boolean
  ) =
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

  private def getAuthProfile(service: String): String =
    service match {
      case HMRCMTDITSUPP => "ITSAS001"
      case _ => "ALL00001"
    }

  private def getIsExclusiveAgent(service: String): Boolean =
    service match {
      case HMRCMTDITSUPP => false
      case _ => true
    }

  private val includeIdTypeIfNeeded: EnrolmentKey => JsObject => JsObject =
    (enrolmentKey: EnrolmentKey) => { request =>
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
            case e => throw new Exception(s"unsupported tax identifier $e for regime TRS")
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
        case Some("CBC") => request + ((idType, JsString("CBC")))
        case Some("PLR") =>
          request +
            ((relationshipType, JsString("ZA01"))) +
            ((authProfile, JsString(authProfileForService))) +
            ((idType, JsString("ZPLR")))
        case _ => request
      }
    }

}
