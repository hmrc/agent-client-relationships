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

import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _}
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ValidationService @Inject() (
  esConnector: EnrolmentStoreProxyConnector,
  appConfig: AppConfig
) extends Logging {

  // noinspection ScalaStyle
  def validateForEnrolmentKey(serviceKey: String, clientType: String, clientId: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[String, EnrolmentKey]] =
    (serviceKey, clientType) match {
      // "special" cases
      case ("IR-SA", "ni" | "NI" | "NINO") if Nino.isValid(clientId) =>
        Future.successful(Right(EnrolmentKey("IR-SA", Nino(clientId))))
      case (Service.MtdIt.id | Service.MtdItSupp.id, "ni" | "NI" | "NINO") if Nino.isValid(clientId) =>
        Future.successful(Right(EnrolmentKey(serviceKey, Nino(clientId))))
      case (Service.PersonalIncomeRecord.id, "NINO") =>
        Future.successful(Right(EnrolmentKey(serviceKey, Nino(clientId))))
      case ("HMCE-VATDEC-ORG", "vrn") if Vrn.isValid(clientId) =>
        Future.successful(Right(EnrolmentKey("HMCE-VATDEC-ORG", Vrn(clientId))))
      case (Service.Cbc.id, CbcIdType.enrolmentId) =>
        makeSanitisedCbcEnrolmentKey(CbcId(clientId))
      // "normal" cases
      case (serviceKey, _) =>
        if (appConfig.supportedServicesWithoutPir.exists(_.id == serviceKey))
          validateSupportedServiceForEnrolmentKey(serviceKey, clientType, clientId)
        else
          Future.successful(Left(s"Unknown service $serviceKey"))
    }

  /** This is needed because sometimes we call the ACR endpoints specifying HMRC-CBC-ORG but it could actually be
    * HMRC-CBC-NONUK-ORG (if the caller has no way of knowing). We check and correct the enrolment key as needed. Also,
    * if it is HMRC-CBC-ORG, we must add a UTR to the enrolment key (alongside the cbcId) as required by specs. First,
    * query EACD assuming enrolment to be HMRC-CBC-ORG (UK version). If that fails, try as HMRC-CBC-NONUK-ORG.
    */
  def makeSanitisedCbcEnrolmentKey(
    cbcId: CbcId
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Either[String, EnrolmentKey]] =
    // Try as HMRC-CBC-ORG (UK version)
    esConnector.queryKnownFacts(Service.Cbc, Seq(Identifier("cbcId", cbcId.value))).flatMap {
      case None => // No results from EACD for HMRC-CBC-ORG (UK version). Try non-uk instead.
        logger.info(s"CbcId ${cbcId.value} not found as as HMRC-CBC-ORG. Trying as HMRC-CBC-NONUK-ORG.")
        esConnector.queryKnownFacts(Service.CbcNonUk, Seq(Identifier("cbcId", cbcId.value))).map {
          case Some(_) => Right(EnrolmentKey(Service.CbcNonUk.id, Seq(Identifier(CbcIdType.enrolmentId, cbcId.value))))
          case None    => Left(s"CbcId ${cbcId.value}: tried as both HMRC-CBC-ORG and HMRC-CBC-NONUK-ORG, not found.")
        }
      case Some(identifiers) =>
        Future.successful(
          Right(
            EnrolmentKey(
              Service.Cbc.id,
              identifiers
            )
          )
        )
    }

  private def validateSupportedServiceForEnrolmentKey(
    serviceKey: String,
    taxIdType: String,
    clientId: String
  ): Future[Either[String, EnrolmentKey]] = {
    val service: Service = Service.forId(serviceKey)
    val clientIdType: ClientIdType[TaxIdentifier] = service.supportedClientIdType
    if (taxIdType == clientIdType.enrolmentId) {
      if (clientIdType.isValid(clientId))
        Future.successful(Right(EnrolmentKey(service, clientIdType.createUnderlying(clientId))))
      else
        Future.successful(
          Left(s"Identifier $clientId of stated type $taxIdType provided for service $serviceKey failed validation")
        )
    } else
      Future.successful(Left(s"Identifier $clientId of stated type $taxIdType cannot be used for service $serviceKey"))
  }
}
