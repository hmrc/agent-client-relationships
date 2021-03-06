/*
 * Copyright 2021 HM Revenue & Customs
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
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.TypeOfEnrolment
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
  des: DesConnector,
  ifConnector: IFConnector,
  relationshipCopyRepository: RelationshipCopyRecordRepository,
  lockService: RecoveryLockService,
  deleteRecordRepository: DeleteRecordRepository,
  agentUserService: AgentUserService,
  appConfig: AppConfig,
  val metrics: Metrics)
    extends Monitoring
    with Logging {

  private val platformErrorKey = if (appConfig.iFPlatformEnabled) "IF" else "DES"

  //noinspection ScalaStyle
  def createRelationship(
    arn: Arn,
    identifier: TaxIdentifier,
    oldReferences: Set[RelationshipReference],
    failIfCreateRecordFails: Boolean,
    failIfAllocateAgentInESFails: Boolean)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[Option[Unit]] =
    lockService
      .tryLock(arn, identifier) {

        auditData.set("AgentDBRecord", false)
        auditData.set("enrolmentDelegated", false)
        auditData.set("etmpRelationshipCreated", false)

        def createRelationshipRecord: Future[Unit] = {
          val identifierType = TypeOfEnrolment(identifier).identifierKey
          val record = RelationshipCopyRecord(arn.value, identifier.value, identifierType, Some(oldReferences))
          relationshipCopyRepository
            .create(record)
            .map(_ => auditData.set("AgentDBRecord", true))
            .recoverWith {
              case NonFatal(ex) =>
                logger.warn(
                  s"Inserting relationship record into mongo failed for ${arn.value}, ${identifier.value} (${identifier.getClass.getSimpleName})",
                  ex)
                if (failIfCreateRecordFails) Future.failed(new Exception("RELATIONSHIP_CREATE_FAILED_DB"))
                else Future.successful(())
            }
        }

        for {
          _ <- createRelationshipRecord
          _ <- createEtmpRecord(arn, identifier)
          _ <- createEsRecord(arn, identifier, failIfAllocateAgentInESFails)
        } yield ()
      }

  private def createEtmpRecord(
    arn: Arn,
    identifier: TaxIdentifier)(implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[Unit] = {
    val updateEtmpSyncStatus = relationshipCopyRepository.updateEtmpSyncStatus(arn, identifier, _: SyncStatus)

    val recoverWithException = (origExc: Throwable, replacementExc: Throwable) => {
      logger.warn(s"Creating ETMP record failed for ${arn.value}, $identifier due to: ${origExc.getMessage}", origExc)
      updateEtmpSyncStatus(Failed).flatMap(_ => Future.failed(replacementExc))
    }

    (for {
      _ <- updateEtmpSyncStatus(InProgress)
      _ <- if (appConfig.iFPlatformEnabled) ifConnector.createAgentRelationship(identifier, arn)
          else des.createAgentRelationship(identifier, arn)
      _ = auditData.set("etmpRelationshipCreated", true)
      _ <- updateEtmpSyncStatus(Success)
    } yield ())
      .recoverWith {
        case e @ Upstream5xxResponse(_, upstreamCode, reportAs, headers) =>
          recoverWithException(
            e,
            UpstreamErrorResponse(s"RELATIONSHIP_CREATE_FAILED_$platformErrorKey", upstreamCode, reportAs, headers))
        case NonFatal(ex) =>
          recoverWithException(ex, new Exception(s"RELATIONSHIP_CREATE_FAILED_$platformErrorKey"))
      }
  }

  //noinspection ScalaStyle
  private def createEsRecord(arn: Arn, identifier: TaxIdentifier, failIfAllocateAgentInESFails: Boolean)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[Unit] = {

    val updateEsSyncStatus = relationshipCopyRepository.updateEsSyncStatus(arn, identifier, _: SyncStatus)

    def logAndMaybeFail(origExc: Throwable, replacementExc: Throwable): Future[Unit] = {
      logger.warn(
        s"Creating ES record failed for ${arn.value}, ${identifier.value} (${identifier.getClass.getName})",
        origExc)
      updateEsSyncStatus(Failed).flatMap { _ =>
        if (failIfAllocateAgentInESFails) Future.failed(replacementExc)
        else Future.successful(())
      }
    }

    val recoverAgentUserRelationshipNotFound: PartialFunction[Throwable, Future[Unit]] = {
      case RelationshipNotFound(errorCode) =>
        logger.warn(
          s"Creating ES record for ${arn.value}, ${identifier.value} (${identifier.getClass.getName}) " +
            s"not possible because of incomplete data: $errorCode")
        updateEsSyncStatus(IncompleteInputParams)
    }

    val recoverUpstream5xx: PartialFunction[Throwable, Future[Unit]] = {
      case e @ Upstream5xxResponse(_, upstreamCode, reportAs, headers) =>
        logAndMaybeFail(e, UpstreamErrorResponse("RELATIONSHIP_CREATE_FAILED_ES", upstreamCode, reportAs, headers))
    }

    val recoverNonFatal: PartialFunction[Throwable, Future[Unit]] = {
      case NonFatal(ex) =>
        logAndMaybeFail(ex, new Exception("RELATIONSHIP_CREATE_FAILED_ES"))
    }

    def createDeleteRecord(record: DeleteRecord): Future[Unit] =
      deleteRecordRepository
        .create(record)
        .map(_ => ())
        .recover {
          case NonFatal(ex) =>
            logger.warn(
              s"Inserting delete record into mongo failed for ${arn.value}, ${identifier.value} (${identifier.getClass.getSimpleName})",
              ex)
            ()
        }

    def removeDeleteRecord(arn: Arn, taxIdentifier: TaxIdentifier)(implicit ec: ExecutionContext): Future[Boolean] =
      deleteRecordRepository
        .remove(arn, taxIdentifier)
        .map(_ > 0)
        .recoverWith {
          case NonFatal(ex) =>
            logger.warn(
              s"Removing delete record from mongo failed for ${arn.value}, ${taxIdentifier.value} (${taxIdentifier.getClass.getSimpleName})",
              ex)
            Future.successful(false)
        }

    def deallocatePreviousRelationshipIfAny: Future[Unit] =
      for {
        existingAgents <- es.getDelegatedGroupIdsFor(identifier)
        _ <- Future.sequence(existingAgents.map { groupId =>
              (for {
                maybeArn <- es.getAgentReferenceNumberFor(groupId)
                _ <- maybeArn match {
                      case None =>
                        Future { logger.warn(s"Arn not found for provided groupId: $groupId") }
                      case Some(arnToRemove) =>
                        createDeleteRecord(
                          DeleteRecord(
                            arnToRemove.value,
                            identifier.value,
                            TypeOfEnrolment(identifier).identifierKey,
                            syncToETMPStatus = Some(Success),
                            headerCarrier = Some(hc)))
                    }
                _ <- es
                      .deallocateEnrolmentFromAgent(groupId, identifier)
                _ <- maybeArn match {
                      case None             => Future successful (())
                      case Some(removedArn) => removeDeleteRecord(removedArn, identifier)
                    }
              } yield ()).recover {
                case NonFatal(ex) =>
                  logger.error(s"Could not deallocate previous relationship because of: $ex. Will try later.")
              }
            })
      } yield ()

    (for {
      _              <- updateEsSyncStatus(InProgress)
      maybeAgentUser <- agentUserService.getAgentAdminUserFor(arn)
      agentUser = maybeAgentUser.right.getOrElse(throw RelationshipNotFound("No admin agent user found"))
      _ <- deallocatePreviousRelationshipIfAny
      _ <- es.allocateEnrolmentToAgent(agentUser.groupId, agentUser.userId, identifier, agentUser.agentCode)
      _ = auditData.set("enrolmentDelegated", true)
      _ <- updateEsSyncStatus(Success)
    } yield ())
      .recoverWith(
        recoverAgentUserRelationshipNotFound
          .orElse(recoverUpstream5xx)
          .orElse(recoverNonFatal))
  }

  def resumeRelationshipCreation(relationshipCopyRecord: RelationshipCopyRecord, arn: Arn, identifier: TaxIdentifier)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[Option[Unit]] =
    lockService
      .tryLock(arn, identifier) {
        def recoverEtmpRecord() = createEtmpRecord(arn, identifier)

        def recoverEsRecord() = createEsRecord(arn, identifier, failIfAllocateAgentInESFails = false)

        (relationshipCopyRecord.needToCreateEtmpRecord, relationshipCopyRecord.needToCreateEsRecord) match {
          case (true, true) =>
            for {
              _ <- recoverEtmpRecord()
              _ <- recoverEsRecord()
            } yield ()
          case (false, true) =>
            recoverEsRecord()
          case (true, false) =>
            logger.warn(
              s"ES relationship existed without ETMP relationship for ${arn.value}, ${identifier.value} (${identifier.getClass.getName}). " +
                s"This should not happen because we always create the ETMP relationship first,")
            recoverEtmpRecord()
          case (false, false) =>
            logger.warn(
              s"recoverRelationshipCreation called for ${arn.value}, ${identifier.value} (${identifier.getClass.getName}) when no recovery needed")
            Future.successful(())
        }
      }
}
