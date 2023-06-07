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

import com.kenshoo.play.metrics.Metrics

import javax.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.TypeOfEnrolment
import uk.gov.hmrc.agentclientrelationships.repository.DbUpdateStatus.convertDbUpdateStatus
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _, _}
import uk.gov.hmrc.agentclientrelationships.support.{Monitoring, RelationshipNotFound}
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.{HeaderCarrier, Upstream5xxResponse, UpstreamErrorResponse}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class CreateRelationshipsService @Inject()(
  es: EnrolmentStoreProxyConnector,
  ifConnector: IFConnector,
  relationshipCopyRepository: RelationshipCopyRecordRepository,
  lockService: RecoveryLockService,
  deleteRecordRepository: DeleteRecordRepository,
  agentUserService: AgentUserService,
  agentUserClientDetailsConnector: AgentUserClientDetailsConnector,
  val metrics: Metrics)
    extends Monitoring
    with Logging {

  //noinspection ScalaStyle
  def createRelationship(
    arn: Arn,
    taxIdentifier: TaxIdentifier,
    oldReferences: Set[RelationshipReference],
    failIfCreateRecordFails: Boolean,
    failIfAllocateAgentInESFails: Boolean)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[Option[DbUpdateStatus]] =
    lockService
      .tryLock(arn, taxIdentifier) {
        auditData.set("AgentDBRecord", false)
        auditData.set("enrolmentDelegated", false)
        auditData.set("etmpRelationshipCreated", false)

        def createRelationshipRecord: Future[DbUpdateStatus] = {
          val identifierKey: String = TypeOfEnrolment(taxIdentifier).identifierKey
          val record = RelationshipCopyRecord(arn.value, taxIdentifier.value, identifierKey, Some(oldReferences))
          relationshipCopyRepository
            .create(record)
            .map(count => {
              auditData.set("AgentDBRecord", true)
              convertDbUpdateStatus(count)
            })
            .recoverWith {
              case NonFatal(ex) =>
                logger.warn(
                  s"Inserting relationship record into mongo failed for ${arn.value}, ${taxIdentifier.value} (${taxIdentifier.getClass.getSimpleName})",
                  ex)
                if (failIfCreateRecordFails) Future.failed(new Exception("RELATIONSHIP_CREATE_FAILED_DB"))
                else Future.successful(DbUpdateFailed)
            }
        }

        for {
          agentUser            <- retrieveAgentUser(arn)
          recordCreationStatus <- createRelationshipRecord
          if recordCreationStatus == DbUpdateSucceeded
          etmpRecordCreationStatus <- createEtmpRecord(arn, taxIdentifier)
          if etmpRecordCreationStatus == DbUpdateSucceeded
          esRecordCreationStatus <- createEsRecord(arn, taxIdentifier, agentUser, failIfAllocateAgentInESFails)
        } yield esRecordCreationStatus
      }

  private def createEtmpRecord(arn: Arn, identifier: TaxIdentifier)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[DbUpdateStatus] = {
    val updateEtmpSyncStatus = relationshipCopyRepository
      .updateEtmpSyncStatus(arn, identifier, _: SyncStatus)
      .map(convertDbUpdateStatus)

    val recoverFromException = (origExc: Throwable, replacementExc: Throwable) => {
      logger.warn(s"Creating ETMP record failed for ${arn.value}, $identifier due to: ${origExc.getMessage}")
      updateEtmpSyncStatus(Failed).flatMap(_ => Future.failed(replacementExc))
    }

    (for {
      etmpSyncStatusInProgress <- updateEtmpSyncStatus(InProgress)
      if etmpSyncStatusInProgress == DbUpdateSucceeded
      maybeResponse <- ifConnector.createAgentRelationship(identifier, arn)
      if maybeResponse.nonEmpty
      _ = auditData.set("etmpRelationshipCreated", true)
      etmpSyncStatusSuccess <- updateEtmpSyncStatus(Success)
    } yield etmpSyncStatusSuccess)
      .recoverWith {
        case e @ Upstream5xxResponse(_, upstreamCode, reportAs, headers) =>
          recoverFromException(
            e,
            UpstreamErrorResponse(s"RELATIONSHIP_CREATE_FAILED_IF", upstreamCode, reportAs, headers))
        case NonFatal(ex) =>
          recoverFromException(ex, new Exception(s"RELATIONSHIP_CREATE_FAILED_IF"))
      }
  }

  //noinspection ScalaStyle
  private def createEsRecord(
    arn: Arn,
    identifier: TaxIdentifier,
    agentUser: AgentUser,
    failIfAllocateAgentInESFails: Boolean)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[DbUpdateStatus] = {

    def updateEsSyncStatus(status: SyncStatus): Future[DbUpdateStatus] =
      relationshipCopyRepository
        .updateEsSyncStatus(arn, identifier, status)
        .map(convertDbUpdateStatus)

    def logAndMaybeFail(origExc: Throwable, replacementExc: Throwable): Future[DbUpdateStatus] = {
      logger.warn(
        s"Creating ES record failed for ${arn.value}, ${identifier.value} (${identifier.getClass.getName})",
        origExc)
      updateEsSyncStatus(Failed).flatMap { _ =>
        if (failIfAllocateAgentInESFails) Future.failed(replacementExc)
        else Future.successful(DbUpdateFailed)
      }
    }

    val recoverAgentUserRelationshipNotFound: PartialFunction[Throwable, Future[DbUpdateStatus]] = {
      case RelationshipNotFound(errorCode) =>
        logger.warn(
          s"Creating ES record for ${arn.value}, ${identifier.value} (${identifier.getClass.getName}) " +
            s"not possible because of incomplete data: $errorCode")
        updateEsSyncStatus(IncompleteInputParams).map(_ => DbUpdateFailed)
    }

    val recoverUpstream5xx: PartialFunction[Throwable, Future[DbUpdateStatus]] = {
      case e @ Upstream5xxResponse(_, upstreamCode, reportAs, headers) =>
        logAndMaybeFail(e, UpstreamErrorResponse("RELATIONSHIP_CREATE_FAILED_ES", upstreamCode, reportAs, headers))
    }

    val recoverNonFatal: PartialFunction[Throwable, Future[DbUpdateStatus]] = {
      case NonFatal(ex) =>
        logAndMaybeFail(ex, new Exception("RELATIONSHIP_CREATE_FAILED_ES"))
    }

    (for {
      esSyncStatusInProgress <- updateEsSyncStatus(InProgress)
      if esSyncStatusInProgress == DbUpdateSucceeded
      _ <- deallocatePreviousRelationship(arn, identifier)
      _ <- es.allocateEnrolmentToAgent(agentUser.groupId, agentUser.userId, identifier, agentUser.agentCode)
      _ = auditData.set("enrolmentDelegated", true)
      _                   <- agentUserClientDetailsConnector.cacheRefresh(arn)
      esSyncStatusSuccess <- updateEsSyncStatus(Success)
    } yield esSyncStatusSuccess)
      .recoverWith(
        recoverAgentUserRelationshipNotFound
          .orElse(recoverUpstream5xx)
          .orElse(recoverNonFatal))
  }

  def deallocatePreviousRelationship(newArn: Arn, identifier: TaxIdentifier)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Unit] =
    for {
      existingAgents <- es.getDelegatedGroupIdsFor(identifier)
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
                        identifier.value,
                        TypeOfEnrolment(identifier).identifierKey,
                        syncToETMPStatus = Some(Success),
                        headerCarrier = Some(hc))
                      deleteRecordRepository
                        .create(deleteRecord)
                        .map(convertDbUpdateStatus)
                        .recover {
                          case NonFatal(ex) =>
                            logger.warn(
                              s"Inserting delete record into mongo failed for ${newArn.value}, ${identifier.value} (${identifier.getClass.getSimpleName})",
                              ex)
                            DbUpdateFailed
                        }
                  }
              _ <- es.deallocateEnrolmentFromAgent(groupId, identifier)
              _ <- maybeArn match {
                    case None => Future successful (())
                    case Some(removedArn) =>
                      deleteRecordRepository
                        .remove(removedArn, identifier)
                        .map(_ > 0)
                        .recoverWith {
                          case NonFatal(ex) =>
                            logger.warn(
                              s"Removing delete record from mongo failed for ${removedArn.value}, ${identifier.value} (${identifier.getClass.getSimpleName})",
                              ex)
                            Future.successful(false)
                        }
                  }
            } yield ()).recover {
              case NonFatal(ex) =>
                logger.error(s"Could not deallocate previous relationship because of: $ex. Will try later.")
            }
          })
    } yield ()

  private def retrieveAgentUser(
    arn: Arn)(implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[AgentUser] =
    agentUserService.getAgentAdminUserFor(arn).map {
      _.right.getOrElse(throw RelationshipNotFound(s"No admin agent user found for Arn $arn"))
    }

  def resumeRelationshipCreation(relationshipCopyRecord: RelationshipCopyRecord, arn: Arn, identifier: TaxIdentifier)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[Option[DbUpdateStatus]] =
    lockService
      .tryLock(arn, identifier) {
        (relationshipCopyRecord.needToCreateEtmpRecord, relationshipCopyRecord.needToCreateEsRecord) match {
          case (true, true) =>
            for {
              agentUser  <- retrieveAgentUser(arn)
              etmpStatus <- createEtmpRecord(arn, identifier)
              if etmpStatus == DbUpdateSucceeded
              esStatus <- createEsRecord(arn, identifier, agentUser, failIfAllocateAgentInESFails = false)
            } yield esStatus
          case (false, true) =>
            for {
              agentUser <- retrieveAgentUser(arn)
              esStatus  <- createEsRecord(arn, identifier, agentUser, failIfAllocateAgentInESFails = false)
            } yield esStatus
          case (true, false) =>
            logger.warn(
              s"ES relationship existed without ETMP relationship for ${arn.value}, ${identifier.value} (${identifier.getClass.getName}). " +
                s"This should not happen because we always create the ETMP relationship first,")
            createEtmpRecord(arn, identifier)
          case (false, false) =>
            logger.warn(
              s"recoverRelationshipCreation called for ${arn.value}, ${identifier.value} (${identifier.getClass.getName}) when no recovery needed")
            Future.successful(DbUpdateFailed)
        }
      }
}
