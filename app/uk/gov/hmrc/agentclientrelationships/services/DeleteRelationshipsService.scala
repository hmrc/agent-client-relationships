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
import play.api.Logging
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys._
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.audit.AuditService
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _, _}
import uk.gov.hmrc.agentclientrelationships.support.Monitoring
import uk.gov.hmrc.agentclientrelationships.support.NoRequest
import uk.gov.hmrc.agentclientrelationships.support.RelationshipNotFound
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport._
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository.endedByAgent
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository.endedByClient
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository.endedByHMRC
import uk.gov.hmrc.auth.core.AffinityGroup
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@Singleton
class DeleteRelationshipsService @Inject() (
  es: EnrolmentStoreProxyConnector,
  hipConnector: HipConnector,
  deleteRecordRepository: DeleteRecordRepository,
  agentUserClientDetailsConnector: AgentUserClientDetailsConnector,
  lockService: MongoLockService,
  checkService: CheckRelationshipsService,
  auditService: AuditService,
  val metrics: Metrics,
  invitationService: InvitationService,
  appConfig: AppConfig
)(implicit ec: ExecutionContext)
extends Monitoring
with Logging {

  private val recoveryTimeout: Int = appConfig.recoveryTimeout

  // noinspection ScalaStyle
  def deleteRelationship(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    affinityGroup: Option[AffinityGroup]
  )(implicit
    request: RequestHeader,
    currentUser: CurrentUser,
    auditData: AuditData = new AuditData
  ): Future[Boolean] = {
    auditService.setAuditDataForTermination(arn, enrolmentKey)

    def delete(): Future[Boolean] = lockService
      .recoveryLock(arn, enrolmentKey) {
        val endedBy = determineUserTypeFromAG(affinityGroup)
        val record = DeleteRecord(
          arn.value,
          enrolmentKey,
          headerCarrier = Some(hc),
          relationshipEndedBy = endedBy
        )

        for {
          groupId <- es.getPrincipalGroupIdFor(arn)
          _ <- createDeleteRecord(record)
          enrolmentDeallocated <- deallocateEsEnrolment(
            arn,
            enrolmentKey,
            groupId
          )
          etmpRelationshipRemoved <- removeEtmpRelationship(arn, enrolmentKey)
          _ <- removeDeleteRecord(arn, enrolmentKey)
          _ <- setRelationshipEnded(
            arn,
            enrolmentKey,
            endedBy.getOrElse("HMRC")
          )
        } yield {
          if (enrolmentDeallocated || etmpRelationshipRemoved)
            true
          else {
            logger.warn(s"[DeleteRelationshipsService] did not find ES or ETMP records for ${arn.value}, $enrolmentKey")
            throw RelationshipNotFound("RELATIONSHIP_NOT_FOUND")
            // TODO ideally should change deleteRelationship to return a deleteStatus ADT with the calling code handling
            // it properly, but that requires refactoring parts of the service to change current exception handling to a case match
            // keeping it as an exception in this refactor to keep the rest of the service working as is
          }
        }
      }.map(_.getOrElse(false))

    for {
      recordOpt <- deleteRecordRepository.findBy(arn, enrolmentKey)
      isDone <-
        recordOpt match {
          case Some(record) => resumeRelationshipRemoval(record)
          case None => delete()
        }
      _ =
        if (isDone)
          auditService.sendTerminateRelationshipAuditEvent()
    } yield isDone
  }

  private def removeEtmpRelationship(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  )(implicit
    rh: RequestHeader,
    auditData: AuditData
  ): Future[Boolean] = {
    val updateEtmpSyncStatus =
      deleteRecordRepository
        .updateEtmpSyncStatus(
          arn,
          enrolmentKey,
          _: SyncStatus
        )

    (
      for {
        _ <- updateEtmpSyncStatus(InProgress)
        etmpRelationshipRemoved <- hipConnector.deleteAgentRelationship(
          enrolmentKey,
          arn
        ).map(_.nonEmpty)
        _ = auditData.set(etmpRelationshipRemovedKey, etmpRelationshipRemoved)
        _ =
          if (!etmpRelationshipRemoved)
            logger.warn(s"[DeleteRelationshipsService] ETMP record not found for ${arn.value}, $enrolmentKey")
        _ <- updateEtmpSyncStatus(Success)
      } yield etmpRelationshipRemoved
    ).recoverWith {
      case ex =>
        logger.warn(s"[DeleteRelationshipsService] De-authorising ETMP record failed for ${arn.value}, $enrolmentKey due to: ${ex.getMessage}")
        updateEtmpSyncStatus(Failed).map(_ => throw ex)
    }

  }

  private def deallocateEsEnrolment(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    groupId: String
  )(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[Boolean] = {
    val updateEsSyncStatus =
      deleteRecordRepository
        .updateEsSyncStatus(
          arn,
          enrolmentKey,
          _: SyncStatus
        )

    (
      for {
        _ <- updateEsSyncStatus(InProgress)
        enrolmentDeallocated <- checkService
          .checkForRelationship(
            arn,
            None,
            enrolmentKey
          )
          .flatMap {
            case true => es.deallocateEnrolmentFromAgent(groupId, enrolmentKey).map(_ => true)
            case false => Future.successful(false)
          }
        _ = auditData.set(enrolmentDeallocatedKey, enrolmentDeallocated)
        _ = agentUserClientDetailsConnector.cacheRefresh(arn)
        _ =
          if (!enrolmentDeallocated)
            logger.warn(s"[DeleteRelationshipsService] ES record not found for ${arn.value}, $enrolmentKey")
        _ <- updateEsSyncStatus(Success)
      } yield enrolmentDeallocated
    ).recoverWith {
      case ex =>
        logger.warn(s"[DeleteRelationshipsService] De-allocating ES record failed for ${arn.value}, ${enrolmentKey.tag}: ${ex.getMessage}")
        updateEsSyncStatus(Failed).map(_ => throw ex)
    }
  }

  def createDeleteRecord(record: DeleteRecord): Future[Done] = deleteRecordRepository
    .create(record)
    .recoverWith { case ex =>
      logger.warn(s"[DeleteRelationshipsService] Inserting delete record into mongo failed for ${record.arn}, ${record.enrolmentKey}: ${ex.getMessage}")
      Future.failed(new Exception("RELATIONSHIP_DELETE_FAILED_DB"))
    }

  def removeDeleteRecord(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  ): Future[Boolean] = deleteRecordRepository
    .remove(arn, enrolmentKey)
    .map(_ > 0)
    .recoverWith { case NonFatal(ex) =>
      logger.warn(s"[DeleteRelationshipsService] Removing delete record from mongo failed for ${arn.value}, $enrolmentKey : ${ex.getMessage}")
      Future.successful(false)
    }

  def tryToResume(implicit
    auditData: AuditData
  ): Future[Boolean] = deleteRecordRepository
    .selectNextToRecover()
    .flatMap {
      case Some(record) =>
        val enrolmentKey = record.enrolmentKey
        checkDeleteRecordAndEventuallyResume(Arn(record.arn), enrolmentKey)(NoRequest, auditData)
      case None => Future.successful(true)
    }

  def checkDeleteRecordAndEventuallyResume(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  )(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[Boolean] =
    (
      for {
        recordOpt <- deleteRecordRepository.findBy(arn, enrolmentKey)
        isComplete <-
          recordOpt match {
            case Some(record) =>
              auditData.set("initialDeleteDateTime", record.dateTime)
              auditData.set("numberOfAttempts", record.numberOfAttempts + 1)
              if (
                record.dateTime
                  .plusSeconds(recoveryTimeout)
                  .isAfter(Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime)
              ) {
                resumeRelationshipRemoval(record)
              }
              else {
                logger.error(s"[DeleteRelationshipsService] Terminating recovery of failed de-authorisation $record because timeout has passed.")
                auditData.set("abandonmentReason", "timeout")
                auditService.sendRecoveryOfDeleteRelationshipHasBeenAbandonedAuditEvent()
                removeDeleteRecord(arn, enrolmentKey).map(_ => true)
              }
            case None => Future.successful(true)
          }
      } yield isComplete
    ).recoverWith {
      case e: UpstreamErrorResponse if e.statusCode == 401 =>
        logger.error(
          s"[DeleteRelationshipsService] Terminating recovery of failed de-authorisation ($arn, $enrolmentKey) because auth token is invalid"
        )
        auditData.set("abandonmentReason", "unauthorised")
        auditService.sendRecoveryOfDeleteRelationshipHasBeenAbandonedAuditEvent()
        removeDeleteRecord(arn, enrolmentKey).map(_ => true)
      case NonFatal(_) => Future.successful(false)
    }

  // noinspection ScalaStyle
  def resumeRelationshipRemoval(deleteRecord: DeleteRecord)(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[Boolean] = {
    val arn = Arn(deleteRecord.arn)
    val enrolmentKey: EnrolmentKey = deleteRecord.enrolmentKey
    lockService
      .recoveryLock(arn, enrolmentKey) {
        logger.info(
          s"[DeleteRelationshipsService] Resuming unfinished removal of the relationship between ${arn.value} and $enrolmentKey. " +
            s"Attempt: ${deleteRecord.numberOfAttempts + 1}"
        )
        (deleteRecord.needToDeleteEtmpRecord, deleteRecord.needToDeleteEsRecord) match {
          case (true, true) =>
            for {
              _ <- deleteRecordRepository.markRecoveryAttempt(arn, enrolmentKey)
              groupId <- es.getPrincipalGroupIdFor(arn)
              _ <- deallocateEsEnrolment(
                arn,
                enrolmentKey,
                groupId
              )
              _ <- removeEtmpRelationship(arn, enrolmentKey)
              _ <- removeDeleteRecord(arn, enrolmentKey)
              _ <- setRelationshipEnded(
                arn,
                enrolmentKey,
                deleteRecord.relationshipEndedBy.getOrElse("HMRC")
              )
            } yield true
          case (false, true) =>
            logger.warn(
              s"[DeleteRelationshipsService] ES relationship existed without ETMP relationship for ${arn.value}, $enrolmentKey." +
                s" This should not happen because we always remove the ES relationship first."
            )
            for {
              _ <- deleteRecordRepository.markRecoveryAttempt(arn, enrolmentKey)
              _ = auditData.set(etmpRelationshipRemovedKey, deleteRecord.syncToETMPStatus.contains(Success))
              groupId <- es.getPrincipalGroupIdFor(arn)
              _ <- deallocateEsEnrolment(
                arn,
                enrolmentKey,
                groupId
              )
              _ <- removeDeleteRecord(arn, enrolmentKey)
              _ <- setRelationshipEnded(
                arn,
                enrolmentKey,
                deleteRecord.relationshipEndedBy.getOrElse("HMRC")
              )
            } yield true
          case (true, false) =>
            for {
              _ <- deleteRecordRepository.markRecoveryAttempt(arn, enrolmentKey)
              _ = auditData.set(enrolmentDeallocatedKey, deleteRecord.syncToESStatus.contains(Success))
              _ <- removeEtmpRelationship(arn, enrolmentKey)
              _ <- removeDeleteRecord(arn, enrolmentKey)
              _ <- setRelationshipEnded(
                arn,
                enrolmentKey,
                deleteRecord.relationshipEndedBy.getOrElse("HMRC")
              )
            } yield true
          case (false, false) =>
            logger.warn(s"[DeleteRelationshipsService] resumeRelationshipRemoval called for ${arn.value}, $enrolmentKey when no recovery needed")
            removeDeleteRecord(arn, enrolmentKey).map(_ => true)
        }
      }
      .map(_.getOrElse(false))
  }

  def determineUserTypeFromAG(maybeGroup: Option[AffinityGroup]): Option[String] =
    maybeGroup match {
      case Some(AffinityGroup.Individual) | Some(AffinityGroup.Organisation) => Some(endedByClient)
      case Some(AffinityGroup.Agent) => Some(endedByAgent)
      case _ => Some(endedByHMRC)
    }

  def setRelationshipEnded(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    endedBy: String
  ): Future[Done] = invitationService
    .deauthoriseInvitation(
      arn,
      enrolmentKey,
      endedBy
    )
    .map(success =>
      if (success)
        Done
      else {
        logger.warn("setRelationshipEnded failed")
        Done
      }
    )

}
