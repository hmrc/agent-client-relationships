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
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.stride.ClientRelationship
import uk.gov.hmrc.agentclientrelationships.support.Monitoring
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class FindRelationshipsService @Inject() (
  ifConnector: IFConnector,
  relationshipConnector: RelationshipConnector,
  appConfig: AppConfig,
  val metrics: Metrics
) extends Monitoring
    with Logging {

  def getItsaRelationshipForClient(
    nino: Nino,
    service: Service
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[ActiveRelationship]] =
    for {
      mtdItId <- ifConnector.getMtdIdFor(nino)
      relationships <-
        mtdItId.fold(Future.successful(Option.empty[ActiveRelationship]))(
          relationshipConnector.getActiveClientRelationships(_, service)
        )
    } yield relationships

  def getAllActiveItsaRelationshipForClient(
    nino: Nino,
    activeOnly: Boolean
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[RelationshipFailureResponse, Seq[ClientRelationship]]] =
    (for {
      mtdItId <- EitherT.fromOptionF(ifConnector.getMtdIdFor(nino), RelationshipFailureResponse.TaxIdentifierError)
      relationships <-
        EitherT(relationshipConnector.getAllRelationships(taxIdentifier = mtdItId, activeOnly = activeOnly))
          .leftFlatMap(recoverNotFoundRelationship)
    } yield relationships.filter(_.isActive)).value

  def getActiveRelationshipsForClient(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[ActiveRelationship]] =
    // If the tax id type is among one of the supported ones...
    if (
      appConfig.supportedServicesWithoutPir
        .map(_.supportedClientIdType.enrolmentId)
        .contains(ClientIdentifier(taxIdentifier).enrolmentId)
    )
      relationshipConnector.getActiveClientRelationships(taxIdentifier, service)
    else {
      logger.warn(s"Unsupported Identifier ${taxIdentifier.getClass.getSimpleName}")
      Future.successful(None)
    }

  // TODO WG - that have recovery
  def getAllRelationshipsForClient(
    taxIdentifier: TaxIdentifier,
    activeOnly: Boolean
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[RelationshipFailureResponse, Seq[ClientRelationship]]] =
    // If the tax id type is among one of the supported ones...
    if (
      appConfig.supportedServicesWithoutPir
        .map(_.supportedClientIdType.enrolmentId)
        .contains(ClientIdentifier(taxIdentifier).enrolmentId)
    ) {
      EitherT(relationshipConnector.getAllRelationships(taxIdentifier = taxIdentifier, activeOnly = activeOnly))
        .leftFlatMap(recoverNotFoundRelationship)
        .value

    } else {
      logger.warn(s"Unsupported Identifier ${taxIdentifier.getClass.getSimpleName}")
      Future.successful(Left(RelationshipFailureResponse.TaxIdentifierError))
    }

  private def recoverNotFoundRelationship(relationshipFailureResponse: RelationshipFailureResponse)(implicit
    ec: ExecutionContext
  ): EitherT[Future, RelationshipFailureResponse, Seq[ClientRelationship]] =
    relationshipFailureResponse match {
      case RelationshipFailureResponse.RelationshipNotFound | RelationshipFailureResponse.RelationshipSuspended =>
        EitherT.rightT[Future, RelationshipFailureResponse](Seq.empty[ClientRelationship])
      case otherError =>
        EitherT.leftT[Future, Seq[ClientRelationship]](otherError)

    }

  def getInactiveRelationshipsForAgent(
    arn: Arn
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[InactiveRelationship]] =
    relationshipConnector.getInactiveRelationships(arn)

  def getActiveRelationshipsForClient(
    identifiers: Map[Service, TaxIdentifier]
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Map[Service, Seq[Arn]]] =
    Future
      .traverse(appConfig.supportedServicesWithoutPir) { service =>
        identifiers.get(service).map(eiv => service.supportedClientIdType.createUnderlying(eiv.value)) match {
          case Some(taxId) =>
            getActiveRelationshipsForClient(taxId, service).map(_.map(r => (service, r.arn)))
          case None => Future.successful(None)
        }
      }
      .map(_.collect { case Some(x) => x }.groupBy(_._1).map { case (k, v) => (k, v.map(_._2)) })

  def getInactiveRelationshipsForClient(
    identifiers: Map[Service, TaxIdentifier]
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[InactiveRelationship]] =
    Future
      .traverse(appConfig.supportedServicesWithoutPir) { service =>
        identifiers.get(service) match {
          case Some(taxId) => getInactiveRelationshipsForClient(taxId, service)
          case None        => Future.successful(Seq.empty)
        }
      }
      .map(_.flatten)

  def getInactiveRelationshipsForClient(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[InactiveRelationship]] =
    // if it is one of the tax ids that we support...
    if (
      appConfig.supportedServicesWithoutPir.exists(
        _.supportedClientIdType.enrolmentId == ClientIdentifier(taxIdentifier).enrolmentId
      )
    ) {
      relationshipConnector.getInactiveClientRelationships(taxIdentifier, service)
    } else { // otherwise...
      logger.warn(s"Unsupported Identifier ${taxIdentifier.getClass.getSimpleName}")
      Future.successful(Seq.empty)
    }
}
