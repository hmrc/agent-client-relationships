/*
 * Copyright 2025 HM Revenue & Customs
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

import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import play.api.libs.json.Json
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.CitizenDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.cbc._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.cgt.CgtSubscriptionDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.pillar2.Pillar2Record
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ppt.PptSubscriptionDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.vat.VatCustomerDetails
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.util.ConsumesAPI
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport.hc
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.HeaderNames
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.client.HttpClientV2

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class ClientDetailsConnector @Inject() (
  appConfig: AppConfig,
  httpClient: HttpClientV2
)(implicit val ec: ExecutionContext)
extends RequestAwareLogging {

  private def desHeaders(authToken: String): Seq[(String, String)] = Seq(
    "Environment" -> appConfig.desEnv,
    // TODO: The correlationId is used to link our request with the corresponding request received in DES/IF/HIP. Without logging, it would be impossible to associate these requests.
    "CorrelationId" -> UUID.randomUUID().toString,
    HeaderNames.authorisation -> s"Bearer $authToken"
  )

  private def ifHeaders(authToken: String): Seq[(String, String)] = Seq(
    "Environment" -> appConfig.ifsEnvironment,
    // TODO: The correlationId is used to link our request with the corresponding request received in DES/IF/HIP. Without logging, it would be impossible to associate these requests.
    "CorrelationId" -> UUID.randomUUID().toString,
    HeaderNames.authorisation -> s"Bearer $authToken"
  )

  @ConsumesAPI(apiId = "CD01", service = "citizen-details")
  def getItsaDesignatoryDetails(
    nino: NinoWithoutSuffix
  )(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, ItsaDesignatoryDetails]] = {
    // Designatory details API requires a NINO with suffix but does not use it when calling backend systems
    val url = url"${appConfig.citizenDetailsBaseUrl}/citizen-details/${nino.anySuffixValue}/designatory-details"
    httpClient
      .get(url)
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case OK => Right(response.json.as[ItsaDesignatoryDetails])
          case NOT_FOUND => Left(ClientDetailsNotFound)
          case status =>
            logger.warn(s"Unexpected error during 'getItsaDesignatoryDetails', statusCode=$status")
            Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getItsaDesignatoryDetails'"))
        }
      }
  }

  @ConsumesAPI(apiId = "CD03", service = "citizen-details")
  def getItsaCitizenDetails(
    nino: NinoWithoutSuffix
  )(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, CitizenDetails]] = httpClient
    .get(url"${appConfig.citizenDetailsBaseUrl}/citizen-details/nino-no-suffix/${nino.value}")
    .execute[HttpResponse]
    .map { response =>
      response.status match {
        case OK => Right(response.json.as[CitizenDetails])
        case NOT_FOUND => Left(ClientDetailsNotFound)
        case status =>
          logger.warn(s"Unexpected error during 'getItsaCitizenDetails', statusCode=$status")
          Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getItsaCitizenDetails'"))
      }

    }

  @ConsumesAPI(apiId = "DES09", service = "des")
  def getVatCustomerInfo(
    vrn: String
  )(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, VatCustomerDetails]] = {
    val url = url"${appConfig.desUrl}/vat/customer/vrn/$vrn/information"
    httpClient
      .get(url)
      .setHeader(desHeaders(appConfig.desToken): _*)
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case OK => Right(response.json.as[VatCustomerDetails])
          case NOT_FOUND => Left(ClientDetailsNotFound)
          case status =>
            logger.warn(s"Unexpected error during 'getVatCustomerInfo', statusCode=$status")
            Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getVatCustomerInfo'"))
        }

      }
  }

  // API#1495 Agent Trust Known Facts
  @ConsumesAPI(apiId = "IF05", service = "if")
  def getTrustName(
    trustTaxIdentifier: String
  )(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, String]] = {

    val utrPattern = "^\\d{10}$"
    val identifierType =
      if (trustTaxIdentifier.matches(utrPattern))
        "UTR"
      else
        "URN"
    httpClient
      .get(url"${appConfig.ifsPlatformBaseUrl}/trusts/agent-known-fact-check/$identifierType/$trustTaxIdentifier")
      .setHeader(ifHeaders(appConfig.ifsAPI1495Token): _*)
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case OK => Right((response.json \ "trustDetails" \ "trustName").as[String])
          case NOT_FOUND => Left(ClientDetailsNotFound)
          case status =>
            logger.warn(s"Unexpected error during 'getTrustName', statusCode=$status")
            Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getTrustName'"))
        }
      }

  }

  @ConsumesAPI(apiId = "DES05", service = "des")
  def getCgtSubscriptionDetails(
    cgtRef: String
  )(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, CgtSubscriptionDetails]] = httpClient
    .get(url"${appConfig.desUrl}/subscriptions/CGT/ZCGT/$cgtRef")
    .setHeader(desHeaders(appConfig.desToken): _*)
    .execute[HttpResponse]
    .map { response =>
      response.status match {
        case OK => Right(response.json.as[CgtSubscriptionDetails])
        case NOT_FOUND => Left(ClientDetailsNotFound)
        case status =>
          logger.warn(s"Unexpected error during 'getCgtSubscriptionDetails', statusCode=$status")
          Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getCgtSubscriptionDetails'"))
      }

    }

  // API#1712 Get PPT Subscription Display
  @ConsumesAPI(apiId = "IF03", service = "if")
  def getPptSubscriptionDetails(
    pptRef: String
  )(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, PptSubscriptionDetails]] = httpClient
    .get(url"${appConfig.ifsPlatformBaseUrl}/plastic-packaging-tax/subscriptions/PPT/$pptRef/display")
    .setHeader(ifHeaders(appConfig.ifsAPI1712Token): _*)
    .execute[HttpResponse]
    .map { response =>
      response.status match {
        case OK => Right(response.json.as[PptSubscriptionDetails])
        case NOT_FOUND => Left(ClientDetailsNotFound)
        case status =>
          logger.warn(s"Unexpected error during 'getPptSubscriptionDetails', statusCode=$status")
          Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getPptSubscriptionDetails'"))
      }

    }

  // DCT 50d
  @ConsumesAPI(apiId = "IF06", service = "if")
  def getCbcSubscriptionDetails(
    cbcId: String
  )(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, SimpleCbcSubscription]] = {
    val conversationId = hc.sessionId.map(_.value.drop(8)).getOrElse(UUID.randomUUID().toString)

    val request = DisplaySubscriptionForCBCRequest(displaySubscriptionForCBCRequest =
      DisplaySubscriptionDetails(
        requestCommon = RequestCommonForSubscription().copy(conversationID = Some(conversationId)),
        requestDetail = ReadSubscriptionRequestDetail(IDType = "CBC", IDNumber = cbcId)
      )
    )

    val httpHeaders = Seq(
      HeaderNames.authorisation -> s"Bearer ${appConfig.ifAuthToken}",
      "x-forwarded-host" -> "mdtp",
      "x-correlation-id" -> UUID.randomUUID().toString,
      "x-conversation-id" -> conversationId,
      "date" -> ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss O")),
      "content-type" -> "application/json",
      "accept" -> "application/json",
      "Environment" -> appConfig.ifEnvironment
    )

    httpClient
      .post(url"${appConfig.ifBaseUrl}/dac6/dct50d/v1")
      .withBody(Json.toJson(request))
      .setHeader(httpHeaders: _*)
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case OK => Right(response.json.as[SimpleCbcSubscription])
          case NOT_FOUND => Left(ClientDetailsNotFound)
          case status =>
            logger.warn(s"Unexpected error during 'getCbcSubscriptionDetails', statusCode=$status")
            Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getCbcSubscriptionDetails'"))
        }
      }

  }

  @ConsumesAPI(apiId = "IF04", service = "if")
  def getPillar2SubscriptionDetails(
    plrId: String
  )(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, Pillar2Record]] = {
    val url = url"${appConfig.ifsPlatformBaseUrl}/pillar2/subscription/$plrId"
    httpClient
      .get(url)
      .setHeader(ifHeaders(appConfig.ifsAPI2143Token): _*)
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case OK => Right(response.json.as[Pillar2Record])
          case NOT_FOUND => Left(ClientDetailsNotFound)
          case status =>
            logger.warn(s"Unexpected error during 'getPillar2SubscriptionDetails', statusCode=$status")
            Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getPillar2SubscriptionDetails'"))
        }

      }
  }

}
