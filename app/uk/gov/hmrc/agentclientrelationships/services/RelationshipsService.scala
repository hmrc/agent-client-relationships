/*
 * Copyright 2017 HM Revenue & Customs
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

import javax.inject.{Inject, Singleton}

import play.api.Logger
import uk.gov.hmrc.agentclientrelationships.connectors.{DesConnector, GovernmentGatewayProxyConnector, MappingConnector, RelationshipNotFound}
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax.returnValue
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus.SyncStatus
import uk.gov.hmrc.agentclientrelationships.repository.{RelationshipCopyRecord, RelationshipCopyRecordRepository, SyncStatus}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.{AgentCode, Nino, SaAgentReference, TaxIdentifier}
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._

import scala.concurrent.Future

@Singleton
class RelationshipsService @Inject()(gg: GovernmentGatewayProxyConnector,
                                     des: DesConnector,
                                     mapping: MappingConnector,
                                     repository: RelationshipCopyRecordRepository) {

  private val MtdItIdType = "MTDITID"

  def checkForOldRelationship(arn: Arn, identifier: TaxIdentifier, agentCode: Future[AgentCode])
                             (implicit hc: HeaderCarrier): Future[Boolean] = {
    identifier match {
      case mtdItId@MtdItId(_) => checkCesaForOldRelationshipAndCopy(arn, mtdItId, agentCode)
      case nino@Nino(_) => checkCesaForOldRelationship(arn, nino).map(_.nonEmpty)
    }
  }

  def checkCesaForOldRelationshipAndCopy(arn: Arn, mtdItId: MtdItId, agentCode: Future[AgentCode])
                                        (implicit hc: HeaderCarrier): Future[Boolean] = {
    repository.findBy(arn, mtdItId).flatMap {
      case Some(_) =>
        Logger.warn(s"Relationship has been already copied from CESA to MTD")
        Future.failed(new Exception())
      case None =>
        checkCesaForOldRelationship(arn, mtdItId).flatMap {
          case references if references.nonEmpty =>
            copyRelationship(arn, mtdItId, agentCode, references)
              .map(_ => true)
              .recover { case _ => true }
          case _ => Future.successful(false)
        }
    }
  }

  private def checkCesaForOldRelationship(arn: Arn,
                                          identifier: TaxIdentifier)(implicit hc: HeaderCarrier): Future[Set[SaAgentReference]] = {

    for {
      nino <- getNinoFor(identifier)
      references <- des.getClientSaAgentSaReferences(nino)
      matching <- intersection(references) {
        mapping.getSaAgentReferencesFor(arn)
      }
    } yield matching
  }

  private def copyRelationship(arn: Arn,
                               mtdItId: MtdItId,
                               agentCode: Future[AgentCode],
                               references: Set[SaAgentReference])(implicit hc: HeaderCarrier): Future[Unit] = {

    def createRelationshipRecord: Future[Unit] = {
      val record = RelationshipCopyRecord(arn.value, mtdItId.value, MtdItIdType, Some(references))
      repository.create(record).recoverWith {
        case ex =>
          Logger.warn(s"Inserting relationship record into mongo failed", ex)
          Future.failed(ex)
      }
    }

    val updateEtmpSyncStatus = repository.updateEtmpSyncStatus(arn, mtdItId, _: SyncStatus)
    val updateGgSyncStatus = repository.updateGgSyncStatus(arn, mtdItId, _: SyncStatus)

    def createEtmpRecord(mtdItId: MtdItId): Future[Unit] = (for {
      _ <- updateEtmpSyncStatus(SyncStatus.InProgress)
      _ <- des.createAgentRelationship(mtdItId, arn)
      _ <- updateEtmpSyncStatus(SyncStatus.Success)
    } yield ())
      .recoverWith {
        case ex =>
          Logger.warn(s"Creating ETMP record failed", ex)
          updateEtmpSyncStatus(SyncStatus.Failed)
            .flatMap(_ => Future.failed(ex))
      }

    def createGgRecord(mtdItId: MtdItId): Future[Unit] = (for {
      _ <- updateGgSyncStatus(SyncStatus.InProgress)
      agentCode <- agentCode
      _ <- gg.allocateAgent(agentCode, mtdItId)
      _ <- updateGgSyncStatus(SyncStatus.Success)
    } yield ())
      .recoverWith {
        case RelationshipNotFound(errorCode) =>
          Logger.warn(s"Creating GG record failed because of missing data with error code: $errorCode")
          updateGgSyncStatus(SyncStatus.IncompleteInputParams)
        case ex =>
          Logger.warn(s"Creating GG record failed", ex)
          updateGgSyncStatus(SyncStatus.Failed)
      }

    for {
      _ <- createRelationshipRecord
      _ <- createEtmpRecord(mtdItId)
      _ <- createGgRecord(mtdItId)
    } yield ()
  }

  private def getNinoFor(identifier: TaxIdentifier)
                        (implicit hc: HeaderCarrier): Future[Nino] = identifier match {
    case mtdItId@MtdItId(_) =>
      des.getNinoFor(mtdItId)
    case nino@Nino(_) =>
      returnValue(nino)
  }

  private def intersection[A](cesaIds: Seq[A])(mappingServiceCall: => Future[Seq[A]])(implicit hc: HeaderCarrier): Future[Set[A]] = {
    val cesaIdSet = cesaIds.toSet

    if (cesaIdSet.isEmpty) {
      Logger.warn("The sa references in cesa are empty.")
      returnValue(Set.empty)
    } else
      mappingServiceCall.map { mappingServiceIds =>
        val intersected = mappingServiceIds.toSet.intersect(cesaIdSet)
        Logger.warn(s"The sa references in mapping store are $mappingServiceIds. The intersected value between mapping store and DES is $intersected")
        intersected
      }
  }
}
