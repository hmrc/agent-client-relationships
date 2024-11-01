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

package uk.gov.hmrc.agentclientrelationships.services

import cats.data.EitherT
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.ClientDetailsConnector
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientStatus._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.KnownFactType._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.cgt.CgtSubscriptionDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa.{ItsaBusinessDetails, ItsaCitizenDetails}
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.vat.VatCustomerDetails
import uk.gov.hmrc.http.HeaderCarrier

import java.time.LocalDate
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ClientDetailsService @Inject() (clientDetailsConnector: ClientDetailsConnector, appConfig: AppConfig)(implicit
  ec: ExecutionContext
) extends Logging {

  def findClientDetails(service: String, clientId: String)(implicit
    hc: HeaderCarrier
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    service.toUpperCase match {
      case "HMRC-MTD-IT" =>
        getItsaClientDetails(clientId)
      case "HMRC-MTD-VAT" | "HMCE-VATDEC-ORG" =>
        getVatClientDetails(clientId)
      case "HMRC-TERS-ORG" | "HMRC-TERSNT-ORG" =>
        getTrustClientDetails(clientId)
      case "IR-SA" =>
        getIrvClientDetails(clientId)
      case "HMRC-CGT-PD" =>
        getCgtClientDetails(clientId)
      case "HMRC-PPT-ORG" =>
        getPptClientDetails(clientId)
      case "HMRC-CBC-ORG" | "HMRC-CBC-NONUK-ORG" =>
        getCbcClientDetails(clientId)
      case "HMRC-PILLAR2-ORG" =>
        getPillar2ClientDetails(clientId)
    }

  private def getItsaClientDetails(
    nino: String
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    clientDetailsConnector.getItsaBusinessDetails(nino).flatMap {
      case Right(details @ ItsaBusinessDetails(name, Some(postcode), _)) =>
        Future
          .successful(Right(ClientDetailsResponse(name, None, details.isOverseas, Seq(postcode), Some(PostalCode))))
      case Right(_) =>
        logger.warn("[getItsaClientDetails] - No postcode was returned by the API")
        Future.successful(Left(ClientDetailsNotFound))
      case Left(ClientDetailsNotFound) if appConfig.altItsaEnabled =>
        (for {
          optName     <- EitherT(clientDetailsConnector.getItsaCitizenDetails(nino)).map(_.name)
          optPostcode <- EitherT(clientDetailsConnector.getItsaDesignatoryDetails(nino)).map(_.postCode)
        } yield (optName, optPostcode)).subflatMap {
          case (Some(name), Some(postcode)) =>
            Right(ClientDetailsResponse(name, None, isOverseas = false, Seq(postcode), Some(PostalCode)))
          case _ =>
            Left(ClientDetailsNotFound)
        }.value
      case Left(err) =>
        Future.successful(Left(err))
    }

  private def getVatClientDetails(
    vrn: String
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    clientDetailsConnector.getVatCustomerInfo(vrn).map {
      case Right(VatCustomerDetails(None, None, None, _, _, _)) =>
        logger.warn("[getVatClientDetails] - No name was returned by the API")
        Left(ClientDetailsNotFound)
      case Right(details @ VatCustomerDetails(_, _, _, Some(regDate), _, _)) =>
        val clientName = details.tradingName.getOrElse(details.organisationName.getOrElse(details.individual.get.name))
        val clientStatus = if (details.isInsolvent) Some(Insolvent) else None
        Right(ClientDetailsResponse(clientName, clientStatus, details.isOverseas, Seq(regDate.toString), Some(Date)))
      case Right(_) =>
        logger.warn("[getVatClientDetails] - No registration date was returned by the API")
        Left(ClientDetailsNotFound)
      case Left(err) => Left(err)
    }

  private def getTrustClientDetails(
    trustTaxIdentifier: String
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    clientDetailsConnector.getTrustName(trustTaxIdentifier).map {
      case Right(name) =>
        Right(ClientDetailsResponse(name, None, isOverseas = false, Seq(), None))
      case Left(err) => Left(err)
    }

  private def getIrvClientDetails(
    nino: String
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    clientDetailsConnector.getItsaCitizenDetails(nino).map {
      case Right(details @ ItsaCitizenDetails(_, _, Some(dateOfBirth))) =>
        details.name match {
          case Some(name) =>
            Right(ClientDetailsResponse(name, None, isOverseas = false, Seq(dateOfBirth.toString), Some(Date)))
          case None =>
            logger.warn("[getIrvClientDetails] - No name was returned by the API")
            Left(ClientDetailsNotFound)
        }
      case Right(_) =>
        logger.warn("[getIrvClientDetails] - No date of birth was returned by the API")
        Left(ClientDetailsNotFound)
      case Left(err) => Left(err)
    }

  private def getCgtClientDetails(
    cgtRef: String
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    clientDetailsConnector.getCgtSubscriptionDetails(cgtRef).map {
      case Right(CgtSubscriptionDetails(name, Some(postcode), countryCode)) if countryCode.toUpperCase == "GB" =>
        Right(ClientDetailsResponse(name, None, isOverseas = false, Seq(postcode), Some(PostalCode)))
      case Right(CgtSubscriptionDetails(name, _, countryCode)) =>
        Right(ClientDetailsResponse(name, None, isOverseas = true, Seq(countryCode), Some(CountryCode)))
      case Left(err) => Left(err)
    }

  private def getPptClientDetails(
    pptRef: String
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    clientDetailsConnector.getPptSubscriptionDetails(pptRef).map {
      case Right(details) =>
        val isDeregistered = details.deregistrationDate.exists(deregDate => deregDate.isBefore(LocalDate.now))
        val status = if (isDeregistered) Some(Deregistered) else None
        Right(
          ClientDetailsResponse(
            details.customerName,
            status,
            isOverseas = false,
            Seq(details.dateOfApplication.toString),
            Some(Date)
          )
        )
      case Left(err) => Left(err)
    }

  private def getCbcClientDetails(
    cbcId: String
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    clientDetailsConnector.getCbcSubscriptionDetails(cbcId).map {
      case Right(details) =>
        (details.anyAvailableName, details.emails.nonEmpty) match {
          case (Some(name), true) =>
            Right(ClientDetailsResponse(name, None, !details.isGBUser, details.emails, Some(Email)))
          case _ =>
            logger.warn("[getCbcClientDetails] - Necessary client name and/or email data was missing")
            Left(ClientDetailsNotFound)
        }
      case Left(err) => Left(err)
    }

  private def getPillar2ClientDetails(
    plrId: String
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    clientDetailsConnector.getPillar2SubscriptionDetails(plrId).map {
      case Right(details) =>
        val status = if (details.inactive) Some(Inactive) else None
        val isOverseas = details.countryCode.toUpperCase != "GB"
        Right(
          ClientDetailsResponse(details.organisationName, status, isOverseas, Seq(details.registrationDate), Some(Date))
        )
      case Left(err) => Left(err)
    }
}