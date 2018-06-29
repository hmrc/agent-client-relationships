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

import com.kenshoo.play.metrics.Metrics
import javax.inject.{Inject, Singleton}
import play.api.Logger
import play.api.mvc.Request
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.TypeOfEnrolment
import uk.gov.hmrc.agentclientrelationships.repository.{DeleteRecord, DeleteRecordRepository}
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.support.{Monitoring, RelationshipNotFound, TaxIdentifierSupport}
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.{HeaderCarrier, Upstream5xxResponse}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class DeleteRelationshipsService @Inject()(
  es: EnrolmentStoreProxyConnector,
  des: DesConnector,
  ugs: UsersGroupsSearchConnector,
  deleteRecordRepository: DeleteRecordRepository,
  lockService: RecoveryLockService,
  checkService: CheckRelationshipsService,
  agentUserService: AgentUserService,
  val auditService: AuditService,
  val metrics: Metrics)
    extends Monitoring {

  def deleteRelationship(arn: Arn, taxIdentifier: TaxIdentifier)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    request: Request[Any],
    currentUser: CurrentUser): Future[Unit] = {

    implicit val auditData: AuditData = setAuditDataForUser(currentUser, arn, taxIdentifier)

    val identifierType = TypeOfEnrolment(taxIdentifier).identifierKey
    val record = DeleteRecord(arn.value, taxIdentifier.value, identifierType)

    auditData.set("AgentDBRecord", false)
    auditData.set("enrolmentDeAllocated", false)
    auditData.set("etmpRelationshipDeAuthorised", false)

    def createDeleteRecord: Future[Unit] =
      deleteRecordRepository
        .create(record)
        .map(_ => auditData.set("AgentDBRecord", true))
        .recoverWith {
          case NonFatal(ex) =>
            Logger(getClass).warn(
              s"Inserting delete record into mongo failed for ${arn.value}, ${taxIdentifier.value} (${taxIdentifier.getClass.getSimpleName})",
              ex)
            Future.failed(new Exception("RELATIONSHIP_DELETE_FAILED_DB"))
        }

    for {
      _             <- createDeleteRecord
      clientGroupId <- es.getPrincipalGroupIdFor(taxIdentifier)
      _             <- deleteEtmpRecord(arn, taxIdentifier)
      _             <- deleteEsRecord(arn, taxIdentifier, clientGroupId)
      _             <- removeDeleteRecord(arn, taxIdentifier)
    } yield sendAuditEventForUser(currentUser)
  }

  def deleteEtmpRecord(arn: Arn, taxIdentifier: TaxIdentifier)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[Unit] = {
    val updateEtmpSyncStatus = deleteRecordRepository.updateEtmpSyncStatus(arn, taxIdentifier, _: SyncStatus)

    val recoverWithException = (origExc: Throwable, replacementExc: Throwable) => {
      Logger(getClass).warn(
        s"De-authorising ETMP record failed for ${arn.value}, ${taxIdentifier.value} (${taxIdentifier.getClass.getName})",
        origExc)
      updateEtmpSyncStatus(Failed).flatMap(_ => Future.failed(replacementExc))
    }

    (for {
      _ <- updateEtmpSyncStatus(InProgress)
      _ <- des.deleteAgentRelationship(taxIdentifier, arn)
      _ = auditData.set("etmpRelationshipDeAuthorised", true)
      _ <- updateEtmpSyncStatus(Success)
    } yield ())
      .recoverWith {
        case e @ Upstream5xxResponse(_, upstreamCode, reportAs) =>
          recoverWithException(e, Upstream5xxResponse("RELATIONSHIP_DELETE_FAILED_DES", upstreamCode, reportAs))
        case NonFatal(ex) =>
          recoverWithException(ex, new Exception("RELATIONSHIP_DELETE_FAILED_DES"))
      }

  }

  def deleteEsRecord(arn: Arn, taxIdentifier: TaxIdentifier, clientGroupId: String)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[Unit] = {

    val updateEsSyncStatus = deleteRecordRepository.updateEsSyncStatus(arn, taxIdentifier, _: SyncStatus)

    def logAndMaybeFail(origExc: Throwable, replacementExc: Throwable): Future[Unit] = {
      Logger(getClass).warn(
        s"Creating ES record failed for ${arn.value}, ${taxIdentifier.value} (${taxIdentifier.getClass.getName})",
        origExc)
      updateEsSyncStatus(Failed)
      Future.failed(replacementExc)
    }

    lazy val recoverAgentUserRelationshipNotFound: PartialFunction[Throwable, Future[Unit]] = {
      case RelationshipNotFound(errorCode) =>
        Logger(getClass).warn(
          s"De-allocating ES record for ${arn.value}, ${taxIdentifier.value} (${taxIdentifier.getClass.getName}) " +
            s"not possible because of incomplete data: $errorCode")
        updateEsSyncStatus(IncompleteInputParams)
    }

    lazy val recoverUpstream5xx: PartialFunction[Throwable, Future[Unit]] = {
      case e @ Upstream5xxResponse(_, upstreamCode, reportAs) =>
        logAndMaybeFail(e, Upstream5xxResponse("RELATIONSHIP_DELETE_FAILED_ES", upstreamCode, reportAs))
    }

    lazy val recoverNonFatal: PartialFunction[Throwable, Future[Unit]] = {
      case NonFatal(ex) =>
        logAndMaybeFail(ex, new Exception("RELATIONSHIP_DELETE_FAILED_ES"))
    }

    (for {
      _         <- updateEsSyncStatus(InProgress)
      agentUser <- agentUserService.getAgentUserFor(arn)
      _ <- checkService
            .checkForRelationship(taxIdentifier, agentUser)
            .flatMap(_ => es.deallocateEnrolmentFromAgent(clientGroupId, taxIdentifier, agentUser.agentCode))
      _ = auditData.set("enrolmentDeAllocated", true)
      _ <- updateEsSyncStatus(Success)
    } yield ()).recoverWith(
      recoverAgentUserRelationshipNotFound
        .orElse(recoverUpstream5xx)
        .orElse(recoverNonFatal))
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

  def checkDeleteRecordAndEventuallyResume(
    taxIdentifier: TaxIdentifier,
    agentUser: AgentUser)(implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[Boolean] =
    (for {
      recordOpt <- deleteRecordRepository.findBy(agentUser.arn, taxIdentifier)
      isComplete <- recordOpt match {
                     case Some(record) =>
                       for {
                         isDone <- resumeRelationshipRemoval(record, agentUser)
                         _ <- if (isDone) removeDeleteRecord(agentUser.arn, taxIdentifier)
                             else Future.successful(false)
                       } yield isDone
                     case None => Future.successful(true)
                   }
    } yield isComplete)
      .recover {
        case NonFatal(_) => false
      }

  def resumeRelationshipRemoval(
    deleteRecord: DeleteRecord,
    agentUser: AgentUser
  )(implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[Boolean] = {
    val arn = Arn(deleteRecord.arn)
    val identifier = TaxIdentifierSupport.from(deleteRecord.clientIdentifier, deleteRecord.clientIdentifierType)
    lockService
      .tryLock(arn, identifier) {
        (deleteRecord.needToDeleteEtmpRecord, deleteRecord.needToDeleteEsRecord) match {
          case (true, true) =>
            for {
              _             <- deleteEtmpRecord(arn, identifier)
              clientGroupId <- es.getPrincipalGroupIdFor(identifier)
              _             <- deleteEsRecord(arn, identifier, clientGroupId)
            } yield true
          case (false, true) =>
            for {
              clientGroupId <- es.getPrincipalGroupIdFor(identifier)
              _             <- deleteEsRecord(arn, identifier, clientGroupId)
            } yield true
          case (true, false) =>
            Logger(getClass).warn(
              s"ETMP relationship existed without ES relationship for ${arn.value}, ${identifier.value} (${identifier.getClass.getName}). " +
                s"This should not happen because we always remove the ETMP relationship first.")
            for {
              _ <- deleteEtmpRecord(arn, identifier)
            } yield true
          case (false, false) =>
            Logger(getClass).warn(
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

  protected def sendAuditEventForUser(currentUser: CurrentUser)(
    implicit headerCarrier: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData): Unit =
    if (currentUser.credentials.providerType == "GovernmentGateway")
      auditService.sendDeleteRelationshipAuditEvent
    else if (currentUser.credentials.providerType == "PrivilegedApplication")
      auditService.sendHmrcLedDeleteRelationshipAuditEvent
    else throw new IllegalStateException("No Client Provider Type Found")
}
