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
import javax.inject.{Inject, Named, Singleton}

import play.api.Logger
import play.api.mvc.Request
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax.{raiseError, returnValue}
import uk.gov.hmrc.agentclientrelationships.model.TypeOfEnrolment
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipReference.{SaRef, VatRef}
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _, _}
import uk.gov.hmrc.agentclientrelationships.support.{Monitoring, RelationshipNotFound}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.auth.core.retrieve.Credentials
import uk.gov.hmrc.domain.{AgentCode, Nino, SaAgentReference, TaxIdentifier}
import uk.gov.hmrc.http.{HeaderCarrier, Upstream5xxResponse}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class DeleteRelationshipsService @Inject()(
  val es: EnrolmentStoreProxyConnector,
  des: DesConnector,
  mapping: MappingConnector,
  val ugs: UsersGroupsSearchConnector,
  deleteRecordRepository: DeleteRecordRepository,
  lockService: RecoveryLockService,
  val auditService: AuditService,
  val metrics: Metrics,
  @Named("features.copy-relationship.mtd-it") copyMtdItRelationshipFlag: Boolean,
  @Named("features.copy-relationship.mtd-vat") copyMtdVatRelationshipFlag: Boolean)
    extends Monitoring
    with ServiceHelper {

  def deleteRelationship(arn: Arn, taxIdentifier: TaxIdentifier)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    request: Request[Any],
    currentUser: CurrentUser): Future[Unit] = {

    implicit val auditData = setAuditDataForUser(currentUser, arn, taxIdentifier)
    val identifierType = TypeOfEnrolment(taxIdentifier).identifierKey
    val record = DeleteRecord(arn.value, taxIdentifier.value, identifierType)

    def createDeleteRecord: Future[Unit] =
      deleteRecordRepository
        .create(record)
        .map(_ => auditData.set("AgentDBRecord", true))
        .recoverWith {
          case NonFatal(ex) =>
            Logger(getClass).warn(
              s"Inserting relationship record into mongo failed for ${arn.value}, ${taxIdentifier.value} (${taxIdentifier.getClass.getSimpleName})",
              ex)
            Future.failed(new Exception("RELATIONSHIP_CREATE_FAILED_DB"))
        }

    for {
      _             <- createDeleteRecord
      clientGroupId <- es.getPrincipalGroupIdFor(taxIdentifier)
      _             <- deleteEtmpRecord(arn, taxIdentifier)
      _             <- deleteEsRecord(arn, taxIdentifier, clientGroupId)
    } yield sendAuditEventForUser(currentUser)
  }

  def deleteEtmpRecord(
    arn: Arn,
    taxIdentifier: TaxIdentifier)(implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData) = {
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

    val recoverAgentUserRelationshipNotFound: PartialFunction[Throwable, Future[Unit]] = {
      case RelationshipNotFound(errorCode) =>
        Logger(getClass).warn(
          s"De-allocating ES record for ${arn.value}, ${taxIdentifier.value} (${taxIdentifier.getClass.getName}) " +
            s"not possible because of incomplete data: $errorCode")
        updateEsSyncStatus(IncompleteInputParams)
    }

    val recoverUpstream5xx: PartialFunction[Throwable, Future[Unit]] = {
      case e @ Upstream5xxResponse(_, upstreamCode, reportAs) =>
        logAndMaybeFail(e, Upstream5xxResponse("RELATIONSHIP_DELETE_FAILED_ES", upstreamCode, reportAs))
    }

    val recoverNonFatal: PartialFunction[Throwable, Future[Unit]] = {
      case NonFatal(ex) =>
        logAndMaybeFail(ex, new Exception("RELATIONSHIP_DELETE_FAILED_ES"))
    }

    (for {
      _         <- updateEsSyncStatus(InProgress)
      agentUser <- getAgentUserFor(arn)
      _ <- checkForRelationship(taxIdentifier, agentUser).map(_ =>
            es.deallocateEnrolmentFromAgent(clientGroupId, taxIdentifier, agentUser.agentCode))
      _ = auditData.set("enrolmentDeAllocated", true)
      _ <- updateEsSyncStatus(Success)
    } yield ()).recoverWith(
      recoverAgentUserRelationshipNotFound
        .orElse(recoverUpstream5xx)
        .orElse(recoverNonFatal))
  }
}
