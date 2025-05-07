/*
 * Copyright 2024 HM Revenue & Customs
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
import play.api.http.Status.{NOT_FOUND, OK}
import play.api.libs.json.Json
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.cbc._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.cgt.CgtSubscriptionDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.pillar2.Pillar2Record
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ppt.PptSubscriptionDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.vat.VatCustomerDetails
import uk.gov.hmrc.agentclientrelationships.util.HttpApiMonitor
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport.hc
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderNames, HttpResponse, StringContextOps}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

//TODO SRP violation, this connector aggregates too many endpoints from DES, IF, and EIS. Split it into separate connectors.
@Singleton
class ClientDetailsConnector @Inject() (appConfig: AppConfig, httpClient: HttpClientV2, val metrics: Metrics)(implicit
  val ec: ExecutionContext
) extends HttpApiMonitor
    with Logging {

  private def desHeaders(authToken: String): Seq[(String, String)] = Seq(
    "Environment" -> appConfig.desEnv,
    // TODO: The correlationId is used to link our request with the corresponding request received in DES/IF/HIP. Without logging, it would be impossible to associate these requests.
    "CorrelationId"           -> UUID.randomUUID().toString,
    HeaderNames.authorisation -> s"Bearer $authToken"
  )

  private def ifHeaders(authToken: String): Seq[(String, String)] = Seq(
    "Environment" -> appConfig.ifEnvironment,
    // TODO: The correlationId is used to link our request with the corresponding request received in DES/IF/HIP. Without logging, it would be impossible to associate these requests.
    "CorrelationId"           -> UUID.randomUUID().toString,
    HeaderNames.authorisation -> s"Bearer $authToken"
  )

  def getItsaDesignatoryDetails(
    nino: String
  )(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, ItsaDesignatoryDetails]] =
    monitor(s"ConsumedAPI-DesignatoryDetails-GET") {
      val url = url"${appConfig.citizenDetailsBaseUrl}/citizen-details/$nino/designatory-details"
      httpClient
        .get(url)
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case OK => Right(response.json.as[ItsaDesignatoryDetails])
            // TODO: Do we really need to handle all those cases where the status is not OK? Ultimately, if it's not OK, the user sees technical difficulties, so why bother and hide the stack trace logged by the default error handler in such cases?
            case NOT_FOUND => Left(ClientDetailsNotFound)
            case status =>
              logger.warn(s"Unexpected error during 'getItsaDesignatoryDetails', statusCode=$status")
              Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getItsaDesignatoryDetails'"))
          }
        }
    }

  def getItsaCitizenDetails(
    nino: String
  )(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, ItsaCitizenDetails]] =
    monitor(s"ConsumedAPI-CitizenDetails-GET") {
      httpClient
        .get(url"${appConfig.citizenDetailsBaseUrl}/citizen-details/nino/$nino")
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            // TODO: Do we really need to handle all those cases where the status is not OK? Ultimately, if it's not OK, the user sees technical difficulties, so why bother and hide the stack trace logged by the default error handler in such cases?
            case OK        => Right(response.json.as[ItsaCitizenDetails])
            case NOT_FOUND => Left(ClientDetailsNotFound)
            case status =>
              logger.warn(s"Unexpected error during 'getItsaCitizenDetails', statusCode=$status")
              Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getItsaCitizenDetails'"))
          }
        }
    }

  def getVatCustomerInfo(
    vrn: String
  )(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, VatCustomerDetails]] = {
    val url = url"${appConfig.desUrl}/vat/customer/vrn/$vrn/information"
    monitor("ConsumedAPI-DES-GetVatCustomerInformation-GET") {
      httpClient
        .get(url)
        .setHeader(desHeaders(appConfig.desToken): _*)
        .execute[HttpResponse]
        // TODO: easier is to do
        // .execute[Option[VatCustomerDetails]]
        // .map(_.toRight(ClientDetailsNotFound))
        // but not suer if the Left(ErrorRetrievingClientDetail) is used anywhere. Analyse and simplify
        .map { response =>
          response.status match {
            case OK        => Right(response.json.as[VatCustomerDetails])
            case NOT_FOUND => Left(ClientDetailsNotFound)
            case status =>
              logger.warn(s"Unexpected error during 'getVatCustomerInfo', statusCode=$status")
              Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getVatCustomerInfo'"))
          }
        }
    }
  }

  // API#1495 Agent Trust Known Facts
  def getTrustName(
    trustTaxIdentifier: String
  )(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, String]] = {

    val utrPattern = "^\\d{10}$"
    val identifierType = if (trustTaxIdentifier.matches(utrPattern)) "UTR" else "URN"
    monitor("ConsumedAPI-IF-GetTrustName-GET") {
      httpClient
        .get(url"${appConfig.ifPlatformBaseUrl}/trusts/agent-known-fact-check/$identifierType/$trustTaxIdentifier")
        .setHeader(ifHeaders(appConfig.ifAPI1495Token): _*)
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case OK        => Right((response.json \ "trustDetails" \ "trustName").as[String])
            case NOT_FOUND => Left(ClientDetailsNotFound)
            case status =>
              logger.warn(s"Unexpected error during 'getTrustName', statusCode=$status")
              Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getTrustName'"))
          }
        }
    }
  }

  def getCgtSubscriptionDetails(
    cgtRef: String
  )(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, CgtSubscriptionDetails]] =
    monitor("ConsumedAPI-DES-GetCgtSubscriptionDetails-GET") {
      httpClient
        .get(url"${appConfig.desUrl}/subscriptions/CGT/ZCGT/$cgtRef")
        .setHeader(desHeaders(appConfig.desToken): _*)
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case OK        => Right(response.json.as[CgtSubscriptionDetails])
            case NOT_FOUND => Left(ClientDetailsNotFound)
            case status =>
              logger.warn(s"Unexpected error during 'getCgtSubscriptionDetails', statusCode=$status")
              Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getCgtSubscriptionDetails'"))
          }
        }
    }

  // API#1712 Get PPT Subscription Display
  def getPptSubscriptionDetails(
    pptRef: String
  )(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, PptSubscriptionDetails]] =
    monitor("ConsumedAPI-IF-GetPptSubscriptionDetails-GET") {
      httpClient
        .get(url"${appConfig.ifPlatformBaseUrl}/plastic-packaging-tax/subscriptions/PPT/$pptRef/display")
        .setHeader(ifHeaders(appConfig.ifAPI1712Token): _*)
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case OK        => Right(response.json.as[PptSubscriptionDetails])
            case NOT_FOUND => Left(ClientDetailsNotFound)
            case status =>
              logger.warn(s"Unexpected error during 'getPptSubscriptionDetails', statusCode=$status")
              Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getPptSubscriptionDetails'"))
          }
        }
    }

  // DCT 50d
  def getCbcSubscriptionDetails(
    cbcId: String
  )(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, SimpleCbcSubscription]] = {
    val conversationId = hc.sessionId.map(_.value.drop(8)).getOrElse(UUID.randomUUID().toString)

    val request = DisplaySubscriptionForCBCRequest(
      displaySubscriptionForCBCRequest = DisplaySubscriptionDetails(
        requestCommon = RequestCommonForSubscription().copy(conversationID = Some(conversationId)),
        requestDetail = ReadSubscriptionRequestDetail(IDType = "CBC", IDNumber = cbcId)
      )
    )

    val httpHeaders = Seq(
      HeaderNames.authorisation -> s"Bearer ${appConfig.eisAuthToken}",
      "x-forwarded-host"        -> "mdtp",
      "x-correlation-id"        -> UUID.randomUUID().toString,
      "x-conversation-id"       -> conversationId,
      "date"         -> ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss O")),
      "content-type" -> "application/json",
      "accept"       -> "application/json",
      "Environment"  -> appConfig.eisEnvironment
    )

    monitor(s"ConsumedAPI-EIS-GetCbcSubscriptionDetails-POST") {
      httpClient
        .post(url"${appConfig.eisBaseUrl}/dac6/dct50d/v1")
        .withBody(Json.toJson(request))
        .setHeader(httpHeaders: _*)
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case OK        => Right(response.json.as[SimpleCbcSubscription])
            case NOT_FOUND => Left(ClientDetailsNotFound)
            case status =>
              logger.warn(s"Unexpected error during 'getCbcSubscriptionDetails', statusCode=$status")
              Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getCbcSubscriptionDetails'"))
          }
        }
    }
  }

  def getPillar2SubscriptionDetails(
    plrId: String
  )(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, Pillar2Record]] = {
    val url = url"${appConfig.ifPlatformBaseUrl}/pillar2/subscription/$plrId"
    monitor("ConsumedAPI-IF-GetPillar2SubscriptionDetails-GET") {
      httpClient
        .get(url)
        .setHeader(ifHeaders(appConfig.ifAPI2143Token): _*)
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case OK        => Right(response.json.as[Pillar2Record])
            case NOT_FOUND => Left(ClientDetailsNotFound)
            case status =>
              logger.warn(s"Unexpected error during 'getPillar2SubscriptionDetails', statusCode=$status")
              Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getPillar2SubscriptionDetails'"))
          }
        }
    }
  }
}
