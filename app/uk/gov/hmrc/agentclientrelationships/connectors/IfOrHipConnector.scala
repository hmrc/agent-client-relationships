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

import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.stride.ClientRelationship
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientDetailsFailureResponse
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa.ItsaBusinessDetails
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Service}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class IfOrHipConnector @Inject() (
  hipConnector: HipConnector,
  ifConnector: IfConnector
)(val appConfig: AppConfig) {

  private val hipAgentRelationshipEnabled: Boolean = appConfig.hipAgentRelationshipEnabled
  private val hipBusinessDetailsEnabled: Boolean = appConfig.hipBusinessDetailsEnabled

  def createAgentRelationship(enrolmentKey: EnrolmentKey, arn: Arn)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[RegistrationRelationshipResponse]] =
    if (hipAgentRelationshipEnabled) hipConnector.createAgentRelationship(enrolmentKey, arn)
    else ifConnector.createAgentRelationship(enrolmentKey, arn)

  def deleteAgentRelationship(enrolmentKey: EnrolmentKey, arn: Arn)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[RegistrationRelationshipResponse]] =
    if (hipAgentRelationshipEnabled) hipConnector.deleteAgentRelationship(enrolmentKey, arn)
    else ifConnector.deleteAgentRelationship(enrolmentKey, arn)

  def getActiveClientRelationships(taxIdentifier: TaxIdentifier, service: Service)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[ActiveRelationship]] =
    if (hipAgentRelationshipEnabled) hipConnector.getActiveClientRelationships(taxIdentifier, service)
    else ifConnector.getActiveClientRelationships(taxIdentifier, service)

  def getAllRelationships(taxIdentifier: TaxIdentifier, activeOnly: Boolean)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[RelationshipFailureResponse, Seq[ClientRelationship]]] =
    if (hipAgentRelationshipEnabled) hipConnector.getAllRelationships(taxIdentifier, activeOnly)
    else ifConnector.getAllRelationships(taxIdentifier, activeOnly)

  def getInactiveClientRelationships(taxIdentifier: TaxIdentifier, service: Service)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Seq[InactiveRelationship]] =
    if (hipAgentRelationshipEnabled) hipConnector.getInactiveClientRelationships(taxIdentifier, service)
    else ifConnector.getInactiveClientRelationships(taxIdentifier, service)

  def getInactiveRelationships(
    arn: Arn
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[InactiveRelationship]] =
    if (hipAgentRelationshipEnabled) hipConnector.getInactiveRelationships(arn)
    else ifConnector.getInactiveRelationships(arn)

  def getNinoFor(mtdId: MtdItId)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[Nino]] =
    if (hipBusinessDetailsEnabled) hipConnector.getNinoFor(mtdId)
    else ifConnector.getNinoFor(mtdId)

  def getMtdIdFor(nino: Nino)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[MtdItId]] =
    if (hipBusinessDetailsEnabled) hipConnector.getMtdIdFor(nino)
    else ifConnector.getMtdIdFor(nino)

  def getItsaBusinessDetails(nino: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[ClientDetailsFailureResponse, ItsaBusinessDetails]] =
    if (hipBusinessDetailsEnabled)
      hipConnector.getItsaBusinessDetails(nino)
    else
      ifConnector.getItsaBusinessDetails(nino)
}
