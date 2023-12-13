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

import akka.Done
import play.api.Logging
import play.api.mvc.Request
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.DbUpdateStatus.convertDbUpdateStatus
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _, _}
import uk.gov.hmrc.agentclientrelationships.support.{NoRequest, RelationshipNotFound}
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.auth.core.AffinityGroup
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}

import java.time.{Instant, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class DeleteRelationshipsService @Inject()(
  es: EnrolmentStoreProxyConnector,
  ifConnector: IFConnector,
  aca: AgentClientAuthorisationConnector,
  deleteRecordRepository: DeleteRecordRepository,
  agentUserClientDetailsConnector: AgentUserClientDetailsConnector,
  lockService: RecoveryLockService,
  checkService: CheckRelationshipsService,
  agentUserService: AgentUserService,
  val auditService: AuditService)(implicit val appConfig: AppConfig)
    extends Logging {

  val recoveryTimeout: Int = appConfig.recoveryTimeout

  //noinspection ScalaStyle
  def deleteRelationship(arn: Arn, enrolmentKey: EnrolmentKey, affinityGroup: Option[AffinityGroup])(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    request: Request[Any],
    currentUser: CurrentUser): Future[Unit] = {

    implicit val auditData: AuditData = setAuditDataForUser(currentUser, arn, enrolmentKey)

    auditData.set("AgentDBRecord", false)
    auditData.set("enrolmentDeAllocated", false)
    auditData.set("etmpRelationshipDeAuthorised", false)

    def createDeleteRecord(record: DeleteRecord): Future[DbUpdateStatus] =
      deleteRecordRepository
        .create(record)
        .map(count => {
          auditData.set("AgentDBRecord", true)
          convertDbUpdateStatus(count)
        })
        .recoverWith {
          case NonFatal(ex) =>
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
                _ <- if (isDone) removeDeleteRecord(arn, enrolmentKey).andThen {
                      case scala.util.Success(true) =>
                        auditData.set("deleteStatus", "success of retry")
                        sendDeleteRelationshipAuditEvent(currentUser)
                      case scala.util.Failure(e) =>
                        auditData.set("deleteStatus", s"exception when retried: $e")
                        sendDeleteRelationshipAuditEvent(currentUser)
                    } else Future.successful(())
              } yield isDone
            case None =>
              delete.andThen {
                case scala.util.Success(_) =>
                  auditData.set("deleteStatus", "success")
                  sendDeleteRelationshipAuditEvent(currentUser)
                case scala.util.Failure(e) =>
                  auditData.set("deleteStatus", s"exception: $e")
                  sendDeleteRelationshipAuditEvent(currentUser)
              }
          }
    } yield ()
  }

  private def deleteEtmpRecord(arn: Arn, enrolmentKey: EnrolmentKey)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[DbUpdateStatus] = {
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
      maybeResponse <- ifConnector.deleteAgentRelationship(enrolmentKey.oneTaxIdentifier(), arn) // TODO DG oneTaxIdentifier may not return what we want for CBC!
      if maybeResponse.nonEmpty
      _ = auditData.set("etmpRelationshipDeAuthorised", true)
      etmpSyncStatusSuccess <- updateEtmpSyncStatus(Success)
    } yield etmpSyncStatusSuccess)
      .recoverWith {
        case e @ UpstreamErrorResponse.Upstream5xxResponse(UpstreamErrorResponse(_, upstreamCode, reportAs, _)) =>
          recoverFromException(e, UpstreamErrorResponse(s"RELATIONSHIP_DELETE_FAILED_IF", upstreamCode, reportAs))
        case NonFatal(ex) =>
          recoverFromException(ex, new Exception(s"RELATIONSHIP_DELETE_FAILED_IF"))
      }

  }

  //noinspection ScalaStyle
  def deleteEsRecord(arn: Arn, enrolmentKey: EnrolmentKey)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[DbUpdateStatus] = {

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
            s"not possible because of incomplete data: $errorCode")
        updateEsSyncStatus(IncompleteInputParams).map(_ => DbUpdateFailed)
    }

    lazy val recoverUpstream5xx: PartialFunction[Throwable, Future[DbUpdateStatus]] = {
      case e @ UpstreamErrorResponse.Upstream5xxResponse(UpstreamErrorResponse(_, upstreamCode, reportAs, _)) =>
        logAndMaybeFail(e, UpstreamErrorResponse("RELATIONSHIP_DELETE_FAILED_ES", upstreamCode, reportAs))
    }

    lazy val recoverUnauthorized: PartialFunction[Throwable, Future[DbUpdateStatus]] = {
      case ex: UpstreamErrorResponse if ex.statusCode == 401 =>
        logAndMaybeFail(ex, ex)
    }

    lazy val recoverNonFatal: PartialFunction[Throwable, Future[DbUpdateStatus]] = {
      case NonFatal(ex) =>
        logAndMaybeFail(ex, new Exception("RELATIONSHIP_DELETE_FAILED_ES"))
    }

    (for {
      esSyncStatusInProgress <- updateEsSyncStatus(InProgress)
      if esSyncStatusInProgress == DbUpdateSucceeded
      maybeAgentUser <- agentUserService.getAgentAdminUserFor(arn)
      agentUser = maybeAgentUser.fold(error => throw RelationshipNotFound(error), identity)
      _ <- checkService
            .checkForRelationship(arn, None, enrolmentKey)
            .flatMap {
              case true  => es.deallocateEnrolmentFromAgent(agentUser.groupId, enrolmentKey)
              case false => throw RelationshipNotFound("RELATIONSHIP_NOT_FOUND")
            }
      _ = auditData.set("enrolmentDeAllocated", true)
      _                   <- agentUserClientDetailsConnector.cacheRefresh(arn)
      esSyncStatusSuccess <- updateEsSyncStatus(Success)
    } yield esSyncStatusSuccess).recoverWith(
      recoverAgentUserRelationshipNotFound
        .orElse(recoverUpstream5xx)
        .orElse(recoverUnauthorized)
        .orElse(recoverNonFatal))
  }

  def removeDeleteRecord(arn: Arn, enrolmentKey: EnrolmentKey)(implicit ec: ExecutionContext): Future[Boolean] =
    deleteRecordRepository
      .remove(arn, enrolmentKey)
      .map(_ > 0)
      .recoverWith {
        case NonFatal(ex) =>
          logger.warn(s"Removing delete record from mongo failed for ${arn.value}, $enrolmentKey : ${ex.getMessage}")
          Future.successful(false)
      }

  def tryToResume(implicit ec: ExecutionContext, auditData: AuditData): Future[Boolean] =
    deleteRecordRepository.selectNextToRecover().flatMap {
      case Some(record) =>
        val headerCarrier = record.headerCarrier.getOrElse(HeaderCarrier())
        val enrolmentKey = record.enrolmentKey.getOrElse(enrolmentKeyFallback(record))
        checkDeleteRecordAndEventuallyResume(Arn(record.arn), enrolmentKey)(ec, headerCarrier, auditData, NoRequest)

      case None =>
        logger.info("No Delete Record Found")
        Future.successful(true)
    }

  def checkDeleteRecordAndEventuallyResume(arn: Arn, enrolmentKey: EnrolmentKey)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData,
    request: Request[_]): Future[Boolean] =
    (for {
      recordOpt <- deleteRecordRepository.findBy(arn, enrolmentKey)
      isComplete <- recordOpt match {
                     case Some(record) =>
                       auditData.set("initialDeleteDateTime", record.dateTime)
                       auditData.set("numberOfAttempts", record.numberOfAttempts + 1)
                       if (record.dateTime
                             .plusSeconds(recoveryTimeout)
                             .isAfter(Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime)) {
                         for {
                           isDone <- resumeRelationshipRemoval(record)
                           _ <- if (isDone) removeDeleteRecord(arn, enrolmentKey)
                               else Future.successful(())
                         } yield isDone
                       } else {
                         logger.error(
                           s"Terminating recovery of failed de-authorisation $record because timeout has passed.")
                         auditData.set("abandonmentReason", "timeout")
                         auditService.sendRecoveryOfDeleteRelationshipHasBeenAbandonedAuditEvent
                         removeDeleteRecord(arn, enrolmentKey).map(_ => true)
                       }
                     case None => Future.successful(true)
                   }
    } yield isComplete)
      .recoverWith {
        case e: UpstreamErrorResponse if e.statusCode == 401 =>
          logger.error(
            s"Terminating recovery of failed de-authorisation ($arn, $enrolmentKey) because auth token is invalid")
          auditData.set("abandonmentReason", "unauthorised")
          auditService.sendRecoveryOfDeleteRelationshipHasBeenAbandonedAuditEvent
          removeDeleteRecord(arn, enrolmentKey).map(_ => true)
        case NonFatal(_) =>
          Future.successful(false)
      }

  def resumeRelationshipRemoval(deleteRecord: DeleteRecord)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[Boolean] = {
    val arn = Arn(deleteRecord.arn)
    val enrolmentKey: EnrolmentKey = deleteRecord.enrolmentKey.getOrElse(enrolmentKeyFallback(deleteRecord))
    lockService
      .tryLock(arn, enrolmentKey) {
        logger.info(
          s"Resuming unfinished removal of the relationship between ${arn.value} and $enrolmentKey. Attempt: ${deleteRecord.numberOfAttempts + 1}")
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
                s"This should not happen because we always remove the ES relationship first.")
            for {
              _ <- deleteRecordRepository.markRecoveryAttempt(arn, enrolmentKey)
              _ <- deleteEsRecord(arn, enrolmentKey)
              _ <- setRelationshipEnded(arn, enrolmentKey, deleteRecord.relationshipEndedBy.getOrElse("HMRC"))
            } yield true
          case (true, false) =>
            for {
              _ <- deleteRecordRepository.markRecoveryAttempt(arn, enrolmentKey)
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

  protected def setAuditDataForUser(currentUser: CurrentUser, arn: Arn, enrolmentKey: EnrolmentKey): AuditData = {
    val auditData = new AuditData()
    if (currentUser.credentials.providerType == "GovernmentGateway") {
      auditData.set("agentReferenceNumber", arn.value)
      auditData.set("clientId", enrolmentKey.oneIdentifier().value)
      auditData.set("clientIdType", enrolmentKey.oneIdentifier().key)
      auditData.set("service", enrolmentKey.service)
      auditData.set("currentUserAffinityGroup", currentUser.affinityGroup.map(_.toString).getOrElse("unknown"))
      auditData.set("authProviderId", currentUser.credentials.providerId)
      auditData.set("authProviderIdType", currentUser.credentials.providerType)
      auditData
    } else if (currentUser.credentials.providerType == "PrivilegedApplication") {
      auditData.set("authProviderId", currentUser.credentials.providerId)
      auditData.set("authProviderIdType", currentUser.credentials.providerType)
      auditData.set("agentReferenceNumber", arn.value)
      auditData.set("clientId", enrolmentKey.oneIdentifier().value)
      auditData.set("service", enrolmentKey.service)
      auditData
    } else throw new IllegalStateException("No providerType found")
  }

  protected def sendDeleteRelationshipAuditEvent(currentUser: CurrentUser)(
    implicit headerCarrier: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData,
    ec: ExecutionContext): Future[Unit] =
    if (currentUser.credentials.providerType == "GovernmentGateway")
      auditService.sendDeleteRelationshipAuditEvent
    else if (currentUser.credentials.providerType == "PrivilegedApplication")
      auditService.sendHmrcLedDeleteRelationshipAuditEvent
    else throw new IllegalStateException("No Client Provider Type Found")

  private[services] def enrolmentKeyFallback(deleteRecord: DeleteRecord): EnrolmentKey = {
    logger.warn("DeleteRecord did not store the whole enrolment key. Performing fallback determination of service.")
    // if the enrolment key wasn't stored, assume single identifier and make a best guess based on identifier type
    (for {
      clientIdTypeStr <- deleteRecord.clientIdentifierType
      clientIdStr     <- deleteRecord.clientIdentifier
      service         <- appConfig.supportedServices.find(_.supportedClientIdType.enrolmentId == clientIdTypeStr)
    } yield {
      EnrolmentKey(
        service.id,
        Seq(Identifier(clientIdTypeStr, clientIdStr))
      )
    }).getOrElse(throw new RuntimeException(
      s"Fallback determination of enrolment key failed for ${deleteRecord.clientIdentifierType}"))
  }

  private def determineUserTypeFromAG(maybeGroup: Option[AffinityGroup]): Option[String] =
    maybeGroup match {
      case Some(AffinityGroup.Individual) | Some(AffinityGroup.Organisation) => Some("Client")
      case Some(AffinityGroup.Agent)                                         => Some("Agent")
      case _                                                                 => Some("HMRC")
    }

  private def setRelationshipEnded(arn: Arn, enrolmentKey: EnrolmentKey, endedBy: String)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Done] =
    aca
      .setRelationshipEnded(arn, enrolmentKey, endedBy)
      .map(
        success =>
          if (success) Done
          else {
            logger.warn("setRelationshipEnded failed")
            Done
        })
}
