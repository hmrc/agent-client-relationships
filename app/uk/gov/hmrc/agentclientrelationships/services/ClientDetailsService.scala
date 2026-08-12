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
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.vat.VatCustomerDetails
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdIt
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdItSupp
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

  // Expands either of the ITSA clientIds to both NINO and MTDITID where possible
  // NINO is mandatory as we treat it as a primary ITSA identifier, it is the 'suppliedClientId' as this is the only one ever manually input by users
  // Sometimes we get MTDITID in requests (from APIs or client enrolments), requiring us to convert either of them into both
  def expandClientId(
    service: Service,
    clientId: TaxIdentifier
  )(implicit request: RequestHeader): Future[(TaxIdentifier, Option[TaxIdentifier])] =
    (service, clientId) match {
      case (MtdIt | MtdItSupp, nino @ NinoWithoutSuffix(_)) =>
        hipConnector.getMtdIdFor(nino).map {
          case Some(mtdId) => (nino, Some(mtdId))
          case None => (clientId, None)
        }
      case (MtdIt | MtdItSupp, mtdId @ MtdItId(_)) =>
        hipConnector.getNinoFor(mtdId).map {
          case Some(nino) => (nino, Some(mtdId))
          case None => throw new RuntimeException(s"NINO not found for MTDITID: ${mtdId.value}")
        }
      case _ => Future.successful((clientId, None))
      // TODO the way this is used will fail for enrolments with multiple identifiers unless they are looked up as part of this code. Potentially merge this logic with the client id validation code?
    }

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
    countryCodes: Seq[String],
    name: String,
    factType: KnownFactType
  ): ClientDetailsResponse = ClientDetailsResponse(
    name = name,
    status = None,
    isOverseas = Some(true),
    knownFacts = countryCodes,
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

  // scalastyle:off cyclomatic.complexity
  private def getItsaClientDetails(nino: String)(implicit request: RequestHeader): Future[Either[ClientDetailsFailureResponse, ClientDetailsResponse]] = {
    for {
      itsaCitizenDetailsEither <- clientDetailsConnector.getItsaCitizenDetails(NinoWithoutSuffix(nino))
      finalResponse <-
        itsaCitizenDetailsEither match {
          case Left(_) => Future.successful(Left(ClientDetailsNotFound))
          case Right(citizenDetails) =>

            for {
              itsaDesignatoryDetailsEither <- clientDetailsConnector.getItsaDesignatoryDetails(NinoWithoutSuffix(nino))

              intermediateResponse <-
                itsaDesignatoryDetailsEither match {
                  case Left(_) => Future.successful(Left(ClientDetailsNotFound))
                  case Right(itsaDesignatoryDetails) =>
                    (
                      citizenDetails.name,
                      citizenDetails.saUtr,
                      itsaDesignatoryDetails.postCode.filter(_.trim.nonEmpty),
                      itsaDesignatoryDetails.country.filter(_.trim.nonEmpty)
                    ) match {
                      case (Some(name), Some(_), Some(postcode), Some(country)) if isUk(country) =>
                        Future.successful(Right(makeItsaUkResponse(postcode = postcode, name = name)))
                      case (Some(name), Some(_), _, Some(country)) if appConfig.overseasItsaEnabled && !isUk(country) =>
                        hipConnector.getMtdIdFor(NinoWithoutSuffix(nino)).map {
                          case Some(_) =>
                            Right(makeItsaOverseasResponse(
                              countryCodes = toCountryCode(country),
                              name = name,
                              factType = CountryCode
                            ))
                          case None => Left(ClientDetailsNotFound)
                        }
                      case (Some(_), Some(_), _, Some(country)) if !isUk(country) =>
                        // TODO REMOVE THIS CASE WHEN overseasItsaEnabled FEATURE SWITCH IS REMOVED
                        Future.successful(Left(ClientDetailsNotFound))
                      case (optName, Some(_), optPostcode, optCountry) if optName.isEmpty || optPostcode.isEmpty || optCountry.isEmpty =>
                        val missingFields =
                          List(
                            Option.when(optName.isEmpty)("Name"),
                            Option.when(optPostcode.isEmpty && optCountry.exists(isUk))("Post Code (UK Only)"),
                            Option.when(optCountry.isEmpty)("Country")
                          ).flatten
                        val msg = s"Missing required data from ITSA APIs: ${missingFields.mkString(", ")}"
                        logger.warn(msg)
                        Future.successful(Left(ErrorRetrievingClientDetails(200, msg)))
                      case _ => Future.successful(Left(ClientDetailsNotFound))
                    }

                }

            } yield intermediateResponse

        }

    } yield finalResponse
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

  private val countryNameToCode: Map[String, Seq[String]] = Map(
    "ANDORRA" -> Seq("AD"),
    "ARAB EMIRATES (UNITED)" -> Seq("AE"),
    "SHARJAH" -> Seq("AE"),
    "AFGHANISTAN" -> Seq("AF"),
    "ANTIGUA" -> Seq("AG"),
    "BARBUDA" -> Seq("AG"),
    "ANTIGUA AND BARBUDA" -> Seq("AG"),
    "ANGUILLA" -> Seq("AI"),
    "ALBANIA" -> Seq("AL"),
    "REPUBLIC OF ARMENIA" -> Seq("AM"),
    "ARMENIA" -> Seq("AM"),
    "ANTILLES (NETHERLANDS)" -> Seq(
      "AN",
      "CW",
      "SX",
      "BQ",
      "AW"
    ),
    "ANGOLA" -> Seq("AO"),
    "ANTARCTICA" -> Seq("AQ"),
    "ARGENTINA" -> Seq("AR"),
    "AMERICAN SAMOA" -> Seq("AS"),
    "AUSTRIA" -> Seq("AT"),
    "AUSTRALIA" -> Seq("AU"),
    "ARUBA" -> Seq("AW"),
    "REPUBLIC OF AZERBAIJAN" -> Seq("AZ"),
    "REP OF BOSNIA-HERZEGOVINA" -> Seq("BA"),
    "BARBADOS" -> Seq("BB"),
    "BANGLADESH" -> Seq("BD"),
    "BELGIUM" -> Seq("BE"),
    "BURKINA FASO" -> Seq("BF"),
    "BULGARIA" -> Seq("BG"),
    "BAHRAIN" -> Seq("BH"),
    "BURUNDI" -> Seq("BI"),
    "BENIN" -> Seq("BJ"),
    "BERMUDA" -> Seq("BM"),
    "BRUNEI" -> Seq("BN"),
    "BOLIVIA" -> Seq("BO"),
    "ANTARCTIC TERRITORIES (BRITISH)" -> Seq("AQ"),
    "CARIBBEAN NETHERLANDS" -> Seq("BQ"),
    "BRAZIL" -> Seq("BR"),
    "BAHAMAS" -> Seq("BS"),
    "BHUTAN" -> Seq("BT"),
    "BOUVET ISLAND" -> Seq("BV"),
    "BOTSWANA" -> Seq("BW"),
    "REPUBLIC OF BELARUS" -> Seq("BY"),
    "BELIZE" -> Seq("BZ"),
    "CANADA" -> Seq("CA"),
    "COCOS (KEELING) ISLANDS" -> Seq("CC"),
    "DEMOCRATIC REPUBLIC OF CONGO" -> Seq("CD"),
    "CENTRAL AFRICAN REPUBLIC" -> Seq("CF"),
    "CONGO" -> Seq("CG"),
    "SWITZERLAND" -> Seq("CH"),
    "COTE D'IVOIRE" -> Seq("CI"),
    "COOK ISLANDS" -> Seq("CK"),
    "CHILE" -> Seq("CL"),
    "CAMEROON" -> Seq("CM"),
    "CHINA PEOPLES REPUBLIC" -> Seq("CN"),
    "COLOMBIA" -> Seq("CO"),
    "COSTA RICA" -> Seq("CR"),
    "CZECHOSLOVAKIA" -> Seq(
      "CS",
      "CZ",
      "SK"
    ),
    "CUBA" -> Seq("CU"),
    "CAPE VERDE ISLANDS" -> Seq("CV"),
    "CURACAO" -> Seq("CW"),
    "CHRISTMAS ISLAND" -> Seq("CX"),
    "CYPRUS" -> Seq("CY"),
    "NORTHERN PART OF CYPRUS" -> Seq("CY"),
    "CZECH REPUBLIC" -> Seq("CZ"),
    "EAST GERMANY" -> Seq("DD", "DE"),
    "GERMANY" -> Seq("DE"),
    "DJIBOUTI" -> Seq("DJ"),
    "DENMARK" -> Seq("DK"),
    "COMMONWEALTH OF DOMINICA" -> Seq("DM"),
    "DOMINICAN REPUBLIC" -> Seq("DO"),
    "ALGERIA" -> Seq("DZ"),
    "ECUADOR" -> Seq("EC"),
    "REPUBLIC OF ESTONIA" -> Seq("EE"),
    "EGYPT" -> Seq("EG"),
    "WESTERN SAHARA" -> Seq("EH"),
    "ERITREA" -> Seq("ER"),
    "SPAIN" -> Seq("ES"),
    "ETHIOPIA" -> Seq("ET"),
    "FINLAND" -> Seq("FI"),
    "FIJI" -> Seq("FJ"),
    "FALKLAND ISLANDS" -> Seq("FK"),
    "MICRONESIA FEDERATION OF" -> Seq("FM"),
    "FAROE ISLANDS" -> Seq("FO"),
    "FRANCE" -> Seq("FR"),
    "FRENCH OVERSEAS DEPARTMENT" -> Seq(
      "FR",
      "RE",
      "MQ",
      "GP",
      "GF",
      "YT"
    ),
    "GABON" -> Seq("GA"),
    "ENGLAND" -> Seq("GB"),
    "GREAT BRITAIN" -> Seq("GB"),
    "NORTHERN IRELAND" -> Seq("GB"),
    "SCOTLAND" -> Seq("GB"),
    "WALES" -> Seq("GB"),
    "GRENADA" -> Seq("GD"),
    "REPUBLIC OF GEORGIA" -> Seq("GE"),
    "FRENCH GUIANA" -> Seq("GF"),
    "ALDERNEY" -> Seq("GG"),
    "GUERNSEY" -> Seq("GG"),
    "SARK" -> Seq("GG"),
    "CHANNEL ISLANDS" -> Seq("GG", "JE"),
    "GHANA" -> Seq("GH"),
    "GIBRALTAR" -> Seq("GI"),
    "GREENLAND" -> Seq("GL"),
    "GAMBIA" -> Seq("GM"),
    "GUINEA" -> Seq("GN"),
    "GUADELOUPE" -> Seq("GP"),
    "EQUATORIAL GUINEA" -> Seq("GQ"),
    "GREECE" -> Seq("GR"),
    "SOUTH GEORGIA AND SOUTH SANDWICH ISLAND" -> Seq("GS"),
    "GUATEMALA" -> Seq("GT"),
    "GUAM" -> Seq("GU"),
    "BISSAU (GUINEA)" -> Seq("GW"),
    "GUYANA" -> Seq("GY"),
    "HONG KONG" -> Seq("HK"),
    "HEARD ISLAND AND MCDONALD ISLANDS" -> Seq("HM"),
    "HONDURAS" -> Seq("HN"),
    "REPUBLIC OF CROATIA" -> Seq("HR"),
    "HAITI" -> Seq("HT"),
    "HUNGARY" -> Seq("HU"),
    "INDONESIA" -> Seq("ID"),
    "REPUBLIC OF IRELAND" -> Seq("IE"),
    "ISRAEL" -> Seq("IL"),
    "ISLE OF MAN" -> Seq("IM"),
    "INDIA" -> Seq("IN"),
    "BRITISH INDIAN OCEAN TERRITORIES" -> Seq("IO"),
    "IRAQ" -> Seq("IQ"),
    "IRAN" -> Seq("IR"),
    "ICELAND" -> Seq("IS"),
    "ITALY" -> Seq("IT"),
    "JERSEY" -> Seq("JE"),
    "JAMAICA" -> Seq("JM"),
    "JORDAN" -> Seq("JO"),
    "JAPAN" -> Seq("JP"),
    "KENYA" -> Seq("KE"),
    "REPUBLIC OF KYRGYZSTAN" -> Seq("KG"),
    "CAMBODIA" -> Seq("KH"),
    "KAMPUCHEA" -> Seq("KH"),
    "KIRIBATI" -> Seq("KI"),
    "COMORO ISLANDS" -> Seq("KM"),
    "NEVIS,ST KITTS-NEVIS" -> Seq("KN"),
    "NORTH KOREA" -> Seq("KP"),
    "SOUTH KOREA" -> Seq("KR"),
    "KUWAIT" -> Seq("KW"),
    "CAYMAN ISLANDS" -> Seq("KY"),
    "REPUBLIC OF KAZAKHSTAN" -> Seq("KZ"),
    "LAOS" -> Seq("LA"),
    "LEBANON" -> Seq("LB"),
    "ST LUCIA" -> Seq("LC"),
    "LIECHTENSTEIN" -> Seq("LI"),
    "SRI LANKA" -> Seq("LK"),
    "LIBERIA" -> Seq("LR"),
    "LESOTHO" -> Seq("LS"),
    "REPUBLIC OF LITHUANIA" -> Seq("LT"),
    "LUXEMBOURG" -> Seq("LU"),
    "REPUBLIC OF LATVIA" -> Seq("LV"),
    "LIBYA" -> Seq("LY"),
    "MOROCCO" -> Seq("MA"),
    "MONACO" -> Seq("MC"),
    "REPUBLIC OF MOLDOVA" -> Seq("MD"),
    "REPUBLIC OF MONTENEGRO" -> Seq("ME"),
    "ST MARTINS" -> Seq("MF", "SX"),
    "MADAGASCAR" -> Seq("MG"),
    "MARSHALL ISLANDS" -> Seq("MH"),
    "FORMER YUG REP OF MACEDONIA" -> Seq("MK"),
    "NORTH MACEDONIA" -> Seq("MK"),
    "MALI" -> Seq("ML"),
    "BURMA" -> Seq("MM"),
    "MYANMAR" -> Seq("MM"),
    "MONGOLIA" -> Seq("MN"),
    "MACAU" -> Seq("MO"),
    "NORTHERN MARIANA ISLANDS" -> Seq("MP"),
    "MARTINIQUE" -> Seq("MQ"),
    "MAURITANIA" -> Seq("MR"),
    "MONTSERRAT" -> Seq("MS"),
    "MALTA" -> Seq("MT"),
    "MAURITIUS" -> Seq("MU"),
    "MALDIVE ISLANDS" -> Seq("MV"),
    "MALAWI" -> Seq("MW"),
    "MEXICO" -> Seq("MX"),
    "MALAYSIA" -> Seq("MY"),
    "SABAH" -> Seq("MY"),
    "SARAWAK" -> Seq("MY"),
    "MOZAMBIQUE" -> Seq("MZ"),
    "NAMIBIA" -> Seq("NA"),
    "NEW CALEDONIA" -> Seq("NC"),
    "NIGER" -> Seq("NE"),
    "NORFOLK ISLAND" -> Seq("NF"),
    "NIGERIA" -> Seq("NG"),
    "NICARAGUA" -> Seq("NI"),
    "NETHERLANDS" -> Seq("NL"),
    "NORWAY" -> Seq("NO"),
    "NEPAL" -> Seq("NP"),
    "NAURU" -> Seq("NR"),
    "NIUE" -> Seq("NU"),
    "NEW ZEALAND" -> Seq("NZ"),
    "OMAN" -> Seq("OM"),
    "PANAMA" -> Seq("PA"),
    "PERU" -> Seq("PE"),
    "FRENCH POLYNESIA" -> Seq("PF"),
    "TAHITI" -> Seq("PF"),
    "PAPUA NEW GUINEA" -> Seq("PG"),
    "PHILIPPINES" -> Seq("PH"),
    "PAKISTAN" -> Seq("PK"),
    "POLAND" -> Seq("PL"),
    "SAINT PIERRE AND MIQUELON" -> Seq("PM"),
    "PITCAIRN" -> Seq("PN"),
    "PUERTO RICO" -> Seq("PR"),
    "PORTUGAL" -> Seq("PT"),
    "PALAU" -> Seq("PW"),
    "PARAGUAY" -> Seq("PY"),
    "QATAR" -> Seq("QA"),
    "REUNION" -> Seq("RE"),
    "ROMANIA" -> Seq("RO"),
    "REPUBLIC OF SERBIA" -> Seq("RS"),
    "RUSSIA" -> Seq("RU"),
    "RUSSIAN FEDERATION" -> Seq("RU"),
    "RWANDA" -> Seq("RW"),
    "SAUDI ARABIA" -> Seq("SA"),
    "SOLOMON ISLANDS" -> Seq("SB"),
    "SEYCHELLES" -> Seq("SC"),
    "SUDAN" -> Seq("SD"),
    "SWEDEN" -> Seq("SE"),
    "SINGAPORE" -> Seq("SG"),
    "ASCENCION ISLAND" -> Seq("SH", "AC"),
    "ST HELENA & DEPNDS" -> Seq(
      "SH",
      "AC",
      "TA"
    ),
    "TRISTAN DA CUHNA" -> Seq("SH", "TA"),
    "REPUBLIC OF SLOVENIA" -> Seq("SI"),
    "SVALBARD AND JAN MAYEN" -> Seq("SJ"),
    "SLOVAK REPUBLIC" -> Seq("SK"),
    "SIERRA LEONE" -> Seq("SL"),
    "SAN MARINO" -> Seq("SM"),
    "SENEGAL" -> Seq("SN"),
    "SOMALIA" -> Seq("SO"),
    "SURINAM" -> Seq("SR"),
    "PRINCIPE AND SAO TOME" -> Seq("ST"),
    "USSR" -> Seq("SU", "RU"),
    "EL SALVADOR" -> Seq("SV"),
    "SYRIA" -> Seq("SY"),
    "SWAZILAND" -> Seq("SZ"),
    "TURKS & CAICOS ISLANDS" -> Seq("TC"),
    "CHAD" -> Seq("TD"),
    "FRENCH SOUTHERN TERRITORIES" -> Seq("TF"),
    "TOGO" -> Seq("TG"),
    "THAILAND" -> Seq("TH"),
    "REPUBLIC OF TAJIKISTAN" -> Seq("TJ"),
    "TOKELAU" -> Seq("TK"),
    "EAST TIMOR" -> Seq("TL"),
    "REPUBLIC OF TURKMENISTAN" -> Seq("TM"),
    "TUNISIA" -> Seq("TN"),
    "TONGA" -> Seq("TO"),
    "TURKEY" -> Seq("TR"),
    "TRINIDAD & TOBAGO" -> Seq("TT"),
    "TUVALU" -> Seq("TV"),
    "TAIWAN" -> Seq("TW"),
    "TANZANIA" -> Seq("TZ"),
    "UKRAINE" -> Seq("UA"),
    "UGANDA" -> Seq("UG"),
    "UNITED STATES MINOR OUTLYING ISLANDS" -> Seq("UM"),
    "USA" -> Seq("US"),
    "URUGUAY" -> Seq("UY"),
    "REPUBLIC OF UZBEKISTAN" -> Seq("UZ"),
    "VATICAN CITY STATE" -> Seq("VA"),
    "ST VINCENT & GRENADINES" -> Seq("VC"),
    "VENEZUELA" -> Seq("VE"),
    "VIRGIN ISLANDS (BRITISH)" -> Seq("VG"),
    "VIRGIN ISLANDS (USA)" -> Seq("VI"),
    "VIETNAM" -> Seq("VN"),
    "VANUATU" -> Seq("VU"),
    "WALLIS AND FUTUNA" -> Seq("WF"),
    "WESTERN SAMOA" -> Seq("WS"),
    "SAMOA" -> Seq("WS"),
    "REPUBLIC OF KOSOVO" -> Seq("XK"),
    "DEMOCRATIC YEMEN" -> Seq("YD", "YE"),
    "REPUBLIC OF YEMEN" -> Seq("YE"),
    "MAYOTTE" -> Seq("YT"),
    "FEDERAL REP OF YUGOSLAVIA" -> Seq(
      "YU",
      "CS",
      "RS",
      "ME"
    ),
    "YUGOSLAVIA" -> Seq(
      "YU",
      "BA",
      "HR",
      "MK",
      "ME",
      "RS",
      "SI",
      "XK"
    ),
    "SOUTH AFRICA" -> Seq("ZA"),
    "ZAMBIA" -> Seq("ZM"),
    "ZAIRE" -> Seq("ZR", "CD"),
    "ZIMBABWE" -> Seq("ZW"),
    "ABROAD - NOT KNOWN" -> Seq("ZZ"),
    "NOT SPECIFIED OR NOT USED" -> Seq("ZZ"),
    "NOT YET RECORDED" -> Seq("ZZ"),
    "STATELESS" -> Seq("ZZ"),
    "TOURS" -> Seq("ZZ")
  )

  private def toCountryCode(country: String): Seq[String] = {
    countryNameToCode.getOrElse(country.trim.toUpperCase, Seq("ZZ"))
  }

}
