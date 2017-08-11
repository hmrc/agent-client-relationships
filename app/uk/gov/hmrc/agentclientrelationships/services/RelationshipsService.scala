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
import play.api.mvc.Request
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.connectors.{DesConnector, GovernmentGatewayProxyConnector, MappingConnector, RelationshipNotFound}
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax.{raiseError, returnValue}
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus.SyncStatus
import uk.gov.hmrc.agentclientrelationships.repository.{RelationshipCopyRecord, RelationshipCopyRecordRepository, SyncStatus}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.{AgentCode, Nino, SaAgentReference}
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class RelationshipsService @Inject()(gg: GovernmentGatewayProxyConnector,
                                     des: DesConnector,
                                     mapping: MappingConnector,
                                     relationshipCopyRepository: RelationshipCopyRecordRepository,
                                     auditService: AuditService) {

  private[services] val MtdItIdType = "MTDITID"

  def getAgentCodeFor(arn: Arn)
                     (implicit hc: HeaderCarrier, auditData: AuditData): Future[AgentCode] =
    for {
      credentialIdentifier <- gg.getCredIdFor(arn)
      _ = auditData.set("credId", credentialIdentifier)
      agentCode <- gg.getAgentCodeFor(credentialIdentifier)
      _ = auditData.set("agentCode", agentCode)
    } yield agentCode

  def checkForRelationship(mtdItId: MtdItId, agentCode: AgentCode)
                          (implicit hc: HeaderCarrier, auditData: AuditData): Future[Either[String, Boolean]] =
    for {
      allocatedAgents <- gg.getAllocatedAgentCodes(mtdItId)
      result <- if (allocatedAgents.contains(agentCode)) returnValue(Right(true))
                else raiseError(RelationshipNotFound("RELATIONSHIP_NOT_FOUND"))
    } yield result

  def checkCesaForOldRelationshipAndCopy(arn: Arn, mtdItId: MtdItId, agentCode: Future[AgentCode])
                                        (implicit hc: HeaderCarrier, request: Request[Any], auditData: AuditData): Future[Boolean] = {

    auditData.set("Journey", "CopyExistingCESARelationship")
    auditData.set("regime", "mtd-it")
    auditData.set("regimeId", mtdItId)

    relationshipCopyRepository.findBy(arn, mtdItId).flatMap {
      case Some(relationshipCopyRecord) if !relationshipCopyRecord.actionRequired =>
        Logger.warn(s"Relationship has been already been found in CESA and we have already attempted to copy to MTD")
        Future.failed(new Exception())
      case _    =>
        for {
          nino <- des.getNinoFor(mtdItId)
          references <- lookupCesaForOldRelationship(arn, nino)
          result <- if (references.nonEmpty) {
            createRelationship(arn, mtdItId, agentCode, references, true, false)
              .map { _ =>
                auditService.sendCreateRelationshipAuditEvent
                true
              }
              .recover { case _ =>
                auditService.sendCreateRelationshipAuditEvent
                true
              }
          } else Future.successful(false)
        } yield result
    }
  }

  def lookupCesaForOldRelationship(arn: Arn, nino: Nino)
                                  (implicit hc: HeaderCarrier, request: Request[Any], auditData: AuditData): Future[Set[SaAgentReference]] = {
    auditData.set("nino", nino)
    for {
      references <- des.getClientSaAgentSaReferences(nino)
      matching <- intersection(references) {
        mapping.getSaAgentReferencesFor(arn)
      }
      _ = auditData.set("saAgentRef", matching.mkString(","))
      _ = auditData.set("CESARelationship", matching.nonEmpty)
    } yield {
      auditService.sendCheckCESAAuditEvent
      matching
    }
  }

  //noinspection ScalaStyle
  def createRelationship(arn: Arn,
                         mtdItId: MtdItId,
                         agentCode: Future[AgentCode],
                         oldReferences: Set[SaAgentReference],
                         failIfCreateRecordFails: Boolean,
                         failIfAllocateAgentInGGFails: Boolean
                        )
                        (implicit hc: HeaderCarrier, auditData: AuditData): Future[Unit] = {

    auditData.set("AgentDBRecord", false)
    auditData.set("enrolmentDelegated", false)
    auditData.set("etmpRelationshipCreated", false)

    def createRelationshipRecord: Future[Unit] = {
      val record = RelationshipCopyRecord(arn.value, mtdItId.value, MtdItIdType, Some(oldReferences))
      relationshipCopyRepository.create(record)
        .map(_ => auditData.set("AgentDBRecord", true))
        .recoverWith {
          case ex =>
            Logger.warn(s"Inserting relationship record into mongo failed", ex)
            if (failIfCreateRecordFails) Future.failed(new Exception("RELATIONSHIP_CREATE_FAILED_DB"))
            else Future.successful(())
        }
    }

    val updateEtmpSyncStatus = relationshipCopyRepository.updateEtmpSyncStatus(arn, mtdItId, _: SyncStatus)
    val updateGgSyncStatus = relationshipCopyRepository.updateGgSyncStatus(arn, mtdItId, _: SyncStatus)

    def createEtmpRecord(mtdItId: MtdItId): Future[Unit] = (for {
      _ <- updateEtmpSyncStatus(SyncStatus.InProgress)
      _ <- des.createAgentRelationship(mtdItId, arn)
      _ = auditData.set("etmpRelationshipCreated", true)
      _ <- updateEtmpSyncStatus(SyncStatus.Success)
    } yield ())
      .recoverWith {
        case ex =>
          Logger.warn(s"Creating ETMP record failed", ex)
          updateEtmpSyncStatus(SyncStatus.Failed)
            .flatMap(_ => Future.failed(new Exception("RELATIONSHIP_CREATE_FAILED_DES")))
      }

    def createGgRecord(mtdItId: MtdItId): Future[Unit] = (for {
      _ <- updateGgSyncStatus(SyncStatus.InProgress)
      agentCode <- agentCode
      _ <- gg.allocateAgent(agentCode, mtdItId)
      _ = auditData.set("enrolmentDelegated", true)
      _ <- updateGgSyncStatus(SyncStatus.Success)
    } yield ())
      .recoverWith {
        case RelationshipNotFound(errorCode) =>
          Logger.warn(s"Creating GG record not possible because of incomplete data: $errorCode")
          updateGgSyncStatus(SyncStatus.IncompleteInputParams)
        case ex                              =>
          Logger.warn(s"Creating GG record failed", ex)
          updateGgSyncStatus(SyncStatus.Failed)
          if (failIfAllocateAgentInGGFails) Future.failed(new Exception("RELATIONSHIP_CREATE_FAILED_GG"))
          else Future.successful(())
      }

    for {
      _ <- createRelationshipRecord
      _ <- createEtmpRecord(mtdItId)
      _ <- createGgRecord(mtdItId)
    } yield ()
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

  def deleteRelationship(arn: Arn, mtdItId: MtdItId)(
    implicit hc: HeaderCarrier, request: Request[Any], auditData: AuditData): Future[Unit] = {
    for {
      agentCode <- getAgentCodeFor(arn)
      _ <- des.deleteAgentRelationship(mtdItId, arn)
      _ <- gg.deallocateAgent(agentCode, mtdItId)
    } yield ()
  }

  def cleanCopyStatusRecord(arn: Arn, mtdItId: MtdItId)(implicit executionContext: ExecutionContext): Future[Unit] = {
    relationshipCopyRepository.remove(arn, mtdItId).flatMap { n =>
      if (n == 0) {
        Future.failed(new Exception("Nothing has been removed from db."))
      } else {
        Logger.warn(s"Copy status record(s) has been removed: $n")
        Future.successful(())
      }
    }
  }
}
