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
import play.api.Logger
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.support.Monitoring
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, CgtRef, MtdItId, Utr, Vrn}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class FindRelationshipsService @Inject()(des: DesConnector, val metrics: Metrics) extends Monitoring {

  def getItsaRelationshipForClient(
    nino: Nino)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[ActiveRelationship]] =
    for {
      mtdItId       <- des.getMtdIdFor(nino)
      relationships <- des.getActiveClientRelationships(mtdItId)
    } yield relationships

  def getActiveRelationshipsForClient(taxIdentifier: TaxIdentifier)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Option[ActiveRelationship]] =
    taxIdentifier match {
      case MtdItId(_) | Vrn(_) | Utr(_) | CgtRef(_) => des.getActiveClientRelationships(taxIdentifier)
      case e =>
        Logger(getClass).warn(s"Unsupported Identifier ${e.getClass.getSimpleName}")
        Future.successful(None)
    }

  def getInactiveRelationshipsForAgent(arn: Arn, service: String)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Seq[InactiveRelationship]] =
    des.getInactiveRelationships(arn, service)

  def getActiveRelationshipsForClient(identifiers: Map[EnrolmentService, EnrolmentIdentifierValue])(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Map[EnrolmentService, Seq[Arn]]] =
    Future
      .sequence(
        Seq(
          identifiers.get(EnrolmentMtdIt.enrolmentService).map(_.asMtdItId) match {
            case Some(mtdItId) =>
              getActiveRelationshipsForClient(mtdItId).map(_.map(r => (EnrolmentMtdIt.enrolmentService, r.arn)))
            case None => Future.successful(None)
          },
          identifiers.get(EnrolmentMtdVat.enrolmentService).map(_.asVrn) match {
            case Some(vrn) =>
              getActiveRelationshipsForClient(vrn).map(_.map(r => (EnrolmentMtdVat.enrolmentService, r.arn)))
            case None => Future.successful(None)
          },
          identifiers.get(EnrolmentTrust.enrolmentService).map(_.asUtr) match {
            case Some(utr) =>
              getActiveRelationshipsForClient(utr).map(_.map(r => (EnrolmentTrust.enrolmentService, r.arn)))
            case None => Future.successful(None)
          }
        ))
      .map(_.collect { case Some(x) => x }.groupBy(_._1).mapValues(_.map(_._2)))
}
