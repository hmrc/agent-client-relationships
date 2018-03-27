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

import javax.inject.{Inject, Named, Singleton}

import play.api.Logger
import play.api.mvc.Request
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax.{raiseError, returnValue}
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentType
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipReference.{SaRef, VatRef}
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus._
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _, _}
import uk.gov.hmrc.agentclientrelationships.support.RelationshipNotFound
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.domain.{AgentCode, Nino, SaAgentReference, TaxIdentifier}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

sealed trait CheckAndCopyResult {
  val grantAccess: Boolean
}

final case object AlreadyCopiedDidNotCheck extends CheckAndCopyResult {
  override val grantAccess = false
}

final case object FoundAndCopied extends CheckAndCopyResult {
  override val grantAccess = true
}

final case object FoundAndFailedToCopy extends CheckAndCopyResult {
  override val grantAccess = true
}

final case object NotFound extends CheckAndCopyResult {
  override val grantAccess = false
}

final case object CopyRelationshipNotEnabled extends CheckAndCopyResult {
  override val grantAccess = false
}

case class AgentUser(userId: String, groupId: String, agentCode: AgentCode)

@Singleton
class RelationshipsService @Inject()(es: EnrolmentStoreProxyConnector,
                                     des: DesConnector,
                                     mapping: MappingConnector,
                                     ugs: UsersGroupsSearchConnector,
                                     relationshipCopyRepository: RelationshipCopyRecordRepository,
                                     lockService: RecoveryLockService,
                                     auditService: AuditService,
                                     @Named("features.copy-relationship.mtd-it") copyMtdItRelationshipFlag: Boolean,
                                     @Named("features.copy-relationship.mtd-vat") copyMtdVatRelationshipFlag: Boolean) {

  def getAgentUserFor(arn: Arn)
                     (implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[AgentUser] =
    for {
      agentGroupId <- es.getPrincipalGroupIdFor(arn)
      agentUserId <- es.getPrincipalUserIdFor(arn)
      _ = auditData.set("credId", agentUserId)
      groupInfo <- ugs.getGroupInfo(agentGroupId)
      agentCode = groupInfo.agentCode.getOrElse(throw new Exception(s"Missing AgentCode for $arn"))
      _ = auditData.set("agentCode", agentCode)
    } yield AgentUser(agentUserId, agentGroupId, agentCode)

  def checkForRelationship(identifier: TaxIdentifier, agentUser: AgentUser)
                          (implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[Either[String, Boolean]] =
    for {
      allocatedGroupIds <- es.getDelegatedGroupIdsFor(identifier)
      result <- if (allocatedGroupIds.contains(agentUser.groupId)) returnValue(Right(true))
      else raiseError(RelationshipNotFound("RELATIONSHIP_NOT_FOUND"))
    } yield result

  def checkForOldRelationshipAndCopy(arn: Arn, identifier: TaxIdentifier, eventualAgentUser: Future[AgentUser])
                                    (implicit ec: ExecutionContext, hc: HeaderCarrier,
                                     request: Request[Any], auditData: AuditData): Future[CheckAndCopyResult] = {

    def ifEnabled(copyRelationshipFlag: Boolean)(body: => Future[CheckAndCopyResult]): Future[CheckAndCopyResult] =
      if (copyRelationshipFlag) body else returnValue(CopyRelationshipNotEnabled)

    identifier match {
      case mtdItId@MtdItId(_) =>
        ifEnabled(copyMtdItRelationshipFlag)(checkCesaForOldRelationshipAndCopyForMtdIt(arn, mtdItId, eventualAgentUser))
      case vrn@Vrn(_) =>
        ifEnabled(copyMtdVatRelationshipFlag)(checkESForOldRelationshipAndCopyForMtdVat(arn, vrn, eventualAgentUser))
    }
  }

  private def checkCesaForOldRelationshipAndCopyForMtdIt(arn: Arn, mtdItId: MtdItId, eventualAgentUser: Future[AgentUser])
                                                        (implicit ec: ExecutionContext, hc: HeaderCarrier, request: Request[Any], auditData: AuditData): Future[CheckAndCopyResult] = {

    auditData.set("Journey", "CopyExistingCESARelationship")
    auditData.set("service", "mtd-it")
    auditData.set("clientId", mtdItId)
    auditData.set("clientIdType", "mtditid")

    relationshipCopyRepository.findBy(arn, mtdItId).flatMap {
      case Some(relationshipCopyRecord) if !relationshipCopyRecord.actionRequired =>
        Logger.warn(s"Relationship has been already been found in CESA and we have already attempted to copy to MTD")
        Future successful AlreadyCopiedDidNotCheck
      case maybeRelationshipCopyRecord@_ =>
        for {
          nino <- des.getNinoFor(mtdItId)
          references <- lookupCesaForOldRelationship(arn, nino)
          result <- if (references.nonEmpty) {
            maybeRelationshipCopyRecord.map(
              relationshipCopyRecord => recoverRelationshipCreation(relationshipCopyRecord, arn, mtdItId, eventualAgentUser))
              .getOrElse(createRelationship(arn, mtdItId, eventualAgentUser, references.map(SaRef.apply), true, false)).map { _ =>
              auditService.sendCreateRelationshipAuditEvent
              FoundAndCopied
            }
              .recover { case NonFatal(ex) =>
                Logger.warn(s"Failed to copy CESA relationship for ${arn.value}, ${mtdItId.value} (${mtdItId.getClass.getName})", ex)
                auditService.sendCreateRelationshipAuditEvent
                FoundAndFailedToCopy
              }
          } else Future.successful(NotFound)
        } yield result
    }
  }

  private def checkESForOldRelationshipAndCopyForMtdVat(arn: Arn, vrn: Vrn, eventualAgentUser: Future[AgentUser])
                                                       (implicit ec: ExecutionContext, hc: HeaderCarrier,
                                                        request: Request[Any], auditData: AuditData): Future[CheckAndCopyResult] = {

    auditData.set("Journey", "CopyExistingESRelationship")
    auditData.set("service", "mtd-vat")
    auditData.set("vrn", vrn)

    relationshipCopyRepository.findBy(arn, vrn).flatMap {
      case Some(relationshipCopyRecord) if !relationshipCopyRecord.actionRequired =>
        Logger.warn(s"Relationship has been already been found in ES and we have already attempted to copy to MTD")
        Future successful AlreadyCopiedDidNotCheck
      case maybeRelationshipCopyRecord@_ =>
        for {
          references <- lookupESForOldRelationship(arn, vrn)
          result <- if (references.nonEmpty) {
            maybeRelationshipCopyRecord.map(
              relationshipCopyRecord => recoverRelationshipCreation(relationshipCopyRecord, arn, vrn, eventualAgentUser))
              .getOrElse(createRelationship(arn, vrn, eventualAgentUser, references.map(VatRef.apply), true, false)).map { _ =>
              auditService.sendCreateRelationshipAuditEventForMtdVat
              FoundAndCopied
            }
              .recover { case NonFatal(ex) =>
                Logger.warn(s"Failed to copy ES relationship for ${arn.value}, ${vrn.value} (${vrn.getClass.getName})", ex)
                auditService.sendCreateRelationshipAuditEventForMtdVat
                FoundAndFailedToCopy
              }
          } else Future.successful(NotFound)
        } yield result
    }
  }

  def lookupCesaForOldRelationship(arn: Arn, nino: Nino)
                                  (implicit ec: ExecutionContext, hc: HeaderCarrier, request: Request[Any], auditData: AuditData): Future[Set[SaAgentReference]] = {
    auditData.set("nino", nino)
    for {
      references <- des.getClientSaAgentSaReferences(nino)
      matching <- intersection(references) {
        mapping.getSaAgentReferencesFor(arn)
      }
      _ = auditData.set("saAgentRef", matching.mkString(","))
      _ = auditData.set("CESARelationship", matching.nonEmpty)
    } yield {
      auditService.sendCheckCESAAuditEvent
      matching
    }
  }

  def lookupESForOldRelationship(arn: Arn, clientVrn: Vrn)(
    implicit ec: ExecutionContext, hc: HeaderCarrier, request: Request[Any], auditData: AuditData): Future[Set[AgentCode]] = {
    auditData.set("vrn", clientVrn)

    for {
      agentGroupIds <- es.getDelegatedGroupIdsForHMCEVATDECORG(clientVrn)
      agentCodes <- Future.sequence(agentGroupIds.map(ugs.getGroupInfo).toSeq).map(_.map(_.agentCode).collect { case Some(ac) => ac })
      matching <- intersection[AgentCode](agentCodes) {
        mapping.getAgentCodesFor(arn)
      }
      _ = auditData.set("oldAgentCodes", matching.map(_.value).mkString(","))
      _ = auditData.set("ESRelationship", matching.nonEmpty)
    } yield {
      auditService.sendCheckESAuditEvent
      matching
    }
  }

  private def createEtmpRecord(arn: Arn, identifier: TaxIdentifier)(implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[Unit] = {
    val updateEtmpSyncStatus = relationshipCopyRepository.updateEtmpSyncStatus(arn, identifier, _: SyncStatus)

    (for {
      _ <- updateEtmpSyncStatus(InProgress)
      _ <- des.createAgentRelationship(identifier, arn)
      _ = auditData.set("etmpRelationshipCreated", true)
      _ <- updateEtmpSyncStatus(Success)
    } yield ())
      .recoverWith {
        case NonFatal(ex) =>
          Logger.warn(s"Creating ETMP record failed for ${arn.value}, ${identifier.value} (${identifier.getClass.getName})", ex)
          updateEtmpSyncStatus(Failed).flatMap(_ => Future.failed(new Exception("RELATIONSHIP_CREATE_FAILED_DES")))
      }
  }

  private def createEsRecord(
                              arn: Arn,
                              identifier: TaxIdentifier,
                              eventualAgentUser: Future[AgentUser],
                              failIfAllocateAgentInESFails: Boolean
                            )(implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[Unit] = {

    val updateEsSyncStatus = relationshipCopyRepository.updateEsSyncStatus(arn, identifier, _: SyncStatus)
    (for {
      _ <- updateEsSyncStatus(InProgress)
      agentUser <- eventualAgentUser
      _ <- es.allocateEnrolmentToAgent(agentUser.groupId, agentUser.userId, identifier, agentUser.agentCode)
      _ = auditData.set("enrolmentDelegated", true)
      _ <- updateEsSyncStatus(Success)
    } yield ())
      .recoverWith {
        case RelationshipNotFound(errorCode) =>
          Logger.warn(s"Creating ES record for ${arn.value}, ${identifier.value} (${identifier.getClass.getName}) " +
            s"not possible because of incomplete data: $errorCode")
          updateEsSyncStatus(IncompleteInputParams)
        case NonFatal(ex) =>
          Logger.warn(s"Creating ES record failed for ${arn.value}, ${identifier.value} (${identifier.getClass.getName})", ex)
          updateEsSyncStatus(Failed)
          if (failIfAllocateAgentInESFails) Future.failed(new Exception("RELATIONSHIP_CREATE_FAILED_ES"))
          else Future.successful(())
      }
  }

  def createRelationship(arn: Arn,
                         identifier: TaxIdentifier,
                         eventualAgentUser: Future[AgentUser],
                         oldReferences: Set[RelationshipReference],
                         failIfCreateRecordFails: Boolean,
                         failIfAllocateAgentInESFails: Boolean
                        )
                        (implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[Unit] = {

    auditData.set("AgentDBRecord", false)
    auditData.set("enrolmentDelegated", false)
    auditData.set("etmpRelationshipCreated", false)

    def createRelationshipRecord: Future[Unit] = {
      val identifierType = EnrolmentType.enrolmentTypeFor(identifier).identifierKey
      val record = RelationshipCopyRecord(arn.value, identifier.value, identifierType, Some(oldReferences))
      relationshipCopyRepository.create(record)
        .map(_ => auditData.set("AgentDBRecord", true))
        .recoverWith {
          case NonFatal(ex) =>
            Logger.warn(s"Inserting relationship record into mongo failed for ${arn.value}, ${identifier.value} (${identifier.getClass.getName})", ex)
            if (failIfCreateRecordFails) Future.failed(new Exception("RELATIONSHIP_CREATE_FAILED_DB"))
            else Future.successful(())
        }
    }

    for {
      _ <- createRelationshipRecord
      _ <- createEtmpRecord(arn, identifier)
      _ <- createEsRecord(arn, identifier, eventualAgentUser, failIfAllocateAgentInESFails)
    } yield ()
  }

  private def recoverRelationshipCreation(
                                           relationshipCopyRecord: RelationshipCopyRecord,
                                           arn: Arn, identifier: TaxIdentifier,
                                           eventualAgentUser: Future[AgentUser])(implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[Unit] = {

    lockService.tryLock(arn, identifier) {
      def recoverEtmpRecord() = createEtmpRecord(arn, identifier)

      def recoverEsRecord() = createEsRecord(arn, identifier, eventualAgentUser, failIfAllocateAgentInESFails = false)

      (relationshipCopyRecord.needToCreateEtmpRecord, relationshipCopyRecord.needToCreateEsRecord) match {
        case (true, true) =>
          for {
            _ <- recoverEtmpRecord()
            _ <- recoverEsRecord()
          } yield ()
        case (false, true) =>
          recoverEsRecord()
        case (true, false) =>
          Logger.warn(s"ES relationship existed without ETMP relationship for ${arn.value}, ${identifier.value} (${identifier.getClass.getName}). " +
            s"This should not happen because we always create the ETMP relationship first,")
          recoverEtmpRecord()
        case (false, false) =>
          Logger.warn(s"recoverRelationshipCreation called for ${arn.value}, ${identifier.value} (${identifier.getClass.getName}) when no recovery needed")
          Future.successful(())
      }
    }.map(_ => ())

  }

  private def intersection[A](referenceIds: Seq[A])(mappingServiceCall: => Future[Seq[A]])(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Set[A]] = {
    val referenceIdSet = referenceIds.toSet

    if (referenceIdSet.isEmpty) {
      Logger.warn(s"The references (${referenceIdSet.getClass.getName}) in cesa/es are empty.")
      returnValue(Set.empty)
    } else
      mappingServiceCall.map { mappingServiceIds =>
        val intersected = mappingServiceIds.toSet.intersect(referenceIdSet)
        Logger.info(s"The sa/es references (${referenceIdSet.getClass.getName}) in mapping store are $mappingServiceIds. " +
          s"The intersected value between mapping store and DES/ES is $intersected")
        intersected
      }
  }

  def deleteRelationship(arn: Arn, mtdItId: MtdItId)(
    implicit ec: ExecutionContext, hc: HeaderCarrier, request: Request[Any], auditData: AuditData): Future[Unit] = {

    def esDeAllocation(clientGroupId: String) = (for {
      agentUser <- getAgentUserFor(arn)
      _ <- checkForRelationship(mtdItId, agentUser).map(_ => es.deallocateEnrolmentFromAgent(clientGroupId, mtdItId, agentUser.agentCode))
    } yield ()).recover {
      case ex: RelationshipNotFound =>
        Logger.warn("Could not delete relationship", ex)
    }

    for {
      clientGroupId <- es.getPrincipalGroupIdFor(mtdItId)
      _ <- des.deleteAgentRelationship(mtdItId, arn)
      _ <- esDeAllocation(clientGroupId)
    } yield ()
  }

  def cleanCopyStatusRecord(arn: Arn, mtdItId: MtdItId)(implicit executionContext: ExecutionContext): Future[Unit] = {
    relationshipCopyRepository.remove(arn, mtdItId).flatMap { n =>
      if (n == 0) {
        Future.failed(RelationshipNotFound("Nothing has been removed from db."))
      } else {
        Logger.warn(s"Copy status record(s) has been removed for ${arn.value}, ${mtdItId.value}: $n")
        Future.successful(())
      }
    }
  }

  def getItsaRelationshipForClient(clientId: MtdItId)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[ItsaRelationship]] = {
    des.getActiveClientItsaRelationships(clientId)
  }

  def getVatRelationshipForClient(clientId: Vrn)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[VatRelationship]] = {
    des.getActiveClientVatRelationships(clientId)
  }
}