/*
 * Copyright 2020 HM Revenue & Customs
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
import play.api.Logger
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.TypeOfEnrolment
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _, _}
import uk.gov.hmrc.agentclientrelationships.support.{Monitoring, RelationshipNotFound}
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.{HeaderCarrier, Upstream5xxResponse}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class CreateRelationshipsService @Inject()(
  es: EnrolmentStoreProxyConnector,
  des: DesConnector,
  relationshipCopyRepository: RelationshipCopyRecordRepository,
  lockService: RecoveryLockService,
  deleteRecordRepository: DeleteRecordRepository,
  agentUserService: AgentUserService,
  val metrics: Metrics)
    extends Monitoring {

  //noinspection ScalaStyle
  def createRelationship(
    arn: Arn,
    identifier: TaxIdentifier,
    oldReferences: Set[RelationshipReference],
    failIfCreateRecordFails: Boolean,
    failIfAllocateAgentInESFails: Boolean)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[Unit] = {

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
            Logger(getClass).warn(
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
      Logger(getClass).warn(
        s"Creating ETMP record failed for ${arn.value}, ${identifier.value} (${identifier.getClass.getName})",
        origExc)
      updateEtmpSyncStatus(Failed).flatMap(_ => Future.failed(replacementExc))
    }

    (for {
      _ <- updateEtmpSyncStatus(InProgress)
      _ <- des.createAgentRelationship(identifier, arn)
      _ = auditData.set("etmpRelationshipCreated", true)
      _ <- updateEtmpSyncStatus(Success)
    } yield ())
      .recoverWith {
        case e @ Upstream5xxResponse(_, upstreamCode, reportAs) =>
          recoverWithException(e, Upstream5xxResponse("RELATIONSHIP_CREATE_FAILED_DES", upstreamCode, reportAs))
        case NonFatal(ex) =>
          recoverWithException(ex, new Exception("RELATIONSHIP_CREATE_FAILED_DES"))
      }
  }

  //noinspection ScalaStyle
  private def createEsRecord(arn: Arn, identifier: TaxIdentifier, failIfAllocateAgentInESFails: Boolean)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[Unit] = {

    val updateEsSyncStatus = relationshipCopyRepository.updateEsSyncStatus(arn, identifier, _: SyncStatus)

    def logAndMaybeFail(origExc: Throwable, replacementExc: Throwable): Future[Unit] = {
      Logger(getClass).warn(
        s"Creating ES record failed for ${arn.value}, ${identifier.value} (${identifier.getClass.getName})",
        origExc)
      updateEsSyncStatus(Failed).flatMap { _ =>
        if (failIfAllocateAgentInESFails) Future.failed(replacementExc)
        else Future.successful(())
      }
    }

    val recoverAgentUserRelationshipNotFound: PartialFunction[Throwable, Future[Unit]] = {
      case RelationshipNotFound(errorCode) =>
        Logger(getClass).warn(
          s"Creating ES record for ${arn.value}, ${identifier.value} (${identifier.getClass.getName}) " +
            s"not possible because of incomplete data: $errorCode")
        updateEsSyncStatus(IncompleteInputParams)
    }

    val recoverUpstream5xx: PartialFunction[Throwable, Future[Unit]] = {
      case e @ Upstream5xxResponse(_, upstreamCode, reportAs) =>
        logAndMaybeFail(e, Upstream5xxResponse("RELATIONSHIP_CREATE_FAILED_ES", upstreamCode, reportAs))
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
            Logger(getClass).warn(
              s"Inserting delete record into mongo failed for ${arn.value}, ${identifier.value} (${identifier.getClass.getSimpleName})",
              ex)
            ()
        }

    def removeDeleteRecord(arn: Arn, taxIdentifier: TaxIdentifier)(
      implicit ec: ExecutionContext,
      hc: HeaderCarrier): Future[Boolean] =
      deleteRecordRepository
        .remove(arn, taxIdentifier)
        .map(_ > 0)
        .recoverWith {
          case NonFatal(ex) =>
            Logger(getClass).warn(
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
                        Future { Logger(getClass).warn(s"Arn not found for provided groupId: $groupId") }
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
                  Logger(getClass).error(s"Could not deallocate previous relationship because of: $ex. Will try later.")
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
    auditData: AuditData): Future[Unit] =
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
            Logger(getClass).warn(
              s"ES relationship existed without ETMP relationship for ${arn.value}, ${identifier.value} (${identifier.getClass.getName}). " +
                s"This should not happen because we always create the ETMP relationship first,")
            recoverEtmpRecord()
          case (false, false) =>
            Logger(getClass).warn(
              s"recoverRelationshipCreation called for ${arn.value}, ${identifier.value} (${identifier.getClass.getName}) when no recovery needed")
            Future.successful(())
        }
      }
      .map(_ => ())
}
