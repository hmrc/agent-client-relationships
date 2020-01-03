/*
 * Copyright 2020 HM Revenue & Customs
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
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax.{raiseError, returnValue}
import uk.gov.hmrc.agentclientrelationships.repository.DeleteRecordRepository
import uk.gov.hmrc.agentclientrelationships.support.{Monitoring, RelationshipNotFound}
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CheckRelationshipsService @Inject()(
  es: EnrolmentStoreProxyConnector,
  repository: DeleteRecordRepository,
  val metrics: Metrics)
    extends Monitoring {

  def checkForRelationship(taxIdentifier: TaxIdentifier, agentUser: AgentUser)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier): Future[Either[String, Boolean]] =
    for {
      allocatedGroupIds <- es.getDelegatedGroupIdsFor(taxIdentifier)
      result <- if (allocatedGroupIds.contains(agentUser.groupId)) returnValue(Right(true))
               else raiseError(RelationshipNotFound("RELATIONSHIP_NOT_FOUND"))
    } yield result

}
