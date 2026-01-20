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

package uk.gov.hmrc.agentclientrelationships.services

import cats.data.EitherT
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.stride.RelationshipSource
import uk.gov.hmrc.agentclientrelationships.model.stride.RelationshipSource.AfrRelationshipRepo
import uk.gov.hmrc.agentclientrelationships.model.stride.RelationshipSource.HipOrIfApi
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.RelationshipFailureResponse
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _}
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.domain.TaxIdentifier

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class ValidationService @Inject() (
  esConnector: EnrolmentStoreProxyConnector,
  appConfig: AppConfig
)(implicit ec: ExecutionContext)
extends RequestAwareLogging {

  def validateForEnrolmentKey(
    serviceKey: String,
    clientId: String,
    clientType: Option[String] = None
  )(implicit rh: RequestHeader): Future[EnrolmentKey] = validateForEnrolmentKeyEither(
    serviceKey,
    clientId,
    clientType
  ).map {
    case Right(enrolmentKey) => enrolmentKey
    case Left(errorMessage) => throw new IllegalArgumentException(s"Failed to build enrolment key because: $errorMessage")
  }

  // noinspection ScalaStyle
  def validateForEnrolmentKeyEither(
    serviceKey: String,
    clientId: String,
    clientType: Option[String] = None
  )(implicit rh: RequestHeader): Future[Either[String, EnrolmentKey]] =
    (serviceKey, clientType) match {
      // "special" cases
      case ("IR-SA" | Service.MtdIt.id | Service.MtdItSupp.id | Service.PersonalIncomeRecord.id, None | Some("ni" | "NI" | "NINO"))
          if NinoWithoutSuffix.isValid(clientId) =>
        Future.successful(Right(EnrolmentKey(serviceKey, NinoWithoutSuffix(clientId))))
      case (Service.Cbc.id, None | Some(CbcIdType.enrolmentId)) => makeSanitisedCbcEnrolmentKey(CbcId(clientId))
      // "normal" cases
      case (serviceKey, _) =>
        if (appConfig.supportedServicesWithoutPir.exists(_.id == serviceKey))
          validateSupportedServiceForEnrolmentKey(
            serviceKey,
            clientId,
            clientType
          )
        else
          Future.successful(Left(s"Unknown service $serviceKey"))
    }

  /** This is needed because sometimes we call the ACR endpoints specifying HMRC-CBC-ORG but it could actually be HMRC-CBC-NONUK-ORG (if the caller has no way
    * of knowing). We check and correct the enrolment key as needed. Also, if it is HMRC-CBC-ORG, we must add a UTR to the enrolment key (alongside the cbcId)
    * as required by specs. First, query EACD assuming enrolment to be HMRC-CBC-ORG (UK version). If that fails, try as HMRC-CBC-NONUK-ORG.
    */
  def makeSanitisedCbcEnrolmentKey(cbcId: CbcId)(implicit rh: RequestHeader): Future[Either[String, EnrolmentKey]] =
    // Try as HMRC-CBC-ORG (UK version)
    esConnector
      .queryKnownFacts(Service.Cbc, Seq(Identifier("cbcId", cbcId.value)))
      .flatMap {
        case None => // No results from EACD for HMRC-CBC-ORG (UK version). Try non-uk instead.
          logger.info(s"CbcId ${cbcId.value} not found as as HMRC-CBC-ORG. Trying as HMRC-CBC-NONUK-ORG.")
          esConnector
            .queryKnownFacts(Service.CbcNonUk, Seq(Identifier("cbcId", cbcId.value)))
            .map {
              case Some(_) => Right(EnrolmentKey(Service.CbcNonUk.id, Seq(Identifier(CbcIdType.enrolmentId, cbcId.value))))
              case None => Left(s"CbcId ${cbcId.value}: tried as both HMRC-CBC-ORG and HMRC-CBC-NONUK-ORG, not found.")
            }
        case Some(identifiers) => Future.successful(Right(EnrolmentKey(Service.Cbc.id, identifiers)))
      }

  def validateForTaxIdentifier(
    clientIdTypeStr: String,
    clientIdStr: String
  ): Either[RelationshipFailureResponse, TaxIdentifier] = {
    val clientIdType = ClientIdType.forId(clientIdTypeStr)
    if (clientIdType.isValid(clientIdStr))
      Right(clientIdType.createUnderlying(clientIdStr))
    else
      Left(RelationshipFailureResponse.TaxIdentifierError)
  }

  def validateAuthProfileToService(
    taxIdentifier: TaxIdentifier,
    authProfile: Option[String],
    relationshipSource: RelationshipSource,
    service: Option[Service]
  )(implicit rh: RequestHeader): Future[Either[RelationshipFailureResponse, Service]] =
    service.fold {
      (taxIdentifier, authProfile, relationshipSource) match {
        case (NinoWithoutSuffix(_), Some("ALL00001"), HipOrIfApi) => Future.successful(Right(Service.MtdIt))
        case (NinoWithoutSuffix(_), Some("ITSAS001"), HipOrIfApi) => Future.successful(Right(Service.MtdItSupp))
        case (NinoWithoutSuffix(_), None, HipOrIfApi) => Future.successful(Right(Service.MtdIt))
        case (NinoWithoutSuffix(_), _, AfrRelationshipRepo) => Future.successful(Right(Service.PersonalIncomeRecord))
        case (Vrn(_), _, _) => Future.successful(Right(Service.Vat))
        case (Utr(_), _, _) => Future.successful(Right(Service.Trust))
        case (CgtRef(_), _, _) => Future.successful(Right(Service.CapitalGains))
        case (PptRef(_), _, _) => Future.successful(Right(Service.Ppt))
        case (Urn(_), _, _) => Future.successful(Right(Service.TrustNT))
        case (CbcId(_), _, _) =>
          EitherT(makeSanitisedCbcEnrolmentKey(CbcId(taxIdentifier.value)))
            .map(x => Service(x.service))
            .leftMap(_ => RelationshipFailureResponse.TaxIdentifierError)
            .value
        case (PlrId(_), _, _) => Future.successful(Right(Service.Pillar2))
        case _ =>
          logger.warn("[validateAuthProfileToService] - could not match authProfile to Service")
          Future.successful(Left(RelationshipFailureResponse.TaxIdentifierError))
      }
    } { s =>
      Future.successful(Right(s))
    }

  private def validateSupportedServiceForEnrolmentKey(
    serviceKey: String,
    clientId: String,
    taxIdType: Option[String]
  ): Future[Either[String, EnrolmentKey]] = {
    val service: Service = Service.forId(serviceKey)
    val clientIdType: ClientIdType[TaxIdentifier] = service.supportedClientIdType
    if (taxIdType.isEmpty || taxIdType.contains(clientIdType.enrolmentId)) {
      if (clientIdType.isValid(clientId))
        Future.successful(Right(EnrolmentKey(service, clientIdType.createUnderlying(clientId))))
      else
        Future.successful(
          Left(s"Identifier $clientId of type $clientIdType provided for service $serviceKey failed validation")
        )
    }
    else
      Future.successful(Left(s"Identifier $clientId of stated type $taxIdType cannot be used for service $serviceKey"))
  }

}
