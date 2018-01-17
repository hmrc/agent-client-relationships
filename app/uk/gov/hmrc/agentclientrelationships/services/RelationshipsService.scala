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

import javax.inject.{Inject, Singleton}

import play.api.Logger
import play.api.mvc.Request
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.connectors.{DesConnector, GovernmentGatewayProxyConnector, MappingConnector, RelationshipNotFound}
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax.{raiseError, returnValue}
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.repository.{RelationshipCopyRecord, RelationshipCopyRecordRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.{AgentCode, Nino, SaAgentReference}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

sealed trait CesaCheckAndCopyResult {
  val grantAccess: Boolean
}

final case object AlreadyCopiedDidNotCheck extends CesaCheckAndCopyResult {
  override val grantAccess = false
}

final case object FoundAndCopied extends CesaCheckAndCopyResult {
  override val grantAccess = true
}

final case object FoundAndFailedToCopy extends CesaCheckAndCopyResult {
  override val grantAccess = true
}

final case object NotFound extends CesaCheckAndCopyResult {
  override val grantAccess = false
}

@Singleton
class RelationshipsService @Inject()(gg: GovernmentGatewayProxyConnector,
                                     des: DesConnector,
                                     mapping: MappingConnector,
                                     relationshipCopyRepository: RelationshipCopyRecordRepository,
                                     lockService: RecoveryLockService,
                                     auditService: AuditService) {

  private[services] val MtdItIdType = "MTDITID"

  def getAgentCodeFor(arn: Arn)
                     (implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[AgentCode] =
    for {
      credentialIdentifier <- gg.getCredIdFor(arn)
      _ = auditData.set("credId", credentialIdentifier)
      agentCode <- gg.getAgentCodeFor(credentialIdentifier)
      _ = auditData.set("agentCode", agentCode)
    } yield agentCode

  def checkForRelationship(mtdItId: MtdItId, agentCode: AgentCode)
                          (implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[Either[String, Boolean]] =
    for {
      allocatedAgents <- gg.getAllocatedAgentCodes(mtdItId)
      result <- if (allocatedAgents.contains(agentCode)) returnValue(Right(true))
                else raiseError(RelationshipNotFound("RELATIONSHIP_NOT_FOUND"))
    } yield result

  def checkCesaForOldRelationshipAndCopy(arn: Arn, mtdItId: MtdItId, eventualAgentCode: Future[AgentCode])
    (implicit ec: ExecutionContext, hc: HeaderCarrier, request: Request[Any], auditData: AuditData): Future[CesaCheckAndCopyResult] = {

    auditData.set("Journey", "CopyExistingCESARelationship")
    auditData.set("regime", "mtd-it")
    auditData.set("regimeId", mtdItId)

    relationshipCopyRepository.findBy(arn, mtdItId).flatMap {
      case Some(relationshipCopyRecord) if !relationshipCopyRecord.actionRequired =>
        Logger.warn(s"Relationship has been already been found in CESA and we have already attempted to copy to MTD")
        Future successful AlreadyCopiedDidNotCheck
      case maybeRelationshipCopyRecord@ _    =>
        for {
          nino <- des.getNinoFor(mtdItId)
          references <- lookupCesaForOldRelationship(arn, nino)
          result <- if (references.nonEmpty) {
            maybeRelationshipCopyRecord.map(
              relationshipCopyRecord => recoverRelationshipCreation(relationshipCopyRecord, arn, mtdItId, eventualAgentCode))
              .getOrElse(createRelationship(arn, mtdItId, eventualAgentCode, references, true, false)).map { _ =>
                auditService.sendCreateRelationshipAuditEvent
                FoundAndCopied
              }
              .recover { case NonFatal(ex) =>
                Logger.warn(s"Failed to copy CESA relationship for ${arn.value}, ${mtdItId.value}", ex)
                auditService.sendCreateRelationshipAuditEvent
                FoundAndFailedToCopy
              }
          } else Future.successful(NotFound)
        } yield result
    }
  }

  def lookupCesaForOldRelationship(arn: Arn, nino: Nino)
    (implicit ec: ExecutionContext, hc: HeaderCarrier, request: Request[Any], auditData: AuditData): Future[Set[SaAgentReference]] = {
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


  private def createEtmpRecord(arn: Arn, mtdItId: MtdItId)(implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[Unit] = {
    val updateEtmpSyncStatus = relationshipCopyRepository.updateEtmpSyncStatus(arn, mtdItId, _: SyncStatus)

    (for {
      _ <- updateEtmpSyncStatus(InProgress)
      _ <- des.createAgentRelationship(mtdItId, arn)
      _ = auditData.set("etmpRelationshipCreated", true)
      _ <- updateEtmpSyncStatus(Success)
    } yield ())
      .recoverWith {
        case NonFatal(ex) =>
          Logger.warn(s"Creating ETMP record failed for ${arn.value}, ${mtdItId.value}", ex)
          updateEtmpSyncStatus(Failed).flatMap(_ => Future.failed(new Exception("RELATIONSHIP_CREATE_FAILED_DES")))
      }
  }

  private def createGgRecord(
    arn: Arn,
    mtdItId: MtdItId,
    eventualAgentCode: Future[AgentCode],
    failIfAllocateAgentInGGFails: Boolean
  )(implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[Unit] = {

    val updateGgSyncStatus = relationshipCopyRepository.updateGgSyncStatus(arn, mtdItId, _: SyncStatus)
    (for {
      _ <- updateGgSyncStatus(InProgress)
      agentCode <- eventualAgentCode
      _ <- gg.allocateAgent(agentCode, mtdItId)
      _ = auditData.set("enrolmentDelegated", true)
      _ <- updateGgSyncStatus(Success)
    } yield ())
      .recoverWith {
        case RelationshipNotFound(errorCode) =>
          Logger.warn(s"Creating GG record for ${arn.value}, ${mtdItId.value} not possible because of incomplete data: $errorCode")
          updateGgSyncStatus(IncompleteInputParams)
        case NonFatal(ex) =>
          Logger.warn(s"Creating GG record failed for ${arn.value}, ${mtdItId.value}", ex)
          updateGgSyncStatus(Failed)
          if (failIfAllocateAgentInGGFails) Future.failed(new Exception("RELATIONSHIP_CREATE_FAILED_GG"))
          else Future.successful(())
      }
  }

  def createRelationship(arn: Arn,
                         mtdItId: MtdItId,
                         eventualAgentCode: Future[AgentCode],
                         oldReferences: Set[SaAgentReference],
                         failIfCreateRecordFails: Boolean,
                         failIfAllocateAgentInGGFails: Boolean
                        )
                        (implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[Unit] = {

    auditData.set("AgentDBRecord", false)
    auditData.set("enrolmentDelegated", false)
    auditData.set("etmpRelationshipCreated", false)

    def createRelationshipRecord: Future[Unit] = {
      val record = RelationshipCopyRecord(arn.value, mtdItId.value, MtdItIdType, Some(oldReferences))
      relationshipCopyRepository.create(record)
        .map(_ => auditData.set("AgentDBRecord", true))
        .recoverWith {
          case NonFatal(ex) =>
            Logger.warn(s"Inserting relationship record into mongo failed for ${arn.value}, ${mtdItId.value}", ex)
            if (failIfCreateRecordFails) Future.failed(new Exception("RELATIONSHIP_CREATE_FAILED_DB"))
            else Future.successful(())
        }
    }

    for {
      _ <- createRelationshipRecord
      _ <- createEtmpRecord(arn, mtdItId)
      _ <- createGgRecord(arn, mtdItId, eventualAgentCode, failIfAllocateAgentInGGFails)
    } yield ()
  }

  private def recoverRelationshipCreation(
    relationshipCopyRecord: RelationshipCopyRecord,
    arn: Arn, mtdItId: MtdItId,
    eventualAgentCode: Future[AgentCode])(implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[Unit] = {

    lockService.tryLock(arn, mtdItId) {
      def recoverEtmpRecord() = createEtmpRecord(arn, mtdItId)

      def recoverGgRecord() = createGgRecord(arn, mtdItId, eventualAgentCode, failIfAllocateAgentInGGFails = false)

      (relationshipCopyRecord.needToCreateEtmpRecord, relationshipCopyRecord.needToCreateGgRecord) match {
        case (true, true) =>
          for {
            _ <- recoverEtmpRecord()
            _ <- recoverGgRecord()
          } yield ()
        case (false, true) =>
          recoverGgRecord()
        case (true, false) =>
          Logger.warn(s"GG relationship existed without ETMP relationship for ${arn.value}, ${mtdItId.value}. " +
                      s"This should not happen because we always create the ETMP relationship first,")
          recoverEtmpRecord()
        case (false, false) =>
          Logger.warn(s"recoverRelationshipCreation called for ${arn.value}, ${mtdItId.value} when no recovery needed")
          Future.successful(())
      }
    }.map(_ => ())

  }


  private def intersection[A](cesaIds: Seq[A])(mappingServiceCall: => Future[Seq[A]])(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Set[A]] = {
    val cesaIdSet = cesaIds.toSet

    if (cesaIdSet.isEmpty) {
      Logger.warn("The sa references in cesa are empty.")
      returnValue(Set.empty)
    } else
        mappingServiceCall.map { mappingServiceIds =>
          val intersected = mappingServiceIds.toSet.intersect(cesaIdSet)
          Logger.info(s"The sa references in mapping store are $mappingServiceIds. The intersected value between mapping store and DES is $intersected")
          intersected
        }
  }

  def deleteRelationship(arn: Arn, mtdItId: MtdItId)(
    implicit ec: ExecutionContext, hc: HeaderCarrier, request: Request[Any], auditData: AuditData): Future[Unit] = {

    def ggDeallocation = (for {
      agentCode <- getAgentCodeFor(arn)
      _ <- checkForRelationship(mtdItId, agentCode).map(_ => gg.deallocateAgent(agentCode, mtdItId))
    } yield ()).recover {
      case ex: RelationshipNotFound =>
        Logger.warn(s"Could not delete relationship for ${arn.value}, ${mtdItId.value}: ${ex.getMessage}")
    }

    for {
      _ <- des.deleteAgentRelationship(mtdItId, arn)
      _ <- ggDeallocation
    } yield ()
  }

  def cleanCopyStatusRecord(arn: Arn, mtdItId: MtdItId)(implicit executionContext: ExecutionContext): Future[Unit] = {
    relationshipCopyRepository.remove(arn, mtdItId).flatMap { n =>
      if (n == 0) {
        Future.failed(new RelationshipNotFound("Nothing has been removed from db."))
      } else {
        Logger.warn(s"Copy status record(s) has been removed for ${arn.value}, ${mtdItId.value}: $n")
        Future.successful(())
      }
    }
  }
}
