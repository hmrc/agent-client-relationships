/*
 * Copyright 2025 HM Revenue & Customs
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
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys._
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.audit.AuditService
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCMTDIT
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.MtdItId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipReference.SaRef
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _, _}
import uk.gov.hmrc.agentclientrelationships.support.RelationshipNotFound
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.auth.core.AffinityGroup.Agent
import uk.gov.hmrc.domain.SaAgentReference

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

sealed trait CheckAndCopyResult {
  val grantAccess: Boolean
}

case object CheckAndCopyNotImplemented
extends CheckAndCopyResult {
  override val grantAccess = false
}

case object AlreadyCopiedDidNotCheck
extends CheckAndCopyResult {
  override val grantAccess = false
}

case object FoundAndCopied
extends CheckAndCopyResult {
  override val grantAccess = true
}

case object FoundButLockedCouldNotCopy
extends CheckAndCopyResult {
  override val grantAccess = true
}

case object FoundAndFailedToCopy
extends CheckAndCopyResult {
  override val grantAccess = true
}

case object NotFound
extends CheckAndCopyResult {
  override val grantAccess = false
}

case object CopyRelationshipNotEnabled
extends CheckAndCopyResult {
  override val grantAccess = false
}

case class AltItsaCreateRelationshipSuccess(service: String)
extends CheckAndCopyResult {
  override val grantAccess = true
}

case object AltItsaNotFoundOrFailed
extends CheckAndCopyResult {
  override val grantAccess = false
}

@Singleton
class CheckAndCopyRelationshipsService @Inject() (
  hipConnector: HipConnector,
  des: DesConnector,
  mapping: MappingConnector,
  relationshipCopyRepository: RelationshipCopyRecordRepository,
  createRelationshipsService: CreateRelationshipsService,
  partialAuthRepo: PartialAuthRepository,
  invitationsRepository: InvitationsRepository,
  itsaDeauthAndCleanupService: ItsaDeauthAndCleanupService,
  val auditService: AuditService,
  val appConfig: AppConfig
)(implicit ec: ExecutionContext)
extends RequestAwareLogging {

  private val copyMtdItRelationshipFlag = appConfig.copyMtdItRelationshipFlag

  def checkForOldRelationshipAndCopy(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  )(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[CheckAndCopyResult] = {

    def ifEnabled(copyRelationshipFlag: Boolean)(body: => Future[CheckAndCopyResult]): Future[CheckAndCopyResult] =
      if (copyRelationshipFlag) {
        body
      }
      else {
        Future.successful(CopyRelationshipNotEnabled)
      }

    enrolmentKey.oneTaxIdentifier() match {
      case mtdItId @ MtdItId(_) =>
        ifEnabled(copyMtdItRelationshipFlag)(
          tryCreateITSARelationshipFromPartialAuthOrCopyAcross(
            arn,
            mtdItId,
            Some(enrolmentKey.service),
            mNino = None
          )
        )
      case _ => Future.successful(CheckAndCopyNotImplemented)
    }
  }

  // scalastyle:off method.length
  private def checkCesaAndCopy(
    arn: Arn,
    mtdItId: MtdItId,
    nino: Option[NinoWithoutSuffix]
  )(implicit
    request: RequestHeader,
    auditData: AuditData,
    currentUser: CurrentUser
  ): Future[CheckAndCopyResult] = {

    auditData.set(howRelationshipCreatedKey, copyExistingCesa)
    auditData.set(serviceKey, HMRCMTDIT)

    relationshipCopyRepository
      .findBy(arn, EnrolmentKey(Service.MtdIt, mtdItId))
      .flatMap {
        case Some(relationshipCopyRecord) if !relationshipCopyRecord.actionRequired =>
          logger.warn(s"Relationship has been already been found in CESA and we have already attempted to copy to MTD")
          Future successful AlreadyCopiedDidNotCheck
        case maybeRelationshipCopyRecord @ _ =>
          for {
            (references, _) <-
              nino.fold[Future[(Set[SaAgentReference], Seq[SaAgentReference])]](Future.successful((Set(), Seq())))(
                lookupCesaForOldRelationship(arn, _)
              )
            result <-
              if (references.nonEmpty)
                findOrCreateRelationshipCopyRecordAndCopy(
                  references.map(SaRef.apply),
                  maybeRelationshipCopyRecord,
                  arn,
                  EnrolmentKey(Service.MtdIt, mtdItId)
                ).flatMap {
                  case Some(_) =>
                    for {
                      _ <-
                        nino.fold[Future[Boolean]](Future.failed(new RuntimeException("nino not found")))(ni =>
                          itsaDeauthAndCleanupService.deleteSameAgentRelationship(
                            HMRCMTDIT,
                            arn.value,
                            Some(mtdItId.value),
                            ni.value
                          )
                        )
                    } yield FoundAndCopied
                  case None =>
                    logger.warn(s"FoundButLockedCouldNotCopy- unable to copy relationship for ITSA")
                    Future.successful(FoundButLockedCouldNotCopy)
                }.recover { case NonFatal(ex) =>
                  logger.warn(
                    s"Failed to copy CESA relationship for ${arn.value}, ${mtdItId.value} (${mtdItId.getClass.getName})",
                    ex
                  )
                  FoundAndFailedToCopy
                }
              else
                Future(NotFound)
          } yield result
      }
  }

  private def endPartialAuth(
    arn: Arn,
    service: String,
    nino: Option[NinoWithoutSuffix],
    mtdItId: MtdItId
  )(implicit request: RequestHeader): Future[Boolean] =
    nino.fold(Future.successful(false))(ni =>
      partialAuthRepo
        .deleteActivePartialAuth(
          service,
          ni,
          arn
        )
        .flatMap { deleteResult =>
          if (deleteResult) {
            invitationsRepository.updatePartialAuthToAcceptedStatus(
              arn,
              service,
              ni,
              mtdItId
            )
          }
          else {
            logger.error("error ending partialauth")
            Future.successful(false)
          }
        }
    )

  private def findPartialAuth(
    arn: Arn,
    nino: Option[NinoWithoutSuffix]
  )(implicit auditData: AuditData): Future[Option[String]] =
    nino.fold[Future[Option[String]]](Future.successful(None)) { ni =>
      auditData.set(ninoKey, ni)
      partialAuthRepo.findActiveForAgent(ni, arn).map(_.map(_.service))
    }

  private def tryCreateRelationshipFromPartialAuth(
    service: String,
    arn: Arn,
    mtdItId: MtdItId
  )(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[Option[Done]] = {
    auditData.set(serviceKey, s"$service")
    auditData.set(howRelationshipCreatedKey, partialAuth)
    createRelationshipsService.createRelationship(
      arn,
      EnrolmentKey(s"$service~MTDITID~${mtdItId.value}"),
      Set.empty,
      failIfAllocateAgentInESFails = true
    )
  }

  def tryCreateITSARelationshipFromPartialAuthOrCopyAcross(
    arn: Arn,
    mtdItId: MtdItId,
    mService: Option[String],
    mNino: Option[NinoWithoutSuffix]
  )(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[CheckAndCopyResult] = {

    auditData.set(clientIdKey, mtdItId)
    auditData.set(clientIdTypeKey, "mtditid")
    auditData.set(arnKey, s"${arn.value}")

    implicit val currentUser: CurrentUser = CurrentUser(credentials = None, affinityGroup = Some(Agent))

    for {
      mNino <- mNino.fold(hipConnector.getNinoFor(mtdItId))(ni => Future.successful(Some(ni)))
      mPartialAuth <- findPartialAuth(arn, mNino)
      createFromPartialAuthRes <-
        mPartialAuth.fold[Future[CheckAndCopyResult]](Future.successful(AltItsaNotFoundOrFailed))(partialAuth =>
          if (mService.orElse(Some(partialAuth)).contains(partialAuth))
            tryCreateRelationshipFromPartialAuth(
              partialAuth,
              arn,
              mtdItId
            ).flatMap {
              case Some(_) =>
                for {
                  _ <- endPartialAuth(
                    arn,
                    partialAuth,
                    mNino,
                    mtdItId
                  )
                  _ <- itsaDeauthAndCleanupService.deleteSameAgentRelationship(
                    partialAuth,
                    arn.value,
                    Some(mtdItId.value),
                    mNino.getOrElse(throw new Exception("nino missing")).value
                  )
                } yield AltItsaCreateRelationshipSuccess(partialAuth)
              case _ => Future.successful(AltItsaNotFoundOrFailed)
            }
          else
            Future.successful(NotFound)
        )

      createRelResult <-
        if (
          isEligibleForCopyAcross(
            createFromPartialAuthRes,
            mService,
            mPartialAuth
          )
        )
          checkCesaAndCopy(
            arn,
            mtdItId,
            mNino
          )
        else
          Future.successful(createFromPartialAuthRes)
    } yield createRelResult
  }

  private def isEligibleForCopyAcross(
    createFromPartialAuthRes: CheckAndCopyResult,
    mService: Option[String],
    mPartialAuth: Option[String]
  ): Boolean =
    List(AltItsaNotFoundOrFailed, NotFound).contains(createFromPartialAuthRes) && !mService.contains(HMRCMTDITSUPP) &&
      mPartialAuth.isEmpty

  private def findOrCreateRelationshipCopyRecordAndCopy(
    references: Set[RelationshipReference],
    maybeRelationshipCopyRecord: Option[RelationshipCopyRecord],
    arn: Arn,
    enrolmentKey: EnrolmentKey
  )(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[Option[Done]] =
    maybeRelationshipCopyRecord match {
      case Some(relationshipCopyRecord) =>
        createRelationshipsService.resumeRelationshipCreation(
          relationshipCopyRecord,
          arn,
          enrolmentKey
        )
      case None =>
        createRelationshipsService.createRelationship(
          arn,
          enrolmentKey,
          references,
          failIfAllocateAgentInESFails = false
        )
    }

  def lookupCesaForOldRelationship(
    arn: Arn,
    nino: NinoWithoutSuffix
  )(implicit
    request: RequestHeader,
    auditData: AuditData = new AuditData()
  ): Future[(Set[SaAgentReference], Seq[SaAgentReference])] = {
    auditData.set(ninoKey, nino)
    for {
      allReferences <- des.getClientSaAgentSaReferences(nino)
      matching <-
        intersection(allReferences) {
          mapping.getSaAgentReferencesFor(arn)
        }
      _ = auditData.set(saAgentRefKey, matching.mkString(","))
      _ = auditData.set(cesaRelationshipKey, matching.nonEmpty)
    } yield {
      if (matching.nonEmpty)
        auditService.sendCheckCesaAndPartialAuthAuditEvent()
      (matching, allReferences)
    }
  }

  def hasPartialAuthOrLegacyRelationshipInCesa(
    arn: Arn,
    nino: NinoWithoutSuffix
  )(implicit
    ec: ExecutionContext,
    request: RequestHeader,
    auditData: AuditData
  ): Future[Boolean] = lookupCesaForOldRelationship(arn, nino).flatMap { case (matching, _) =>
    if (matching.isEmpty) {
      partialAuthRepo
        .findActiveForAgent(nino, arn)
        .map { optPartialAuth =>
          auditData.set("partialAuth", optPartialAuth.nonEmpty)
          auditService.sendCheckCesaAndPartialAuthAuditEvent()
          optPartialAuth.nonEmpty
        }
    }
    else
      Future successful true
  }

  def intersection[A](referenceIds: Seq[A])(mappingServiceCall: => Future[Seq[A]])(implicit request: RequestHeader): Future[Set[A]] = {
    val referenceIdSet = referenceIds.toSet

    if (referenceIdSet.isEmpty) {
      // logger.warn(s"The references (${referenceIdSet.getClass.getName}) in cesa/es are empty.")
      Future.successful(Set.empty)
    }
    else
      mappingServiceCall.map { mappingServiceIds =>
        val intersected = mappingServiceIds.toSet.intersect(referenceIdSet)
        logger.info(
          s"The CESA SA references have been found, " +
            s"${if (intersected.isEmpty)
                "but no previous relationship exists"
              else
                "and will attempt to copy existing relationship"}"
        )
        intersected
      }
  }

  def cleanCopyStatusRecord(
    arn: Arn,
    mtdItId: MtdItId
  )(implicit requestHeader: RequestHeader): Future[Unit] = relationshipCopyRepository
    .remove(arn, EnrolmentKey(Service.MtdIt, mtdItId))
    .flatMap { n =>
      if (n == 0)
        Future.failed(RelationshipNotFound("Nothing has been removed from db."))
      else {
        logger.warn(s"Copy status record(s) has been removed for ${arn.value}, ${mtdItId.value}: $n")
        Future.successful(())
      }
    }

}
