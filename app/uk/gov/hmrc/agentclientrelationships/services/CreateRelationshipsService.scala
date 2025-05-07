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

import play.api.Logging
import play.api.mvc.{Request, RequestHeader}
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys.{enrolmentDelegatedKey, etmpRelationshipCreatedKey}
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.DbUpdateStatus.convertDbUpdateStatus
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _, _}
import uk.gov.hmrc.agentclientrelationships.support.{Monitoring, RelationshipNotFound}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Service}
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport._

@Singleton
class CreateRelationshipsService @Inject() (
  es: EnrolmentStoreProxyConnector,
  hipConnector: HipConnector,
  relationshipCopyRepository: RelationshipCopyRecordRepository,
  lockService: MongoLockService,
  auditService: AuditService,
  deleteRecordRepository: DeleteRecordRepository,
  agentUserService: AgentUserService,
  agentUserClientDetailsConnector: AgentUserClientDetailsConnector,
  val metrics: Metrics
)(implicit ec: ExecutionContext)
    extends Monitoring
    with Logging {

  // noinspection ScalaStyle
  def createRelationship(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    oldReferences: Set[RelationshipReference],
    failIfCreateRecordFails: Boolean,
    failIfAllocateAgentInESFails: Boolean
  )(implicit request: RequestHeader, auditData: AuditData): Future[Option[DbUpdateStatus]] =
    lockService
      .recoveryLock(arn, enrolmentKey) {
        auditData.set(enrolmentDelegatedKey, false)
        auditData.set(etmpRelationshipCreatedKey, false)

        def createRelationshipRecord: Future[DbUpdateStatus] = {
          val record = RelationshipCopyRecord(
            arn.value,
            Some(enrolmentKey), // APB-7215 added to accommodate multiple identifiers
            references = Some(oldReferences)
          )
          relationshipCopyRepository
            .create(record)
            .map { count =>
              convertDbUpdateStatus(count)
            }
            .recoverWith { case NonFatal(ex) =>
              logger
                .warn(s"Inserting relationship record into mongo failed for ${arn.value}, ${enrolmentKey.tag}", ex)
              if (failIfCreateRecordFails) Future.failed(new Exception("RELATIONSHIP_CREATE_FAILED_DB"))
              else Future.successful(DbUpdateFailed)
            }
        }

        for {
          agentUser            <- retrieveAgentUser(arn)
          recordCreationStatus <- createRelationshipRecord
          if recordCreationStatus == DbUpdateSucceeded
          etmpRecordCreationStatus <- createEtmpRecord(arn, enrolmentKey)
          if etmpRecordCreationStatus == DbUpdateSucceeded
          esRecordCreationStatus <- createEsRecord(arn, enrolmentKey, agentUser, failIfAllocateAgentInESFails)
          _ = auditService.sendCreateRelationshipAuditEvent()
        } yield esRecordCreationStatus
      }

  private def createEtmpRecord(arn: Arn, enrolmentKey: EnrolmentKey)(implicit
    ec: ExecutionContext,
    request: RequestHeader,
    auditData: AuditData
  ): Future[DbUpdateStatus] = {
    val updateEtmpSyncStatus = relationshipCopyRepository
      .updateEtmpSyncStatus(arn, enrolmentKey, _: SyncStatus)
      .map(convertDbUpdateStatus)

    val recoverFromException = (origExc: Throwable, replacementExc: Throwable) => {
      logger.warn(s"Creating ETMP record failed for ${arn.value}, $enrolmentKey due to: ${origExc.getMessage}")
      updateEtmpSyncStatus(Failed).flatMap(_ => Future.failed(replacementExc))
    }

    (for {
      etmpSyncStatusInProgress <- updateEtmpSyncStatus(InProgress)
      if etmpSyncStatusInProgress == DbUpdateSucceeded
      maybeResponse <- hipConnector
                         .createAgentRelationship(enrolmentKey, arn)
      if maybeResponse.nonEmpty
      _ = auditData.set(etmpRelationshipCreatedKey, true)
      etmpSyncStatusSuccess <- updateEtmpSyncStatus(Success)
    } yield etmpSyncStatusSuccess)
      .recoverWith {
        case e @ UpstreamErrorResponse(_, upstreamCode, reportAs, headers)
            if e.statusCode >= 500 && e.statusCode < 600 =>
          recoverFromException(
            e,
            UpstreamErrorResponse(s"RELATIONSHIP_CREATE_FAILED_IF", upstreamCode, reportAs, headers)
          )
        case NonFatal(ex) =>
          recoverFromException(ex, new Exception(s"RELATIONSHIP_CREATE_FAILED_IF"))
      }
  }

  // noinspection ScalaStyle
  private def createEsRecord(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    agentUser: AgentUser,
    failIfAllocateAgentInESFails: Boolean
  )(implicit request: RequestHeader, auditData: AuditData): Future[DbUpdateStatus] = {

    def updateEsSyncStatus(status: SyncStatus): Future[DbUpdateStatus] =
      relationshipCopyRepository
        .updateEsSyncStatus(arn, enrolmentKey, status)
        .map(convertDbUpdateStatus)

    def logAndMaybeFail(origExc: Throwable, replacementExc: Throwable): Future[DbUpdateStatus] = {
      logger.warn(s"Creating ES record failed for ${arn.value}, ${enrolmentKey.tag}", origExc)
      updateEsSyncStatus(Failed).flatMap { _ =>
        if (failIfAllocateAgentInESFails) Future.failed(replacementExc)
        else Future.successful(DbUpdateFailed)
      }
    }

    val recoverAgentUserRelationshipNotFound: PartialFunction[Throwable, Future[DbUpdateStatus]] = {
      case RelationshipNotFound(errorCode) =>
        logger.warn(
          s"Creating ES record for ${arn.value}, ${enrolmentKey.tag} " +
            s"not possible because of incomplete data: $errorCode"
        )
        updateEsSyncStatus(IncompleteInputParams).map(_ => DbUpdateFailed)
    }

    val recoverUpstream5xx: PartialFunction[Throwable, Future[DbUpdateStatus]] = {
      case e @ UpstreamErrorResponse(_, upstreamCode, reportAs, headers) if e.statusCode >= 500 && e.statusCode < 600 =>
        logAndMaybeFail(e, UpstreamErrorResponse("RELATIONSHIP_CREATE_FAILED_ES", upstreamCode, reportAs, headers))
    }

    val recoverNonFatal: PartialFunction[Throwable, Future[DbUpdateStatus]] = { case NonFatal(ex) =>
      logAndMaybeFail(ex, new Exception("RELATIONSHIP_CREATE_FAILED_ES"))
    }

    (for {
      esSyncStatusInProgress <- updateEsSyncStatus(InProgress)
      if esSyncStatusInProgress == DbUpdateSucceeded
      _ <- if (enrolmentKey.service == Service.HMRCMTDITSUPP) Future.unit
           else deallocatePreviousRelationship(arn, enrolmentKey)
      _ <- es.allocateEnrolmentToAgent(agentUser.groupId, agentUser.userId, enrolmentKey, agentUser.agentCode)
      _ = auditData.set(enrolmentDelegatedKey, true)
      _                   <- agentUserClientDetailsConnector.cacheRefresh(arn)
      esSyncStatusSuccess <- updateEsSyncStatus(Success)
    } yield esSyncStatusSuccess)
      .recoverWith(
        recoverAgentUserRelationshipNotFound
          .orElse(recoverUpstream5xx)
          .orElse(recoverNonFatal)
      )
  }

  // noinspection ScalaStyle
  def deallocatePreviousRelationship(newArn: Arn, enrolmentKey: EnrolmentKey)(implicit
    request: RequestHeader
  ): Future[Unit] =
    for {
      existingAgents <- es.getDelegatedGroupIdsFor(enrolmentKey)
      _ <- Future.sequence(existingAgents.map { groupId =>
             (for {
               maybeArn <- es.getAgentReferenceNumberFor(groupId)
               _ <- maybeArn match {
                      case None =>
                        logger.warn(s"Arn not found for provided groupId: $groupId")
                        Future.successful(())
                      case Some(arnToRemove) =>
                        val deleteRecord = DeleteRecord(
                          arnToRemove.value,
                          Some(enrolmentKey),
                          syncToETMPStatus = Some(Success),
                          headerCarrier = Some(hc)
                        )
                        deleteRecordRepository
                          .create(deleteRecord)
                          .map(convertDbUpdateStatus)
                          .recover { case NonFatal(ex) =>
                            logger.warn(
                              s"Inserting delete record into mongo failed for ${newArn.value}, ${enrolmentKey.tag}",
                              ex
                            )
                            DbUpdateFailed
                          }
                    }
               _ <- es.deallocateEnrolmentFromAgent(groupId, enrolmentKey)
               _ <- maybeArn match {
                      case None => Future successful (())
                      case Some(removedArn) =>
                        deleteRecordRepository
                          .remove(removedArn, enrolmentKey)
                          .map { updated =>
                            if (updated > 0) {
                              auditService.auditForAgentReplacement(removedArn, enrolmentKey)
                              true
                            } else false
                          }
                          .recoverWith { case NonFatal(ex) =>
                            logger.warn(
                              s"Removing delete record from mongo failed for ${removedArn.value}, ${enrolmentKey.tag}",
                              ex
                            )
                            Future.successful(false)
                          }
                    }
             } yield ()).recover { case NonFatal(ex) =>
               logger.error(s"Could not deallocate previous relationship because of: $ex. Will try later.")
             }
           })
    } yield ()

  private def retrieveAgentUser(
    arn: Arn
  )(implicit ec: ExecutionContext, request: RequestHeader, auditData: AuditData): Future[AgentUser] =
    agentUserService.getAgentAdminAndSetAuditData(arn).map {
      _.toOption.getOrElse(throw RelationshipNotFound(s"No admin agent user found for Arn $arn"))
    }

  // This seems to only get triggered by the relationship check endpoint, as a result it doesn't seem to happen at all
  def resumeRelationshipCreation(
    relationshipCopyRecord: RelationshipCopyRecord,
    arn: Arn,
    enrolmentKey: EnrolmentKey
  )(implicit request: RequestHeader, auditData: AuditData): Future[Option[DbUpdateStatus]] =
    lockService
      .recoveryLock(arn, enrolmentKey) {
        (relationshipCopyRecord.needToCreateEtmpRecord, relationshipCopyRecord.needToCreateEsRecord) match {
          case (true, true) =>
            logger.warn(
              s"Relationship copy record found: ETMP and ES had failed status. Record dateTime: ${relationshipCopyRecord.dateTime}"
            )
            for {
              agentUser  <- retrieveAgentUser(arn)
              etmpStatus <- createEtmpRecord(arn, enrolmentKey)
              if etmpStatus == DbUpdateSucceeded
              esStatus <- createEsRecord(arn, enrolmentKey, agentUser, failIfAllocateAgentInESFails = false)
              _ = auditService.sendCreateRelationshipAuditEvent()
            } yield esStatus
          case (false, true) =>
            logger.warn(
              s"Relationship copy record found: ETMP had succeeded and ES had failed. Record dateTime: ${relationshipCopyRecord.dateTime}"
            )
            for {
              agentUser <- retrieveAgentUser(arn)
              esStatus  <- createEsRecord(arn, enrolmentKey, agentUser, failIfAllocateAgentInESFails = false)
              _ = auditService.sendCreateRelationshipAuditEvent()
            } yield esStatus
          case (true, false) =>
            logger.warn(
              s"ES relationship existed without ETMP relationship for ${arn.value}, ${enrolmentKey.tag}. " +
                s"This should not happen because we always create the ETMP relationship first. Record dateTime: ${relationshipCopyRecord.dateTime}"
            )
            createEtmpRecord(arn, enrolmentKey).map { result =>
              auditService.sendCreateRelationshipAuditEvent()
              result
            }
          case (false, false) =>
            logger.warn(
              s"recoverRelationshipCreation called for ${arn.value}, ${enrolmentKey.tag} when no recovery needed"
            )
            Future.successful(DbUpdateFailed)
        }
      }
}
