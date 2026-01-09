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

package uk.gov.hmrc.agentclientrelationships.services

import org.apache.pekko.Done
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys.enrolmentDelegatedKey
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys.etmpRelationshipCreatedKey
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.audit.AuditService
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.repository._
import uk.gov.hmrc.agentclientrelationships.support.RelationshipNotFound
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport._
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@Singleton
class CreateRelationshipsService @Inject() (
  es: EnrolmentStoreProxyConnector,
  hipConnector: HipConnector,
  relationshipCopyRepository: RelationshipCopyRecordRepository,
  lockService: MongoLockService,
  auditService: AuditService,
  deleteRecordRepository: DeleteRecordRepository,
  agentUserService: AgentUserService,
  agentUserClientDetailsConnector: AgentUserClientDetailsConnector
)(implicit ec: ExecutionContext)
extends RequestAwareLogging {

  // noinspection ScalaStyle
  def createRelationship(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    oldReferences: Set[RelationshipReference],
    failIfAllocateAgentInESFails: Boolean
  )(implicit
    request: RequestHeader,
    auditData: AuditData = new AuditData()
  ): Future[Option[Done]] =
    lockService.recoveryLock(arn, enrolmentKey) {
      auditData.set(enrolmentDelegatedKey, false)
      auditData.set(etmpRelationshipCreatedKey, false)

      val isCopyAcrossRelationship = oldReferences.nonEmpty

      def createRelationshipRecord: Future[Done] = {
        if (isCopyAcrossRelationship) {
          val record = RelationshipCopyRecord(
            arn.value,
            enrolmentKey,
            references = Some(oldReferences)
          )
          relationshipCopyRepository.create(record)
        }
        else {
          Future.successful(Done)
        }
      }

      for {
        agentUser <- retrieveAgentUser(arn)
        _ <- createRelationshipRecord
        _ <- createEtmpRecord(
          arn,
          enrolmentKey,
          isCopyAcrossRelationship
        )
        _ <- createEsRecord(
          arn,
          enrolmentKey,
          agentUser,
          failIfAllocateAgentInESFails,
          isCopyAcrossRelationship
        )
        _ = auditService.sendCreateRelationshipAuditEvent()
      } yield Done
    }

  private def createEtmpRecord(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    isCopyAcrossRelationship: Boolean
  )(implicit
    ec: ExecutionContext,
    request: RequestHeader,
    auditData: AuditData
  ): Future[Done] = {

    def updateEtmpSyncStatus(status: SyncStatus): Future[Done] =
      if (isCopyAcrossRelationship) {
        relationshipCopyRepository.updateEtmpSyncStatus(
          arn,
          enrolmentKey,
          status
        )
      }
      else
        Future.successful(Done)

    (
      for {
        _ <- updateEtmpSyncStatus(InProgress)
        _ <- hipConnector.createAgentRelationship(enrolmentKey, arn)
        _ = auditData.set(etmpRelationshipCreatedKey, true)
        _ <- updateEtmpSyncStatus(Success)
      } yield Done
    ).recoverWith {
      case ex =>
        logger.warn(s"[CreateRelationshipsService] Creating ETMP record failed for ${arn.value}, $enrolmentKey due to: ${ex.getMessage}")
        updateEtmpSyncStatus(Failed).map(_ => throw ex)
    }
  }

  private def createEsRecord(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    agentUser: AgentUser,
    failIfAllocateAgentInESFails: Boolean,
    isCopyAcrossRelationship: Boolean
  )(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[Done] = {

    def updateEsSyncStatus(status: SyncStatus): Future[Done] =
      if (isCopyAcrossRelationship) {
        relationshipCopyRepository
          .updateEsSyncStatus(
            arn,
            enrolmentKey,
            status
          )
      }
      else
        Future.successful(Done)

    (
      for {
        _ <- updateEsSyncStatus(InProgress)
        _ <-
          if (enrolmentKey.service == Service.HMRCMTDITSUPP)
            Future.unit
          else
            deallocatePreviousRelationship(arn, enrolmentKey)
        _ <- es.allocateEnrolmentToAgent(
          agentUser.groupId,
          agentUser.userId,
          enrolmentKey,
          agentUser.agentCode
        )
        _ = auditData.set(enrolmentDelegatedKey, true)
        _ = agentUserClientDetailsConnector.cacheRefresh(arn)
        _ <- updateEsSyncStatus(Success)
      } yield Done
    ).recoverWith {
      case ex =>
        logger.warn(s"[CreateRelationshipsService] Creating ES record failed for ${arn.value}, $enrolmentKey due to: ${ex.getMessage}")
        updateEsSyncStatus(Failed).map(_ =>
          if (failIfAllocateAgentInESFails)
            throw ex
          else
            Done
        )
    }
  }

  // noinspection ScalaStyle
  def deallocatePreviousRelationship(
    newArn: Arn,
    enrolmentKey: EnrolmentKey
  )(implicit request: RequestHeader): Future[Boolean] =
    for {
      existingAgents <- es.getDelegatedGroupIdsFor(enrolmentKey)
      deallocateResult <- Future.sequence(
        existingAgents.map { groupId =>
          (
            for {
              maybeArn <- es.getAgentReferenceNumberFor(groupId)
              _ <-
                maybeArn match {
                  case None =>
                    logger.warn(s"[CreateRelationshipsService] Arn not found for provided groupId: $groupId")
                    Future.unit
                  case Some(arnToRemove) =>
                    val deleteRecord = DeleteRecord(
                      arnToRemove.value,
                      enrolmentKey,
                      syncToETMPStatus = Some(Success),
                      headerCarrier = Some(hc)
                    )
                    deleteRecordRepository
                      .create(deleteRecord)
                      .recover { case NonFatal(ex) =>
                        logger.warn(
                          s"[CreateRelationshipsService] Inserting delete record into mongo failed for ${newArn.value}, ${enrolmentKey.tag}",
                          ex
                        )
                        false
                      }
                }
              _ <- es.deallocateEnrolmentFromAgent(groupId, enrolmentKey)
              _ <-
                maybeArn match {
                  case None => Future.unit
                  case Some(removedArn) =>
                    deleteRecordRepository
                      .remove(removedArn, enrolmentKey)
                      .map { updated =>
                        agentUserClientDetailsConnector.cacheRefresh(removedArn)
                        if (updated > 0) {
                          auditService.auditForAgentReplacement(removedArn, enrolmentKey)
                          true
                        }
                        else
                          false
                      }
                      .recover { case NonFatal(ex) =>
                        logger.warn(
                          s"[CreateRelationshipsService] Removing delete record from mongo failed for ${removedArn.value}, ${enrolmentKey.tag}",
                          ex
                        )
                        false
                      }
                }
            } yield true
          ).recover { case NonFatal(ex) =>
            logger.error(s"[CreateRelationshipsService] Could not deallocate previous relationship because of: $ex. Will try later.")
            false
          }
        }
      )
    } yield deallocateResult.contains(true)

  private def retrieveAgentUser(arn: Arn)(implicit
    ec: ExecutionContext,
    request: RequestHeader,
    auditData: AuditData
  ): Future[AgentUser] = agentUserService
    .getAgentAdminAndSetAuditData(arn)
    .map {
      _.toOption.getOrElse(throw RelationshipNotFound(s"[CreateRelationshipsService] No admin agent user found for Arn $arn"))
    }

  // noinspection ScalaStyle
  // Only triggered for copy across
  def resumeRelationshipCreation(
    relationshipCopyRecord: RelationshipCopyRecord,
    arn: Arn,
    enrolmentKey: EnrolmentKey
  )(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[Option[Done]] =
    lockService.recoveryLock(arn, enrolmentKey) {
      (relationshipCopyRecord.needToCreateEtmpRecord, relationshipCopyRecord.needToCreateEsRecord) match {
        case (true, true) =>
          logger.warn(
            s"[CreateRelationshipsService] Relationship copy record found: ETMP and ES had failed status. Record dateTime: ${relationshipCopyRecord.dateTime}"
          )
          for {
            agentUser <- retrieveAgentUser(arn)
            _ <- createEtmpRecord(
              arn,
              enrolmentKey,
              isCopyAcrossRelationship = true
            )
            _ <- createEsRecord(
              arn,
              enrolmentKey,
              agentUser,
              failIfAllocateAgentInESFails = false,
              isCopyAcrossRelationship = true
            )
            _ = auditService.sendCreateRelationshipAuditEvent()
          } yield Done
        case (false, true) =>
          logger.warn(
            s"[CreateRelationshipsService] Relationship copy record found: ETMP had succeeded and ES had failed. Record dateTime: ${relationshipCopyRecord.dateTime}"
          )
          for {
            agentUser <- retrieveAgentUser(arn)
            _ <- createEsRecord(
              arn,
              enrolmentKey,
              agentUser,
              failIfAllocateAgentInESFails = false,
              isCopyAcrossRelationship = true
            )
            _ = auditService.sendCreateRelationshipAuditEvent()
          } yield Done
        case (true, false) =>
          logger.warn(
            s"[CreateRelationshipsService] ES relationship existed without ETMP relationship for ${arn.value}, ${enrolmentKey.tag}. " +
              s"This should not happen because we always create the ETMP relationship first. Record dateTime: ${relationshipCopyRecord.dateTime}"
          )
          createEtmpRecord(
            arn,
            enrolmentKey,
            isCopyAcrossRelationship = true
          ).map { result =>
            auditService.sendCreateRelationshipAuditEvent()
            result
          }
        case (false, false) =>
          logger.warn(
            s"[CreateRelationshipsService] recoverRelationshipCreation called for ${arn.value}, ${enrolmentKey.tag} when no recovery needed"
          )
          Future.successful(Done)
      }
    }

}
