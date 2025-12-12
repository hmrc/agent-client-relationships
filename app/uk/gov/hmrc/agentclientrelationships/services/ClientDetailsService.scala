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

package uk.gov.hmrc.agentclientrelationships.services

import cats.data.EitherT
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.ClientDetailsConnector
import uk.gov.hmrc.agentclientrelationships.connectors.HipConnector
import uk.gov.hmrc.agentclientrelationships.model.CitizenDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientStatus._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.KnownFactType._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.cgt.CgtSubscriptionDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa.ItsaBusinessDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.vat.VatCustomerDetails
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.domain.TaxIdentifier

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class ClientDetailsService @Inject() (
  clientDetailsConnector: ClientDetailsConnector,
  hipConnector: HipConnector,
  appConfig: AppConfig
)(implicit ec: ExecutionContext)
extends RequestAwareLogging {

  def findClientDetailsByTaxIdentifier(
    taxIdentifier: TaxIdentifier
  )(implicit request: RequestHeader): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    taxIdentifier match {
      case NinoWithoutSuffix(nino) => EitherT(getItsaClientDetails(nino)).orElse(EitherT(getIrvClientDetails(nino))).value
      case Vrn(vrn) => getVatClientDetails(vrn)
      case Utr(utr) => getTrustClientDetails(utr)
      case Urn(urn) => getTrustClientDetails(urn)
      case CgtRef(cgtRef) => getCgtClientDetails(cgtRef)
      case PptRef(pptRef) => getPptClientDetails(pptRef)
      case CbcId(cbcId) => getCbcClientDetails(cbcId)
      case PlrId(plrId) => getPillar2ClientDetails(plrId)
      case _ => Future.successful(Left(ClientDetailsNotFound))
    }

  def findClientDetails(
    service: String,
    clientId: String
  )(implicit request: RequestHeader): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    service.toUpperCase match {
      case "HMRC-MTD-IT" | "HMRC-MTD-IT-SUPP" => getItsaClientDetails(clientId)
      case "HMRC-MTD-VAT" => getVatClientDetails(clientId)
      case "HMRC-TERS-ORG" | "HMRC-TERSNT-ORG" => getTrustClientDetails(clientId)
      case "PERSONAL-INCOME-RECORD" => getIrvClientDetails(clientId)
      case "HMRC-CGT-PD" => getCgtClientDetails(clientId)
      case "HMRC-PPT-ORG" => getPptClientDetails(clientId)
      case "HMRC-CBC-ORG" => getCbcClientDetails(clientId)
      case "HMRC-PILLAR2-ORG" => getPillar2ClientDetails(clientId)
    }

  private def makeItsaOverseasResponse(
    country: String,
    name: String,
    factType: KnownFactType
  ): ClientDetailsResponse = ClientDetailsResponse(
    name = name,
    status = None,
    isOverseas = Some(true),
    knownFacts = Seq(country),
    knownFactType = Some(factType)
  )

  private def makeItsaUkResponse(
    postcode: String,
    name: String
  ): ClientDetailsResponse = ClientDetailsResponse(
    name = name,
    status = None,
    isOverseas = Some(false),
    knownFacts = Seq(postcode.replaceAll("\\s", "")),
    knownFactType = Some(PostalCode)
  )

  // using Citizen Details designatory details service returns country names
  // that includes UK countries
  // https://github.com/hmrc/citizen-details/blob/main/app/uk/gov/hmrc/citizendetails/model/nps/Address.scala#L17
  private def isUk(countryName: String) = List(
    "GREAT BRITAIN",
    "ENGLAND",
    "WALES",
    "NORTHERN IRELAND",
    "SCOTLAND"
  ).contains(countryName)

  private def checkCitizenDetails(nino: NinoWithoutSuffix)(implicit rh: RequestHeader): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] =
    (
      for {
        citizenDetails <- EitherT(clientDetailsConnector.getItsaCitizenDetails(nino))
        designatoryDetails <- EitherT(clientDetailsConnector.getItsaDesignatoryDetails(nino))
      } yield (citizenDetails.name, citizenDetails.saUtr, designatoryDetails.postCode, designatoryDetails.country)
    ).subflatMap {
      case (Some(name), Some(_), Some(postcode), Some(country)) if isUk(country) =>
        Right(makeItsaUkResponse(
          postcode = postcode,
          name = name
        ))
      case (Some(name), Some(_), _, Some(country)) if appConfig.overseasItsaEnabled =>
        Right(makeItsaOverseasResponse(
          country = country,
          name = name,
          factType = Country
        ))
      case _ => Left(ClientDetailsNotFound)
    }.value

  private def getItsaClientDetails(nino: String)(implicit
    request: RequestHeader
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] = hipConnector
    .getItsaBusinessDetails(NinoWithoutSuffix(nino))
    .flatMap {
      case Right(details @ ItsaBusinessDetails(
            _,
            Some(postcode),
            _
          )) if !details.isOverseas =>
        Future.successful(Right(makeItsaUkResponse(
          postcode = postcode,
          name = details.name
        )))
      case Right(details @ ItsaBusinessDetails(
            _,
            _,
            countryCode
          )) if appConfig.overseasItsaEnabled =>
        Future.successful(Right(makeItsaOverseasResponse(
          country = countryCode,
          name = details.name,
          factType = CountryCode
        )))
      case Right(_) =>
        logger.warn(s"[getItsaClientDetails] - Valid business details not found in ETMP for $nino")
        Future.successful(Left(ClientDetailsNotFound))
      case Left(ClientDetailsNotFound) => checkCitizenDetails(NinoWithoutSuffix(nino))
      case Left(err) => Future.successful(Left(err))
    }

  private def getVatClientDetails(vrn: String)(implicit
    request: RequestHeader
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] = clientDetailsConnector
    .getVatCustomerInfo(vrn)
    .map {
      case Right(
            VatCustomerDetails(
              None,
              None,
              None,
              _,
              _
            )
          ) =>
        logger.warn("[getVatClientDetails] - No name was returned by the API")
        Left(ClientDetailsNotFound)
      case Right(
            details @ VatCustomerDetails(
              _,
              _,
              _,
              Some(regDate),
              _
            )
          ) =>
        val clientName = details.tradingName.getOrElse(details.organisationName.getOrElse(details.individual.get.name))
        val clientStatus =
          if (details.isInsolvent)
            Some(Insolvent)
          else
            None
        Right(
          ClientDetailsResponse(
            clientName,
            clientStatus,
            None,
            Seq(regDate.toString),
            Some(Date)
          )
        )
      case Right(_) =>
        logger.warn("[getVatClientDetails] - No registration date was returned by the API")
        Left(ClientDetailsNotFound)
      case Left(err) => Left(err)
    }

  private def getTrustClientDetails(trustTaxIdentifier: String)(implicit
    request: RequestHeader
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] = clientDetailsConnector
    .getTrustName(trustTaxIdentifier)
    .map {
      case Right(name) =>
        Right(
          ClientDetailsResponse(
            name,
            None,
            None,
            Seq(),
            None
          )
        )
      case Left(err) => Left(err)
    }

  private def getIrvClientDetails(nino: String)(implicit
    request: RequestHeader
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] = clientDetailsConnector
    .getItsaCitizenDetails(NinoWithoutSuffix(nino))
    .map {
      case Right(
            details @ CitizenDetails(
              _,
              _,
              Some(dateOfBirth),
              _
            )
          ) =>
        details.name match {
          case Some(name) =>
            Right(
              ClientDetailsResponse(
                name,
                None,
                None,
                Seq(dateOfBirth.toString),
                Some(Date)
              )
            )
          case None =>
            logger.warn("[getIrvClientDetails] - No name was returned by the API")
            Left(ClientDetailsNotFound)
        }
      case Right(_) =>
        logger.warn("[getIrvClientDetails] - No date of birth was returned by the API")
        Left(ClientDetailsNotFound)
      case Left(err) => Left(err)
    }

  private def getCgtClientDetails(cgtRef: String)(implicit
    request: RequestHeader
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] = clientDetailsConnector
    .getCgtSubscriptionDetails(cgtRef)
    .map {
      case Right(
            CgtSubscriptionDetails(
              name,
              Some(postcode),
              countryCode
            )
          ) if countryCode.toUpperCase == "GB" =>
        Right(
          ClientDetailsResponse(
            name,
            None,
            Some(false),
            Seq(postcode.replaceAll("\\s", "")),
            Some(PostalCode)
          )
        )
      case Right(
            CgtSubscriptionDetails(
              name,
              _,
              countryCode
            )
          ) =>
        Right(
          ClientDetailsResponse(
            name,
            None,
            Some(true),
            Seq(countryCode),
            Some(CountryCode)
          )
        )
      case Left(err) => Left(err)
    }

  private def getPptClientDetails(pptRef: String)(implicit
    request: RequestHeader
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] = clientDetailsConnector
    .getPptSubscriptionDetails(pptRef)
    .map {
      case Right(details) =>
        val isDeregistered = details.deregistrationDate.exists(deregDate => deregDate.isBefore(LocalDate.now))
        val status =
          if (isDeregistered)
            Some(Deregistered)
          else
            None
        Right(
          ClientDetailsResponse(
            details.customerName,
            status,
            None,
            Seq(details.dateOfApplication.toString),
            Some(Date)
          )
        )
      case Left(err) => Left(err)
    }

  private def getCbcClientDetails(cbcId: String)(implicit
    request: RequestHeader
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] = clientDetailsConnector
    .getCbcSubscriptionDetails(cbcId)
    .map {
      case Right(details) =>
        (details.anyAvailableName, details.emails.nonEmpty) match {
          case (Some(name), true) =>
            Right(
              ClientDetailsResponse(
                name,
                None,
                Some(!details.isGBUser),
                details.emails,
                Some(Email)
              )
            )
          case _ =>
            logger.warn("[getCbcClientDetails] - Necessary client name and/or email data was missing")
            Left(ClientDetailsNotFound)
        }
      case Left(err) => Left(err)
    }

  private def getPillar2ClientDetails(plrId: String)(implicit
    request: RequestHeader
  ): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] = clientDetailsConnector
    .getPillar2SubscriptionDetails(plrId)
    .map {
      case Right(details) =>
        val status =
          if (details.inactive)
            Some(Inactive)
          else
            None
        val isOverseas = details.countryCode.toUpperCase != "GB"
        Right(
          ClientDetailsResponse(
            details.organisationName,
            status,
            Some(isOverseas),
            Seq(details.registrationDate),
            Some(Date)
          )
        )
      case Left(err) => Left(err)
    }

}
