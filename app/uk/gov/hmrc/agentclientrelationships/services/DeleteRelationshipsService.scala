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

import org.apache.pekko.Done
import play.api.Logging
import play.api.mvc.Request
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys._
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.DbUpdateStatus.convertDbUpdateStatus
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _, _}
import uk.gov.hmrc.agentclientrelationships.support.{Monitoring, NoRequest, RelationshipNotFound}
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.auth.core.AffinityGroup
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.time.{Instant, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

private[services] abstract class DeleteRelationshipsService(
  es: EnrolmentStoreProxyConnector,
  relationshipConnector: RelationshipConnector,
  deleteRecordRepository: DeleteRecordRepository,
  agentUserClientDetailsConnector: AgentUserClientDetailsConnector,
  lockService: MongoLockService,
  checkService: CheckRelationshipsService,
  agentUserService: AgentUserService,
  val auditService: AuditService,
  val metrics: Metrics,
  deAuthoriseInvitationService: DeAuthoriseInvitationService
)(implicit val appConfig: AppConfig, ec: ExecutionContext)
    extends Monitoring
    with Logging {

  private val recoveryTimeout: Int = appConfig.recoveryTimeout

  // noinspection ScalaStyle
  def deleteRelationship(arn: Arn, enrolmentKey: EnrolmentKey, affinityGroup: Option[AffinityGroup])(implicit
    hc: HeaderCarrier,
    request: Request[Any],
    currentUser: CurrentUser,
    auditData: AuditData = new AuditData
  ): Future[Unit] = {

    auditService.setAuditDataForTermination(arn, enrolmentKey)

    def createDeleteRecord(record: DeleteRecord): Future[DbUpdateStatus] =
      deleteRecordRepository
        .create(record)
        .map { count =>
          convertDbUpdateStatus(count)
        }
        .recoverWith { case NonFatal(ex) =>
          logger.warn(s"Inserting delete record into mongo failed for ${arn.value}, $enrolmentKey: ${ex.getMessage}")
          Future.failed(new Exception("RELATIONSHIP_DELETE_FAILED_DB"))
        }

    def delete: Future[Unit] = {
      val endedBy = determineUserTypeFromAG(affinityGroup)
      val record = DeleteRecord(
        arn.value,
        Some(enrolmentKey),
        headerCarrier = Some(hc),
        relationshipEndedBy = endedBy
      )
      for {
        recordDeletionStatus <- createDeleteRecord(record)
        if recordDeletionStatus == DbUpdateSucceeded
        esRecordDeletionStatus <- deleteEsRecord(arn, enrolmentKey)
        if esRecordDeletionStatus == DbUpdateSucceeded
        etmpRecordDeletionStatus <- deleteEtmpRecord(arn, enrolmentKey)
        if etmpRecordDeletionStatus == DbUpdateSucceeded
        removed <- removeDeleteRecord(arn, enrolmentKey)
        if removed
        _ <- setRelationshipEnded(arn, enrolmentKey, endedBy.getOrElse("HMRC"))
      } yield ()
    }

    for {
      recordOpt <- deleteRecordRepository.findBy(arn, enrolmentKey)
      _ <- recordOpt match {
             case Some(record) =>
               for {
                 isDone <- resumeRelationshipRemoval(record)
                 _ <- if (isDone) {
                        auditService.sendTerminateRelationshipAuditEvent()
                        removeDeleteRecord(arn, enrolmentKey)
                      } else Future.unit
               } yield isDone
             case None =>
               for {
                 result <- delete
                 _ = auditService.sendTerminateRelationshipAuditEvent()
               } yield result
           }
    } yield ()
  }

  private def deleteEtmpRecord(arn: Arn, enrolmentKey: EnrolmentKey)(implicit
    hc: HeaderCarrier,
    auditData: AuditData
  ): Future[DbUpdateStatus] = {
    val updateEtmpSyncStatus = deleteRecordRepository
      .updateEtmpSyncStatus(arn, enrolmentKey, _: SyncStatus)
      .map(convertDbUpdateStatus)

    val recoverFromException = (origExc: Throwable, replacementExc: Throwable) => {
      logger.warn(s"De-authorising ETMP record failed for ${arn.value}, $enrolmentKey due to: ${origExc.getMessage}")
      updateEtmpSyncStatus(Failed).flatMap(_ => Future.failed(replacementExc))
    }

    (for {
      etmpSyncStatusInProgress <- updateEtmpSyncStatus(InProgress)
      if etmpSyncStatusInProgress == DbUpdateSucceeded
      maybeResponse <- relationshipConnector.deleteAgentRelationship(
                         enrolmentKey,
                         arn
                       ) // TODO DG oneTaxIdentifier may not return what we want for CBC!
      _ = if (maybeResponse.nonEmpty) auditData.set(etmpRelationshipRemovedKey, true)
      etmpSyncStatusSuccess <- updateEtmpSyncStatus(Success)
    } yield etmpSyncStatusSuccess)
      .recoverWith {
        case e @ UpstreamErrorResponse(_, _, _, _) if e.getMessage().contains("No active relationship found") =>
          logger.warn(
            s"De-authorising ETMP record failed for ${arn.value}, $enrolmentKey due to: No active relationship found"
          )
          updateEtmpSyncStatus(Success).flatMap(_ => Future.failed(RelationshipNotFound("RELATIONSHIP_NOT_FOUND")))
        case e @ UpstreamErrorResponse(_, upstreamCode, reportAs, _) =>
          recoverFromException(e, UpstreamErrorResponse("RELATIONSHIP_DELETE_FAILED_ETMP", upstreamCode, reportAs))
        case NonFatal(ex) =>
          recoverFromException(ex, new Exception("RELATIONSHIP_DELETE_FAILED_ETMP"))
      }

  }

  // noinspection ScalaStyle
  def deleteEsRecord(arn: Arn, enrolmentKey: EnrolmentKey)(implicit
    hc: HeaderCarrier,
    auditData: AuditData
  ): Future[DbUpdateStatus] = {

    val updateEsSyncStatus = deleteRecordRepository
      .updateEsSyncStatus(arn, enrolmentKey, _: SyncStatus)
      .map(convertDbUpdateStatus)

    def logAndMaybeFail(origExc: Throwable, replacementExc: Throwable): Future[DbUpdateStatus] = {
      logger.warn(s"De-allocating ES record failed for ${arn.value}, ${enrolmentKey.tag}: ${origExc.getMessage}")
      updateEsSyncStatus(Failed)
      Future.failed(replacementExc)
    }

    lazy val recoverAgentUserRelationshipNotFound: PartialFunction[Throwable, Future[DbUpdateStatus]] = {
      case RelationshipNotFound(errorCode) =>
        logger.warn(
          s"De-allocating ES record for ${arn.value}, ${enrolmentKey.tag} " +
            s"was not possible because a relationship between the Agent and Client was not found: $errorCode"
        )
        updateEsSyncStatus(Success).flatMap(_ => Future.failed(RelationshipNotFound(errorCode)))
    }

    lazy val recoverUpstream5xx: PartialFunction[Throwable, Future[DbUpdateStatus]] = {
      case e @ UpstreamErrorResponse(_, upstreamCode, reportAs, _) if e.statusCode >= 500 && e.statusCode < 600 =>
        logAndMaybeFail(e, UpstreamErrorResponse("RELATIONSHIP_DELETE_FAILED_ES", upstreamCode, reportAs))
    }

    lazy val recoverUnauthorized: PartialFunction[Throwable, Future[DbUpdateStatus]] = {
      case ex: UpstreamErrorResponse if ex.statusCode == 401 =>
        logAndMaybeFail(ex, ex)
    }

    lazy val recoverNonFatal: PartialFunction[Throwable, Future[DbUpdateStatus]] = { case NonFatal(ex) =>
      logAndMaybeFail(ex, new Exception("RELATIONSHIP_DELETE_FAILED_ES"))
    }

    (for {
      esSyncStatusInProgress <- updateEsSyncStatus(InProgress)
      if esSyncStatusInProgress == DbUpdateSucceeded
      maybeAgentUser <- agentUserService.getAgentAdminAndSetAuditData(arn)
      agentUser = maybeAgentUser.fold(error => throw RelationshipNotFound(error), identity)
      _ <- checkService
             .checkForRelationship(arn, None, enrolmentKey)
             .flatMap {
               case true  => es.deallocateEnrolmentFromAgent(agentUser.groupId, enrolmentKey)
               case false => throw RelationshipNotFound("RELATIONSHIP_NOT_FOUND")
             }
      _ = auditData.set(enrolmentDeallocatedKey, true)
      _                   <- agentUserClientDetailsConnector.cacheRefresh(arn)
      esSyncStatusSuccess <- updateEsSyncStatus(Success)
    } yield esSyncStatusSuccess).recoverWith(
      recoverAgentUserRelationshipNotFound
        .orElse(recoverUpstream5xx)
        .orElse(recoverUnauthorized)
        .orElse(recoverNonFatal)
    )
  }

  def removeDeleteRecord(arn: Arn, enrolmentKey: EnrolmentKey): Future[Boolean] =
    deleteRecordRepository
      .remove(arn, enrolmentKey)
      .map(_ > 0)
      .recoverWith { case NonFatal(ex) =>
        logger.warn(s"Removing delete record from mongo failed for ${arn.value}, $enrolmentKey : ${ex.getMessage}")
        Future.successful(false)
      }

  def tryToResume(implicit ec: ExecutionContext, auditData: AuditData): Future[Boolean] =
    deleteRecordRepository.selectNextToRecover().flatMap {
      case Some(record) =>
        val headerCarrier = record.headerCarrier.getOrElse(HeaderCarrier())
        val enrolmentKey = record.enrolmentKey.getOrElse(enrolmentKeyFallback(record))
        checkDeleteRecordAndEventuallyResume(Arn(record.arn), enrolmentKey)(headerCarrier, auditData, NoRequest, ec)

      case None =>
        logger.info("No Delete Record Found")
        Future.successful(true)
    }

  def checkDeleteRecordAndEventuallyResume(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  )(implicit hc: HeaderCarrier, auditData: AuditData, request: Request[_], ec: ExecutionContext): Future[Boolean] =
    (for {
      recordOpt <- deleteRecordRepository.findBy(arn, enrolmentKey)
      isComplete <- recordOpt match {
                      case Some(record) =>
                        auditData.set("initialDeleteDateTime", record.dateTime)
                        auditData.set("numberOfAttempts", record.numberOfAttempts + 1)
                        if (
                          record.dateTime
                            .plusSeconds(recoveryTimeout)
                            .isAfter(Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime)
                        ) {
                          for {
                            isDone <- resumeRelationshipRemoval(record)
                            _ <- if (isDone) removeDeleteRecord(arn, enrolmentKey)
                                 else Future.successful(())
                          } yield isDone
                        } else {
                          logger.error(
                            s"Terminating recovery of failed de-authorisation $record because timeout has passed."
                          )
                          auditData.set("abandonmentReason", "timeout")
                          auditService.sendRecoveryOfDeleteRelationshipHasBeenAbandonedAuditEvent()
                          removeDeleteRecord(arn, enrolmentKey).map(_ => true)
                        }
                      case None => Future.successful(true)
                    }
    } yield isComplete)
      .recoverWith {
        case e: UpstreamErrorResponse if e.statusCode == 401 =>
          logger.error(
            s"Terminating recovery of failed de-authorisation ($arn, $enrolmentKey) because auth token is invalid"
          )
          auditData.set("abandonmentReason", "unauthorised")
          auditService.sendRecoveryOfDeleteRelationshipHasBeenAbandonedAuditEvent()
          removeDeleteRecord(arn, enrolmentKey).map(_ => true)
        case NonFatal(_) =>
          Future.successful(false)
      }

  def resumeRelationshipRemoval(
    deleteRecord: DeleteRecord
  )(implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[Boolean] = {
    val arn = Arn(deleteRecord.arn)
    val enrolmentKey: EnrolmentKey = deleteRecord.enrolmentKey.getOrElse(enrolmentKeyFallback(deleteRecord))
    lockService
      .recoveryLock(arn, enrolmentKey) {
        logger.info(
          s"Resuming unfinished removal of the relationship between ${arn.value} and $enrolmentKey. Attempt: ${deleteRecord.numberOfAttempts + 1}"
        )
        (deleteRecord.needToDeleteEtmpRecord, deleteRecord.needToDeleteEsRecord) match {
          case (true, true) =>
            for {
              _ <- deleteRecordRepository.markRecoveryAttempt(arn, enrolmentKey)
              _ <- deleteEsRecord(arn, enrolmentKey)
              _ <- deleteEtmpRecord(arn, enrolmentKey)
              _ <- setRelationshipEnded(arn, enrolmentKey, deleteRecord.relationshipEndedBy.getOrElse("HMRC"))
            } yield true
          case (false, true) =>
            logger.warn(
              s"ES relationship existed without ETMP relationship for ${arn.value}, $enrolmentKey. " +
                s"This should not happen because we always remove the ES relationship first."
            )
            for {
              _ <- deleteRecordRepository.markRecoveryAttempt(arn, enrolmentKey)
              _ = auditData.set(etmpRelationshipRemovedKey, true)
              _ <- deleteEsRecord(arn, enrolmentKey)
              _ <- setRelationshipEnded(arn, enrolmentKey, deleteRecord.relationshipEndedBy.getOrElse("HMRC"))
            } yield true
          case (true, false) =>
            for {
              _ <- deleteRecordRepository.markRecoveryAttempt(arn, enrolmentKey)
              _ = auditData.set(enrolmentDeallocatedKey, true)
              _ <- deleteEtmpRecord(arn, enrolmentKey)
              _ <- setRelationshipEnded(arn, enrolmentKey, deleteRecord.relationshipEndedBy.getOrElse("HMRC"))
            } yield true
          case (false, false) =>
            logger.warn(s"resumeRelationshipRemoval called for ${arn.value}, $enrolmentKey when no recovery needed")
            Future.successful(true)
        }
      }
      .map(_.getOrElse(false))
  }

  private[services] def enrolmentKeyFallback(deleteRecord: DeleteRecord): EnrolmentKey = {
    logger.warn("DeleteRecord did not store the whole enrolment key. Performing fallback determination of service.")
    // if the enrolment key wasn't stored, assume single identifier and make a best guess based on identifier type
    (for {
      clientIdTypeStr <- deleteRecord.clientIdentifierType
      clientIdStr     <- deleteRecord.clientIdentifier
      service <- appConfig.supportedServicesWithoutPir.find(_.supportedClientIdType.enrolmentId == clientIdTypeStr)
    } yield EnrolmentKey(
      service.id,
      Seq(Identifier(clientIdTypeStr, clientIdStr))
    )).getOrElse(
      throw new RuntimeException(
        s"Fallback determination of enrolment key failed for ${deleteRecord.clientIdentifierType}"
      )
    )
  }

  def determineUserTypeFromAG(maybeGroup: Option[AffinityGroup]): Option[String] =
    maybeGroup match {
      case Some(AffinityGroup.Individual) | Some(AffinityGroup.Organisation) => Some("Client")
      case Some(AffinityGroup.Agent)                                         => Some("Agent")
      case _                                                                 => Some("HMRC")
    }

  private def setRelationshipEnded(arn: Arn, enrolmentKey: EnrolmentKey, endedBy: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Done] =
    deAuthoriseInvitationService
      .deAuthoriseInvitation(arn, enrolmentKey, endedBy)
      .map(success =>
        if (success) Done
        else {
          logger.warn("setRelationshipEnded failed")
          Done
        }
      )

}

@Singleton
class DeleteRelationshipsServiceWithAca @Inject() (
  es: EnrolmentStoreProxyConnector,
  relationshipConnector: RelationshipConnector,
  deleteRecordRepository: DeleteRecordRepository,
  agentUserClientDetailsConnector: AgentUserClientDetailsConnector,
  lockService: MongoLockService,
  checkService: CheckRelationshipsService,
  agentUserService: AgentUserService,
  override val auditService: AuditService,
  override val metrics: Metrics,
  acaDeAuthoriseInvitationService: AcaDeAuthoriseInvitationService
)(implicit override val appConfig: AppConfig, ec: ExecutionContext)
    extends DeleteRelationshipsService(
      es,
      relationshipConnector,
      deleteRecordRepository,
      agentUserClientDetailsConnector,
      lockService,
      checkService,
      agentUserService,
      auditService,
      metrics,
      acaDeAuthoriseInvitationService
    )

@Singleton
class DeleteRelationshipsServiceWithAcr @Inject() (
  es: EnrolmentStoreProxyConnector,
  relationshipConnector: RelationshipConnector,
  deleteRecordRepository: DeleteRecordRepository,
  agentUserClientDetailsConnector: AgentUserClientDetailsConnector,
  lockService: MongoLockService,
  checkService: CheckRelationshipsService,
  agentUserService: AgentUserService,
  override val auditService: AuditService,
  override val metrics: Metrics,
  acrDeAuthoriseInvitationService: AcrDeAuthoriseInvitationService
)(implicit override val appConfig: AppConfig, ec: ExecutionContext)
    extends DeleteRelationshipsService(
      es,
      relationshipConnector,
      deleteRecordRepository,
      agentUserClientDetailsConnector,
      lockService,
      checkService,
      agentUserService,
      auditService,
      metrics,
      acrDeAuthoriseInvitationService
    )
