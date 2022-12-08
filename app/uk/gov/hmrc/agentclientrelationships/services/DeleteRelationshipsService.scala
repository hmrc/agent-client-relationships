/*
 * Copyright 2022 HM Revenue & Customs
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
import com.kenshoo.play.metrics.Metrics
import play.api.Logging
import play.api.mvc.Request
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.TypeOfEnrolment
import uk.gov.hmrc.agentclientrelationships.repository.DbUpdateStatus.convertDbUpdateStatus
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _, _}
import uk.gov.hmrc.agentclientrelationships.support.{Monitoring, NoRequest, RelationshipNotFound, TaxIdentifierSupport}
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.auth.core.AffinityGroup
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.{HeaderCarrier, Upstream5xxResponse, UpstreamErrorResponse}

import java.time.{Instant, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class DeleteRelationshipsService @Inject()(
  es: EnrolmentStoreProxyConnector,
  des: DesConnector,
  ifConnector: IFConnector,
  ugs: UsersGroupsSearchConnector,
  aca: AgentClientAuthorisationConnector,
  deleteRecordRepository: DeleteRecordRepository,
  lockService: RecoveryLockService,
  checkService: CheckRelationshipsService,
  agentUserService: AgentUserService,
  val auditService: AuditService,
  val metrics: Metrics)(implicit val appConfig: AppConfig)
    extends Monitoring
    with Logging {

  val recoveryTimeout = appConfig.recoveryTimeout
  //noinspection ScalaStyle
  def deleteRelationship(arn: Arn, taxIdentifier: TaxIdentifier, affinityGroup: Option[AffinityGroup])(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    request: Request[Any],
    currentUser: CurrentUser): Future[Unit] = {

    implicit val auditData: AuditData = setAuditDataForUser(currentUser, arn, taxIdentifier)

    val identifierType = TypeOfEnrolment(taxIdentifier).identifierKey

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
            logger.warn(
              s"Inserting delete record into mongo failed for ${arn.value}, ${taxIdentifier.value} (${taxIdentifier.getClass.getSimpleName}): ${ex.getMessage}")
            Future.failed(new Exception("RELATIONSHIP_DELETE_FAILED_DB"))
        }

    def delete: Future[Unit] = {
      val endedBy = determineUserTypeFromAG(affinityGroup)
      val record = DeleteRecord(
        arn.value,
        taxIdentifier.value,
        identifierType,
        headerCarrier = Some(hc),
        relationshipEndedBy = endedBy)
      for {
        recordDeletionStatus <- createDeleteRecord(record)
        if recordDeletionStatus == DbUpdateSucceeded
        esRecordDeletionStatus <- deleteEsRecord(arn, taxIdentifier)
        if esRecordDeletionStatus == DbUpdateSucceeded
        etmpRecordDeletionStatus <- deleteEtmpRecord(arn, taxIdentifier)
        if etmpRecordDeletionStatus == DbUpdateSucceeded
        removed <- removeDeleteRecord(arn, taxIdentifier)
        if removed
        _ <- setRelationshipEnded(arn, taxIdentifier, endedBy.getOrElse("HMRC"))
      } yield ()
    }

    for {
      recordOpt <- deleteRecordRepository.findBy(arn, taxIdentifier)
      _ <- recordOpt match {
            case Some(record) =>
              for {
                isDone <- resumeRelationshipRemoval(record)
                _ <- if (isDone) removeDeleteRecord(arn, taxIdentifier).andThen {
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

  def deleteEtmpRecord(arn: Arn, taxIdentifier: TaxIdentifier)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[DbUpdateStatus] = {
    val updateEtmpSyncStatus = deleteRecordRepository
      .updateEtmpSyncStatus(arn, taxIdentifier, _: SyncStatus)
      .map(convertDbUpdateStatus)

    val recoverFromException = (origExc: Throwable, replacementExc: Throwable) => {
      logger.warn(s"De-authorising ETMP record failed for ${arn.value}, $taxIdentifier due to: ${origExc.getMessage}")
      updateEtmpSyncStatus(Failed).flatMap(_ => Future.failed(replacementExc))
    }

    (for {
      etmpSyncStatusInProgress <- updateEtmpSyncStatus(InProgress)
      if etmpSyncStatusInProgress == DbUpdateSucceeded
      maybeResponse <- ifConnector.deleteAgentRelationship(taxIdentifier, arn)
      if maybeResponse.nonEmpty
      _ = auditData.set("etmpRelationshipDeAuthorised", true)
      etmpSyncStatusSuccess <- updateEtmpSyncStatus(Success)
    } yield etmpSyncStatusSuccess)
      .recoverWith {
        case e @ Upstream5xxResponse(_, upstreamCode, reportAs, _) =>
          recoverFromException(e, UpstreamErrorResponse(s"RELATIONSHIP_DELETE_FAILED_IF", upstreamCode, reportAs))
        case NonFatal(ex) =>
          recoverFromException(ex, new Exception(s"RELATIONSHIP_DELETE_FAILED_IF"))
      }

  }

  //noinspection ScalaStyle
  def deleteEsRecord(arn: Arn, taxIdentifier: TaxIdentifier)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[DbUpdateStatus] = {

    val updateEsSyncStatus = deleteRecordRepository
      .updateEsSyncStatus(arn, taxIdentifier, _: SyncStatus)
      .map(convertDbUpdateStatus)

    def logAndMaybeFail(origExc: Throwable, replacementExc: Throwable): Future[DbUpdateStatus] = {
      logger.warn(
        s"De-allocating ES record failed for ${arn.value}, ${taxIdentifier.value} (${taxIdentifier.getClass.getName}): ${origExc.getMessage}")
      updateEsSyncStatus(Failed)
      Future.failed(replacementExc)
    }

    lazy val recoverAgentUserRelationshipNotFound: PartialFunction[Throwable, Future[DbUpdateStatus]] = {
      case RelationshipNotFound(errorCode) =>
        logger.warn(
          s"De-allocating ES record for ${arn.value}, ${taxIdentifier.value} (${taxIdentifier.getClass.getName}) " +
            s"not possible because of incomplete data: $errorCode")
        updateEsSyncStatus(IncompleteInputParams).map(_ => DbUpdateFailed)
    }

    lazy val recoverUpstream5xx: PartialFunction[Throwable, Future[DbUpdateStatus]] = {
      case e @ Upstream5xxResponse(_, upstreamCode, reportAs, _) =>
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
            .checkForRelationship(arn, None, taxIdentifier)
            .flatMap {
              case true  => es.deallocateEnrolmentFromAgent(agentUser.groupId, taxIdentifier)
              case false => throw RelationshipNotFound("RELATIONSHIP_NOT_FOUND")
            }
      _ = auditData.set("enrolmentDeAllocated", true)
      esSyncStatusSuccess <- updateEsSyncStatus(Success)
    } yield esSyncStatusSuccess).recoverWith(
      recoverAgentUserRelationshipNotFound
        .orElse(recoverUpstream5xx)
        .orElse(recoverUnauthorized)
        .orElse(recoverNonFatal))
  }

  def removeDeleteRecord(arn: Arn, taxIdentifier: TaxIdentifier)(implicit ec: ExecutionContext): Future[Boolean] =
    deleteRecordRepository
      .remove(arn, taxIdentifier)
      .map(_ > 0)
      .recoverWith {
        case NonFatal(ex) =>
          logger.warn(
            s"Removing delete record from mongo failed for ${arn.value}, ${taxIdentifier.value} (${taxIdentifier.getClass.getSimpleName}) : ${ex.getMessage}")
          Future.successful(false)
      }

  def tryToResume(implicit ec: ExecutionContext, auditData: AuditData): Future[Boolean] =
    deleteRecordRepository.selectNextToRecover.flatMap {
      case Some(record) =>
        val headerCarrier = record.headerCarrier.getOrElse(HeaderCarrier())
        val taxIdentifier: TaxIdentifier = record.clientIdentifierType match {
          case "MTDITID"                => MtdItId(record.clientIdentifier)
          case "VRN"                    => Vrn(record.clientIdentifier)
          case "SAUTR"                  => Utr(record.clientIdentifier)
          case "URN"                    => Urn(record.clientIdentifier)
          case "CGTPDRef"               => CgtRef(record.clientIdentifier)
          case "EtmpRegistrationNumber" => PptRef(record.clientIdentifier)
          case _                        => throw new RuntimeException("unsupported client identifier type found in Delete record")
        }
        checkDeleteRecordAndEventuallyResume(taxIdentifier, Arn(record.arn))(ec, headerCarrier, auditData, NoRequest)

      case None =>
        logger.info("No Delete Record Found")
        Future.successful(true)
    }

  def checkDeleteRecordAndEventuallyResume(taxIdentifier: TaxIdentifier, arn: Arn)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData,
    request: Request[_]): Future[Boolean] =
    (for {
      recordOpt <- deleteRecordRepository.findBy(arn, taxIdentifier)
      isComplete <- recordOpt match {
                     case Some(record) =>
                       auditData.set("initialDeleteDateTime", record.dateTime)
                       auditData.set("numberOfAttempts", record.numberOfAttempts + 1)
                       if (record.dateTime
                             .plusSeconds(recoveryTimeout)
                             .isAfter(Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime)) {
                         for {
                           isDone <- resumeRelationshipRemoval(record)
                           _ <- if (isDone) removeDeleteRecord(arn, taxIdentifier)
                               else Future.successful(())
                         } yield isDone
                       } else {
                         logger.error(
                           s"Terminating recovery of failed de-authorisation $record because timeout has passed.")
                         auditData.set("abandonmentReason", "timeout")
                         auditService.sendRecoveryOfDeleteRelationshipHasBeenAbandonedAuditEvent
                         removeDeleteRecord(arn, taxIdentifier).map(_ => true)
                       }
                     case None => Future.successful(true)
                   }
    } yield isComplete)
      .recoverWith {
        case e: UpstreamErrorResponse if e.statusCode == 401 =>
          logger.error(
            s"Terminating recovery of failed de-authorisation ($arn, $taxIdentifier) because auth token is invalid")
          auditData.set("abandonmentReason", "unauthorised")
          auditService.sendRecoveryOfDeleteRelationshipHasBeenAbandonedAuditEvent
          removeDeleteRecord(arn, taxIdentifier).map(_ => true)
        case NonFatal(_) =>
          Future.successful(false)
      }

  def resumeRelationshipRemoval(deleteRecord: DeleteRecord)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[Boolean] = {
    val arn = Arn(deleteRecord.arn)
    val identifier = TaxIdentifierSupport.from(deleteRecord.clientIdentifier, deleteRecord.clientIdentifierType)
    lockService
      .tryLock(arn, identifier) {
        logger.info(
          s"Resuming unfinished removal of the ${identifier.getClass.getName} relationship between ${arn.value} and ${identifier.value}. Attempt: ${deleteRecord.numberOfAttempts + 1}")
        (deleteRecord.needToDeleteEtmpRecord, deleteRecord.needToDeleteEsRecord) match {
          case (true, true) =>
            for {
              _ <- deleteRecordRepository.markRecoveryAttempt(arn, identifier)
              _ <- deleteEsRecord(arn, identifier)
              _ <- deleteEtmpRecord(arn, identifier)
              _ <- setRelationshipEnded(arn, identifier, deleteRecord.relationshipEndedBy.getOrElse("HMRC"))
            } yield true
          case (false, true) =>
            logger.warn(
              s"ES relationship existed without ETMP relationship for ${arn.value}, ${identifier.value} (${identifier.getClass.getName}). " +
                s"This should not happen because we always remove the ES relationship first.")
            for {
              _ <- deleteRecordRepository.markRecoveryAttempt(arn, identifier)
              _ <- deleteEsRecord(arn, identifier)
              _ <- setRelationshipEnded(arn, identifier, deleteRecord.relationshipEndedBy.getOrElse("HMRC"))
            } yield true
          case (true, false) =>
            for {
              _ <- deleteRecordRepository.markRecoveryAttempt(arn, identifier)
              _ <- deleteEtmpRecord(arn, identifier)
              _ <- setRelationshipEnded(arn, identifier, deleteRecord.relationshipEndedBy.getOrElse("HMRC"))
            } yield true
          case (false, false) =>
            logger.warn(
              s"resumeRelationshipRemoval called for ${arn.value}, ${identifier.value} (${identifier.getClass.getName}) when no recovery needed")
            Future.successful(true)
        }
      }
      .map(_.getOrElse(false))
  }

  protected def setAuditDataForUser(currentUser: CurrentUser, arn: Arn, taxIdentifier: TaxIdentifier): AuditData = {
    val auditData = new AuditData()
    if (currentUser.credentials.providerType == "GovernmentGateway") {
      auditData.set("agentReferenceNumber", arn.value)
      auditData.set("clientId", taxIdentifier.value)
      auditData.set("clientIdType", taxIdentifier.getClass.getSimpleName)
      auditData.set("service", TypeOfEnrolment(taxIdentifier).enrolmentKey)
      auditData.set("currentUserAffinityGroup", currentUser.affinityGroup.map(_.toString).getOrElse("unknown"))
      auditData.set("authProviderId", currentUser.credentials.providerId)
      auditData.set("authProviderIdType", currentUser.credentials.providerType)
      auditData
    } else if (currentUser.credentials.providerType == "PrivilegedApplication") {
      auditData.set("authProviderId", currentUser.credentials.providerId)
      auditData.set("authProviderIdType", currentUser.credentials.providerType)
      auditData.set("agentReferenceNumber", arn.value)
      auditData.set("clientId", taxIdentifier.value)
      auditData.set("service", TypeOfEnrolment(taxIdentifier).enrolmentKey)
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

  private def determineUserTypeFromAG(maybeGroup: Option[AffinityGroup]): Option[String] =
    maybeGroup match {
      case Some(AffinityGroup.Individual) | Some(AffinityGroup.Organisation) => Some("Client")
      case Some(AffinityGroup.Agent)                                         => Some("Agent")
      case _                                                                 => Some("HMRC")
    }

  private def setRelationshipEnded(arn: Arn, clientIdentifier: TaxIdentifier, endedBy: String)(
    implicit hc: HeaderCarrier,
    ec: ExecutionContext): Future[Done] = {
    val service = TypeOfEnrolment(clientIdentifier).enrolmentKey
    aca
      .setRelationshipEnded(arn, clientIdentifier, service, endedBy)
      .map(
        success =>
          if (success) Done
          else {
            logger.warn("setRelationshipEnded failed")
            Done
        })
  }
}
