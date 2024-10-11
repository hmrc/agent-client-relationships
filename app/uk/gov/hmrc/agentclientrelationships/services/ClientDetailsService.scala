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
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientStatus.{Deregistered, Insolvent}
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.cgt.CgtSubscriptionDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa.{ItsaBusinessDetails, ItsaCitizenDetails}
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa.ItsaCitizenDetails.citizenDateFormatter
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.vat.VatCustomerDetails
import uk.gov.hmrc.agentclientrelationships.util.PostcodeMatchUtil.{postcodeMatches, postcodeWithoutSpacesRegex}
import uk.gov.hmrc.http.HeaderCarrier

import java.time.LocalDate
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ClientDetailsService @Inject() (clientDetailsConnector: ClientDetailsConnector, appConfig: AppConfig)(implicit
  ec: ExecutionContext
) extends Logging {

  def findClientDetails(service: String, details: ClientDetailsRequest)(implicit
    hc: HeaderCarrier
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    service.toUpperCase match {
      case "HMRC-MTD-IT" =>
        getItsaClientDetails(
          details.clientDetails("nino"),
          details.clientDetails("postcode")
        )
      case "HMRC-MTD-VAT" | "HMCE-VATDEC-ORG" =>
        getVatClientDetails(
          details.clientDetails("vrn"),
          LocalDate.parse(details.clientDetails("registrationDate"))
        )
      case "HMRC-TERS-ORG" | "HMRC-TERSNT-ORG" =>
        getTrustClientDetails(
          details.clientDetails("taxId")
        )
      case "IR-SA" =>
        getIrvClientDetails(
          details.clientDetails("nino"),
          LocalDate.parse(details.clientDetails("dob"), citizenDateFormatter)
        )
      case "HMRC-CGT-PD" =>
        getCgtClientDetails(
          details.clientDetails("cgtRef"),
          details.clientDetails("knownFact")
        )
      case "HMRC-PPT-ORG" =>
        getPptClientDetails(
          details.clientDetails("pptRef"),
          LocalDate.parse(details.clientDetails("registrationDate"))
        )
        // TODO - Implement remaining tax regimes below
//      case "HRMC-CBC-ORG" | "HMRC-CBC-NONUK-ORG" =>
//        getCbcClientDetails(
//          details.clientDetails("cbcId"),
//          details.clientDetails("email")
//        )
//      case "HMRC-PILLAR2-ORG" =>
//        getPillar2ClientDetails(
//          details.clientDetails("plrId"),
//          LocalDate.parse(details.clientDetails("registrationDate")),
//        )
    }

  private def getItsaClientDetails(nino: String, submittedPostcode: String)(implicit
    hc: HeaderCarrier
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    clientDetailsConnector.getItsaBusinessDetails(nino).flatMap {
      case Right(details @ ItsaBusinessDetails(name, Some(postcode), _))
          if postcodeMatches(submittedPostcode, postcode) =>
        Future.successful(Right(ClientDetailsResponse(name, None, details.isOverseas)))
      case Right(_) =>
        Future.successful(Left(ClientDetailsDoNotMatch))
      case Left(ClientDetailsNotFound) if appConfig.altItsaEnabled =>
        (for {
          optName     <- EitherT(clientDetailsConnector.getItsaCitizenDetails(nino)).map(_.name)
          optPostcode <- EitherT(clientDetailsConnector.getItsaDesignatoryDetails(nino)).map(_.postCode)
        } yield (optName, optPostcode)).subflatMap {
          case (Some(name), Some(postcode)) if postcodeMatches(submittedPostcode, postcode) =>
            Right(ClientDetailsResponse(name, None, isOverseas = false))
          case _ =>
            Left(ClientDetailsDoNotMatch)
        }.value
      case Left(err) =>
        Future.successful(Left(err))
    }

  private def getVatClientDetails(vrn: String, vatRegistrationDate: LocalDate)(implicit
    hc: HeaderCarrier
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    clientDetailsConnector.getVatCustomerInfo(vrn).map {
      case Right(VatCustomerDetails(None, None, None, _, _, _)) =>
        logger.warn("[getVatClientDetails] - No name was returned by the API")
        Left(ClientDetailsNotFound)
      case Right(details @ VatCustomerDetails(_, _, _, Some(regDate), _, _)) if regDate == vatRegistrationDate =>
        val clientName = details.tradingName.getOrElse(details.organisationName.getOrElse(details.individual.get.name))
        val clientStatus = if (details.isInsolvent) Some(Insolvent) else None
        Right(ClientDetailsResponse(clientName, clientStatus, details.isOverseas))
      case Right(_) =>
        Left(ClientDetailsDoNotMatch)
      case Left(err) => Left(err)
    }

  private def getTrustClientDetails(
    trustTaxIdentifier: String
  )(implicit hc: HeaderCarrier): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    clientDetailsConnector.getTrustName(trustTaxIdentifier).map {
      case Right(name) =>
        Right(ClientDetailsResponse(name, None, isOverseas = false))
      case Left(err) => Left(err)
    }

  private def getIrvClientDetails(nino: String, dob: LocalDate)(implicit
    hc: HeaderCarrier
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    clientDetailsConnector.getItsaCitizenDetails(nino).map {
      case Right(details @ ItsaCitizenDetails(_, _, Some(dateOfBirth))) if dateOfBirth == dob =>
        details.name match {
          case Some(name) =>
            Right(ClientDetailsResponse(name, None, isOverseas = false))
          case None =>
            logger.warn("[getIrvClientDetails] - No name was returned by the API")
            Left(ClientDetailsNotFound)
        }
      case Right(_) =>
        Left(ClientDetailsDoNotMatch)
      case Left(err) => Left(err)
    }

  private def getCgtClientDetails(cgtRef: String, knownFact: String)(implicit
    hc: HeaderCarrier
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    clientDetailsConnector.getCgtSubscriptionDetails(cgtRef).map {
      case Right(CgtSubscriptionDetails(name, Some(postcode), countryCode))
          if postcodeWithoutSpacesRegex.matches(knownFact.replaceAll("\\s", "")) &&
            knownFact.replaceAll("\\s", "") == postcode.replaceAll("\\s", "") =>
        Right(ClientDetailsResponse(name, None, countryCode.toUpperCase != "GB"))
      case Right(details) if knownFact == details.countryCode =>
        Right(ClientDetailsResponse(details.name, None, details.countryCode.toUpperCase != "GB"))
      case Right(_) =>
        Left(ClientDetailsDoNotMatch)
      case Left(err) => Left(err)
    }

  private def getPptClientDetails(pptRef: String, registrationDate: LocalDate)(implicit
    hc: HeaderCarrier
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    clientDetailsConnector.getPptSubscriptionDetails(pptRef).map {
      case Right(details) if details.dateOfApplication == registrationDate =>
        val isDeregistered = details.deregistrationDate.exists(deregDate => deregDate.isBefore(LocalDate.now))
        val status = if (isDeregistered) Some(Deregistered) else None
        Right(ClientDetailsResponse(details.customerName, status, isOverseas = false))
      case Right(_) =>
        Left(ClientDetailsDoNotMatch)
      case Left(err) => Left(err)
    }
}
