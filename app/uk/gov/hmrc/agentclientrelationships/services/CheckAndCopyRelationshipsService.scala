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

import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys._
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.audit.AuditService
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax.returnValue
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipReference.SaRef
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipReference.VatRef
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _, _}
import uk.gov.hmrc.agentclientrelationships.support.Monitoring
import uk.gov.hmrc.agentclientrelationships.support.RelationshipNotFound
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDIT
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentmtdidentifiers.model.MtdItId
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.agentmtdidentifiers.model.Vrn
import uk.gov.hmrc.auth.core.AffinityGroup.Agent
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.domain.SaAgentReference
import play.api.mvc.RequestHeader
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

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

case object VrnNotFoundInEtmp
extends CheckAndCopyResult {
  override val grantAccess = true
}

@Singleton
class CheckAndCopyRelationshipsService @Inject() (
  es: EnrolmentStoreProxyConnector,
  ifOrHipConnector: IfOrHipConnector,
  des: DesConnector,
  mapping: MappingConnector,
  ugs: UsersGroupsSearchConnector,
  relationshipCopyRepository: RelationshipCopyRecordRepository,
  createRelationshipsService: CreateRelationshipsService,
  partialAuthRepo: PartialAuthRepository,
  invitationsRepository: InvitationsRepository,
  itsaDeauthAndCleanupService: ItsaDeauthAndCleanupService,
  val auditService: AuditService,
  val metrics: Metrics,
  val appConfig: AppConfig
)(implicit ec: ExecutionContext)
extends Monitoring
with RequestAwareLogging {

  val copyMtdItRelationshipFlag = appConfig.copyMtdItRelationshipFlag
  val copyMtdVatRelationshipFlag = appConfig.copyMtdVatRelationshipFlag

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
        returnValue(CopyRelationshipNotEnabled)
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
      case vrn @ Vrn(_) => ifEnabled(copyMtdVatRelationshipFlag)(checkESForOldRelationshipAndCopyForMtdVat(arn, vrn))

      case _ => Future.successful(CheckAndCopyNotImplemented)
    }
  }

  private def checkCesaAndCopy(
    arn: Arn,
    mtdItId: MtdItId,
    nino: Option[Nino]
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
            references <-
              nino.fold[Future[Set[SaAgentReference]]](Future.successful(Set.empty))(
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
                      _ = mark("Count-CopyRelationship-ITSA-FoundAndCopied")
                    } yield FoundAndCopied
                  case None =>
                    mark("Count-CopyRelationship-ITSA-FoundButLockedCouldNotCopy")
                    logger.warn(s"FoundButLockedCouldNotCopy- unable to copy relationship for ITSA")
                    Future.successful(FoundButLockedCouldNotCopy)
                }.recover { case NonFatal(ex) =>
                  logger.warn(
                    s"Failed to copy CESA relationship for ${arn.value}, ${mtdItId.value} (${mtdItId.getClass.getName})",
                    ex
                  )
                  mark("Count-CopyRelationship-ITSA-FoundAndFailedToCopy")
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
    nino: Option[Nino],
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
    nino: Option[Nino]
  )(implicit auditData: AuditData): Future[Option[String]] =
    nino.fold[Future[Option[String]]](Future.successful(None)) { ni =>
      auditData.set(ninoKey, ni)
      partialAuthRepo.findActive(ni, arn).map(_.map(_.service))
    }

  private def tryCreateRelationshipFromPartialAuth(
    service: String,
    arn: Arn,
    mtdItId: MtdItId
  )(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[Option[DbUpdateStatus]] = {
    auditData.set(serviceKey, s"$service")
    auditData.set(howRelationshipCreatedKey, partialAuth)
    createRelationshipsService.createRelationship(
      arn,
      EnrolmentKey(s"$service~MTDITID~${mtdItId.value}"),
      Set.empty,
      failIfCreateRecordFails = true,
      failIfAllocateAgentInESFails = true
    )
  }

  def tryCreateITSARelationshipFromPartialAuthOrCopyAcross(
    arn: Arn,
    mtdItId: MtdItId,
    mService: Option[String],
    mNino: Option[Nino]
  )(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[CheckAndCopyResult] = {

    auditData.set(clientIdKey, mtdItId)
    auditData.set(clientIdTypeKey, "mtditid")
    auditData.set(arnKey, s"${arn.value}")

    implicit val currentUser: CurrentUser = CurrentUser(credentials = None, affinityGroup = Some(Agent))

    for {
      mNino <- mNino.fold(ifOrHipConnector.getNinoFor(mtdItId))(ni => Future.successful(Some(ni)))
      mPartialAuth <- findPartialAuth(arn, mNino)
      createFromPartialAuthRes <-
        mPartialAuth.fold[Future[CheckAndCopyResult]](Future.successful(AltItsaNotFoundOrFailed))(partialAuth =>
          if (mService.orElse(Some(partialAuth)).contains(partialAuth))
            tryCreateRelationshipFromPartialAuth(
              partialAuth,
              arn,
              mtdItId
            ).flatMap {
              case Some(DbUpdateSucceeded) =>
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
  ): Future[Option[DbUpdateStatus]] =
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
          failIfCreateRecordFails = true,
          failIfAllocateAgentInESFails = false
        )
    }

  private def checkESForOldRelationshipAndCopyForMtdVat(
    arn: Arn,
    vrn: Vrn
  )(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[CheckAndCopyResult] = {
    auditData.set(howRelationshipCreatedKey, "CopyExistingESRelationship")
    auditData.set(serviceKey, "mtd-vat")
    auditData.set("vrn", vrn)
    relationshipCopyRepository
      .findBy(arn, EnrolmentKey(Service.Vat, vrn))
      .flatMap {
        case Some(relationshipCopyRecord) if !relationshipCopyRecord.actionRequired =>
          // logger.warn(s"Relationship has been already been found in ES and we have already attempted to copy to MTD")
          Future successful AlreadyCopiedDidNotCheck
        case maybeRelationshipCopyRecord @ _ =>
          for {
            references <- lookupESForOldRelationship(arn, vrn)
            result <-
              if (references.nonEmpty)
                checkVrnExistsInEtmp(vrn).flatMap {
                  case true =>
                    findOrCreateRelationshipCopyRecordAndCopy(
                      references.map(VatRef.apply),
                      maybeRelationshipCopyRecord,
                      arn,
                      EnrolmentKey(Service.Vat, vrn)
                    ).map {
                      case Some(_) =>
                        auditService.sendCreateRelationshipAuditEventForMtdVat()
                        mark("Count-CopyRelationship-VAT-FoundAndCopied")
                        FoundAndCopied
                      case None =>
                        auditService.sendCreateRelationshipAuditEventForMtdVat()
                        mark("Count-CopyRelationship-VAT-FoundButLockedCouldNotCopy")
                        logger.warn(s"FoundButLockedCouldNotCopy- unable to copy relationship for MTD-VAT")
                        FoundButLockedCouldNotCopy
                    }.recover { case NonFatal(ex) =>
                      logger.warn(
                        s"Failed to copy ES relationship for ${arn.value}, $vrn due to: ${ex.getMessage}",
                        ex
                      )
                      auditService.sendCreateRelationshipAuditEventForMtdVat()
                      mark("Count-CopyRelationship-VAT-FoundAndFailedToCopy")
                      FoundAndFailedToCopy
                    }
                  case false =>
                    auditService.sendCreateRelationshipAuditEventForMtdVat()
                    Future.successful(VrnNotFoundInEtmp)
                }
              else
                Future.successful(NotFound)
          } yield result
      }
  }

  private def checkVrnExistsInEtmp(vrn: Vrn)(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[Boolean] = des
    .vrnIsKnownInEtmp(vrn)
    .map { result =>
      auditData.set("vrnExistsInEtmp", result)
      result
    }

  def lookupCesaForOldRelationship(
    arn: Arn,
    nino: Nino
  )(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[Set[SaAgentReference]] = {
    auditData.set(ninoKey, nino)
    for {
      references <- des.getClientSaAgentSaReferences(nino)
      matching <-
        intersection(references) {
          mapping.getSaAgentReferencesFor(arn)
        }
      _ = auditData.set(saAgentRefKey, matching.mkString(","))
      _ = auditData.set(cesaRelationshipKey, matching.nonEmpty)
    } yield {
      if (matching.nonEmpty)
        auditService.sendCheckCesaAndPartialAuthAuditEvent()
      matching
    }
  }

  def hasPartialAuthOrLegacyRelationshipInCesa(
    arn: Arn,
    nino: Nino
  )(implicit
    ec: ExecutionContext,
    request: RequestHeader,
    auditData: AuditData
  ): Future[Boolean] = lookupCesaForOldRelationship(arn, nino).flatMap(matching =>
    if (matching.isEmpty) {
      partialAuthRepo
        .findActive(nino, arn)
        .map { optPartialAuth =>
          auditData.set("partialAuth", optPartialAuth.nonEmpty)
          auditService.sendCheckCesaAndPartialAuthAuditEvent()
          optPartialAuth.nonEmpty
        }
    }
    else
      Future successful true
  )

  def lookupESForOldRelationship(
    arn: Arn,
    clientVrn: Vrn
  )(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[Set[AgentCode]] = {
    auditData.set("vrn", clientVrn)

    for {
      agentGroupIds <- es.getDelegatedGroupIdsForHMCEVATDECORG(clientVrn)
      groupInfos = agentGroupIds.map(ugs.getGroupInfo)
      agentCodes <- Future
        .sequence(groupInfos)
        .map { setOptions: Set[Option[GroupInfo]] =>
          setOptions
            .flatMap { maybeGroup =>
              maybeGroup.map(_.agentCode)
            }
            .collect { case Some(ac) => ac }
        }
      matching <-
        intersection[AgentCode](agentCodes.toSeq) {
          mapping.getAgentCodesFor(arn)
        }
      _ = auditData.set("oldAgentCodes", matching.map(_.value).mkString(","))
      _ = auditData.set("ESRelationship", matching.nonEmpty)
      _ <- auditService.sendCheckEsAuditEvent()
    } yield matching
  }

  def intersection[A](referenceIds: Seq[A])(mappingServiceCall: => Future[Seq[A]])(implicit request: RequestHeader): Future[Set[A]] = {
    val referenceIdSet = referenceIds.toSet

    if (referenceIdSet.isEmpty) {
      // logger.warn(s"The references (${referenceIdSet.getClass.getName}) in cesa/es are empty.")
      returnValue(Set.empty)
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
