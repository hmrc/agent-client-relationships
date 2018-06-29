/*
 * Copyright 2018 HM Revenue & Customs
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

import com.kenshoo.play.metrics.Metrics
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax.{raiseError, returnValue}
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _}
import uk.gov.hmrc.agentclientrelationships.support.{Monitoring, RelationshipNotFound}
import uk.gov.hmrc.agentmtdidentifiers.model.{MtdItId, Vrn}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class FindRelationshipsService @Inject()(es: EnrolmentStoreProxyConnector, des: DesConnector, val metrics: Metrics)
    extends Monitoring {

  def checkForRelationship(identifier: TaxIdentifier, agentUser: AgentUser)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[Either[String, Boolean]] =
    for {
      allocatedGroupIds <- es.getDelegatedGroupIdsFor(identifier)
      result <- if (allocatedGroupIds.contains(agentUser.groupId)) returnValue(Right(true))
               else raiseError(RelationshipNotFound("RELATIONSHIP_NOT_FOUND"))
    } yield result

  def getItsaRelationshipForClient(
    clientId: MtdItId)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[ItsaRelationship]] =
    des.getActiveClientItsaRelationships(clientId)

  def getItsaRelationshipForClient(
    nino: Nino)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[ItsaRelationship]] =
    for {
      mtdItId       <- des.getMtdIdFor(nino)
      relationships <- des.getActiveClientItsaRelationships(mtdItId)
    } yield relationships

  def getVatRelationshipForClient(
    clientId: Vrn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[VatRelationship]] =
    des.getActiveClientVatRelationships(clientId)
}
