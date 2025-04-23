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
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.pillar2.Pillar2Record
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.cbc._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.cgt.CgtSubscriptionDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ppt.PptSubscriptionDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.vat.VatCustomerDetails
import uk.gov.hmrc.agentclientrelationships.util.HttpAPIMonitor
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{Authorization, HeaderCarrier, HeaderNames, HttpClient, HttpReads, HttpResponse}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

@Singleton
class ClientDetailsConnector @Inject() (appConfig: AppConfig, http: HttpClient, val metrics: Metrics)(implicit
  val ec: ExecutionContext
) extends HttpAPIMonitor
    with Logging {

  private def desOrIFHeaders(authToken: String, env: String, isInternalHost: Boolean)(implicit
    hc: HeaderCarrier
  ): Seq[(String, String)] = {
    val additionalHeaders =
      if (isInternalHost) Seq.empty
      else
        Seq(
          HeaderNames.authorisation -> s"Bearer $authToken",
          HeaderNames.xRequestId    -> hc.requestId.map(_.value).getOrElse(UUID.randomUUID().toString)
        ) ++ hc.sessionId.fold(Seq.empty[(String, String)])(x => Seq(HeaderNames.xSessionId -> x.value))
    val commonHeaders = Seq("Environment" -> env, "CorrelationId" -> UUID.randomUUID().toString)
    commonHeaders ++ additionalHeaders
  }

  def getItsaDesignatoryDetails(
    nino: String
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, ItsaDesignatoryDetails]] =
    monitor(s"ConsumedAPI-DesignatoryDetails-GET") {
      val url = s"${appConfig.citizenDetailsBaseUrl}/citizen-details/$nino/designatory-details"
      http.GET[HttpResponse](url).map { response =>
        response.status match {
          case OK        => Right(response.json.as[ItsaDesignatoryDetails])
          case NOT_FOUND => Left(ClientDetailsNotFound)
          case status =>
            logger.warn(s"Unexpected error during 'getItsaDesignatoryDetails', statusCode=$status")
            Left(ErrorRetrievingClientDetails(status, "Unexpected error during 'getItsaDesignatoryDetails'"))
        }
      }
    }

  def getItsaCitizenDetails(
    nino: String
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, ItsaCitizenDetails]] =
    monitor(s"ConsumedAPI-CitizenDetails-GET") {
      val url = s"${appConfig.citizenDetailsBaseUrl}/citizen-details/nino/$nino"
      http.GET[HttpResponse](url).map { response =>
        response.status match {
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
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, VatCustomerDetails]] = {

    val url = new URL(s"${appConfig.desUrl}/vat/customer/vrn/$vrn/information")

    val isInternal = appConfig.internalHostPatterns.exists(_.pattern.matcher(url.getHost).matches())

    monitor("ConsumedAPI-DES-GetVatCustomerInformation-GET") {
      http
        .GET(url, headers = desOrIFHeaders(appConfig.desToken, appConfig.desEnv, isInternal))(
          implicitly[HttpReads[HttpResponse]],
          if (isInternal) hc.copy(authorization = Some(Authorization(s"Bearer ${appConfig.desToken}"))) else hc,
          ec
        )
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
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, String]] = {

    val utrPattern = "^\\d{10}$"
    val identifierType = if (trustTaxIdentifier.matches(utrPattern)) "UTR" else "URN"
    val url = new URL(
      s"${appConfig.ifPlatformBaseUrl}/trusts/agent-known-fact-check/$identifierType/$trustTaxIdentifier"
    )

    val isInternal = appConfig.internalHostPatterns.exists(_.pattern.matcher(url.getHost).matches())

    monitor("ConsumedAPI-IF-GetTrustName-GET") {
      http
        .GET(url, headers = desOrIFHeaders(appConfig.ifAPI1495Token, appConfig.ifEnvironment, isInternal))(
          implicitly[HttpReads[HttpResponse]],
          if (isInternal) hc.copy(authorization = Some(Authorization(s"Bearer ${appConfig.ifAPI1495Token}"))) else hc,
          ec
        )
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
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, CgtSubscriptionDetails]] = {

    val url = new URL(s"${appConfig.desUrl}/subscriptions/CGT/ZCGT/$cgtRef")

    val isInternal = appConfig.internalHostPatterns.exists(_.pattern.matcher(url.getHost).matches())

    monitor("ConsumedAPI-DES-GetCgtSubscriptionDetails-GET") {
      http
        .GET(url, headers = desOrIFHeaders(appConfig.desToken, appConfig.desEnv, isInternal))(
          implicitly[HttpReads[HttpResponse]],
          if (isInternal) hc.copy(authorization = Some(Authorization(s"Bearer ${appConfig.desToken}"))) else hc,
          ec
        )
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
  }

  // API#1712 Get PPT Subscription Display
  def getPptSubscriptionDetails(
    pptRef: String
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, PptSubscriptionDetails]] = {

    val url = new URL(s"${appConfig.ifPlatformBaseUrl}/plastic-packaging-tax/subscriptions/PPT/$pptRef/display")

    val isInternal = appConfig.internalHostPatterns.exists(_.pattern.matcher(url.getHost).matches())

    monitor("ConsumedAPI-IF-GetPptSubscriptionDetails-GET") {
      http
        .GET(url, headers = desOrIFHeaders(appConfig.ifAPI1712Token, appConfig.ifEnvironment, isInternal))(
          implicitly[HttpReads[HttpResponse]],
          if (isInternal) hc.copy(authorization = Some(Authorization(s"Bearer ${appConfig.ifAPI1712Token}"))) else hc,
          ec
        )
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
  }

  // DCT 50d
  def getCbcSubscriptionDetails(
    cbcId: String
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, SimpleCbcSubscription]] = {
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
      http
        .POST[DisplaySubscriptionForCBCRequest, HttpResponse](
          appConfig.eisBaseUrl + "/dac6/dct50d/v1",
          request,
          headers = httpHeaders
        )
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
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, Pillar2Record]] = {

    val url = new URL(s"${appConfig.ifPlatformBaseUrl}/pillar2/subscription/$plrId")

    val isInternal = appConfig.internalHostPatterns.exists(_.pattern.matcher(url.getHost).matches())

    monitor("ConsumedAPI-IF-GetPillar2SubscriptionDetails-GET") {
      http
        .GET(url, headers = desOrIFHeaders(appConfig.ifAPI2143Token, appConfig.ifEnvironment, isInternal))(
          implicitly[HttpReads[HttpResponse]],
          if (isInternal) hc.copy(authorization = Some(Authorization(s"Bearer ${appConfig.ifAPI2143Token}"))) else hc,
          ec
        )
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
