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
import cats.implicits._
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.stride.ClientRelationship
import uk.gov.hmrc.agentclientrelationships.support.Monitoring
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.domain.TaxIdentifier
import play.api.mvc.RequestHeader
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class FindRelationshipsService @Inject() (
  ifOrHipConnector: IfOrHipConnector,
  hipConnector: HipConnector,
  appConfig: AppConfig,
  val metrics: Metrics
)(implicit executionContext: ExecutionContext)
extends Monitoring
with RequestAwareLogging {

  def getItsaRelationshipForClient(
    nino: Nino,
    service: Service
  )(implicit request: RequestHeader): Future[Option[ActiveRelationship]] =
    for {
      mtdItId <- ifOrHipConnector.getMtdIdFor(nino)
      relationships <-
        mtdItId.fold(Future.successful(Option.empty[ActiveRelationship]))(
          hipConnector.getActiveClientRelationships(_, service)
        )
    } yield relationships

  def getAllActiveItsaRelationshipForClient(
    nino: Nino,
    activeOnly: Boolean
  )(implicit request: RequestHeader): Future[Either[RelationshipFailureResponse, Seq[ClientRelationship]]] =
    (
      for {
        mtdItId <- EitherT.fromOptionF(
          ifOrHipConnector.getMtdIdFor(nino),
          RelationshipFailureResponse.TaxIdentifierError
        )
        relationships <- EitherT(hipConnector.getAllRelationships(taxIdentifier = mtdItId, activeOnly = activeOnly))
      } yield relationships.filter(_.isActive)
    ).leftFlatMap(recoverGetRelationships).value

  def getActiveRelationshipsForClient(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit request: RequestHeader): Future[Option[ActiveRelationship]] =
    // If the tax id type is among one of the supported ones...
    if (
      appConfig.supportedServicesWithoutPir
        .map(_.supportedClientIdType.enrolmentId)
        .contains(ClientIdentifier(taxIdentifier).enrolmentId)
    )
      hipConnector.getActiveClientRelationships(taxIdentifier, service)
    else {
      logger.warn(s"Unsupported Identifier ${taxIdentifier.getClass.getSimpleName}")
      Future.successful(None)
    }

  def getAllRelationshipsForClient(
    taxIdentifier: TaxIdentifier,
    activeOnly: Boolean
  )(implicit request: RequestHeader): Future[Either[RelationshipFailureResponse, Seq[ClientRelationship]]] =
    // If the tax id type is among one of the supported ones...
    if (
      appConfig.supportedServicesWithoutPir
        .map(_.supportedClientIdType.enrolmentId)
        .contains(ClientIdentifier(taxIdentifier).enrolmentId)
    ) {
      EitherT(hipConnector.getAllRelationships(taxIdentifier = taxIdentifier, activeOnly = activeOnly))
        .leftFlatMap(recoverGetRelationships)
        .value

    }
    else {
      logger.warn(s"Unsupported Identifier ${taxIdentifier.getClass.getSimpleName}")
      Future.successful(Left(RelationshipFailureResponse.TaxIdentifierError))
    }

  private def recoverGetRelationships(relationshipFailureResponse: RelationshipFailureResponse): EitherT[
    Future,
    RelationshipFailureResponse,
    Seq[ClientRelationship]
  ] =
    relationshipFailureResponse match {
      case RelationshipFailureResponse.RelationshipNotFound | RelationshipFailureResponse.RelationshipSuspended |
          RelationshipFailureResponse.TaxIdentifierError =>
        EitherT.rightT[Future, RelationshipFailureResponse](Seq.empty[ClientRelationship])
      case otherError => EitherT.leftT[Future, Seq[ClientRelationship]](otherError)
    }

  def getInactiveRelationshipsForAgent(
    arn: Arn
  )(implicit request: RequestHeader): Future[Seq[InactiveRelationship]] = hipConnector.getInactiveRelationships(arn)

  def getActiveRelationshipsForClient(
    identifiers: Map[Service, TaxIdentifier]
  )(implicit request: RequestHeader): Future[Map[Service, Seq[Arn]]] = Future
    .traverse(appConfig.supportedServicesWithoutPir) { service =>
      identifiers.get(service).map(eiv => service.supportedClientIdType.createUnderlying(eiv.value)) match {
        case Some(taxId) => getActiveRelationshipsForClient(taxId, service).map(_.map(r => (service, r.arn)))
        case None => Future.successful(None)
      }
    }
    .map(
      _.collect { case Some(x) => x }
        .groupBy(_._1)
        .map { case (k, v) => (k, v.map(_._2)) }
    )

  def getInactiveRelationshipsForClient(
    identifiers: Map[Service, TaxIdentifier]
  )(implicit request: RequestHeader): Future[Seq[InactiveRelationship]] = Future
    .traverse(appConfig.supportedServicesWithoutPir) { service =>
      identifiers.get(service) match {
        case Some(taxId) => getInactiveRelationshipsForClient(taxId, service)
        case None => Future.successful(Seq.empty)
      }
    }
    .map(_.flatten)

  def getInactiveRelationshipsForClient(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit request: RequestHeader): Future[Seq[InactiveRelationship]] =
    // if it is one of the tax ids that we support...
    if (
      appConfig.supportedServicesWithoutPir
        .exists(_.supportedClientIdType.enrolmentId == ClientIdentifier(taxIdentifier).enrolmentId)
    ) {
      hipConnector.getInactiveClientRelationships(taxIdentifier, service)
    }
    else { // otherwise...
      logger.warn(s"Unsupported Identifier ${taxIdentifier.getClass.getSimpleName}")
      Future.successful(Seq.empty)
    }

}
