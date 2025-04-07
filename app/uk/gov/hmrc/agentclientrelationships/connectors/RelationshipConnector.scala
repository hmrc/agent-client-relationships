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

import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.stride.ClientRelationship
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Service}
import uk.gov.hmrc.domain.TaxIdentifier

import scala.concurrent.Future

trait RelationshipConnector {

  def createAgentRelationship(
                               enrolmentKey: EnrolmentKey,
                               arn: Arn)(implicit
      rh: RequestHeader
  ): Future[Unit]

  def deleteAgentRelationship(
                               enrolmentKey: EnrolmentKey,
                               arn: Arn)(implicit
                               rh: RequestHeader
  ): Future[Unit]

  def getActiveClientRelationship(
    taxIdentifier: TaxIdentifier,
    service: Service)(implicit
                      rh: RequestHeader
  ): Future[Option[ActiveRelationship]]

  def getAllRelationships(
    taxIdentifier: TaxIdentifier,
    activeOnly: Boolean
  )(implicit
    rh: RequestHeader
  ): Future[Either[RelationshipFailureResponse, Seq[ClientRelationship]]]

  def getInactiveClientRelationships(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit rh: RequestHeader): Future[Seq[InactiveRelationship]]

  def getInactiveRelationships(
    arn: Arn
  )(implicit rh: RequestHeader): Future[Seq[InactiveRelationship]]

}
