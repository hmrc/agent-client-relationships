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

import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys.arnKey
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys.credIdKey
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.UserId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.MtdItId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.services.CheckRelationshipResult._
import uk.gov.hmrc.agentclientrelationships.support.Monitoring
import uk.gov.hmrc.agentclientrelationships.support.RelationshipNotFound
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@Singleton
class CheckRelationshipsOrchestratorService @Inject() (
  val metrics: Metrics,
  checkService: CheckRelationshipsService,
  checkOldAndCopyService: CheckAndCopyRelationshipsService,
  validationService: ValidationService,
  deleteService: DeleteRelationshipsService,
  hipConnector: HipConnector,
  agentFiRelationshipConnector: AgentFiRelationshipConnector,
  agentAssuranceService: AgentAssuranceService
)(implicit executionContext: ExecutionContext)
extends Monitoring
with RequestAwareLogging {

  def checkForRelationship(
    arn: Arn,
    service: String,
    clientIdType: String,
    clientId: String,
    userId: Option[String]
  )(implicit request: RequestHeader): Future[CheckRelationshipResult] = {
    val tUserId = userId.map(UserId)

    (service, clientIdType, clientId) match {
      // Used by BTA to handle non MTD ITSA users
      case ("IR-SA", _, _) if NinoWithoutSuffix.isValid(clientId) =>
        withIrSaSuspensionCheck(arn) {
          checkLegacyWithNinoOrPartialAuth(arn, NinoWithoutSuffix(clientId))
        }
      // MTD ITSA uses nino as supplied clientId while the actual clientId is MTDITID
      case (Service.MtdIt.id | Service.MtdItSupp.id, "ni" | "NI" | "NINO", _) if NinoWithoutSuffix.isValid(clientId) =>
        withMtdItId(clientId) { mtdItId =>
          checkWithTaxIdentifier(
            arn,
            tUserId,
            EnrolmentKey(service, mtdItId)
          )
        }
      // PIR relationships are done through agent-fi-relationships
      case (Service.PersonalIncomeRecord.id, _, _) =>
        checkAgentFiRelationship(
          arn,
          service,
          clientId
        )
      // "normal" cases
      case (svc, idType, id) =>
        withValidEnrolment(
          service,
          clientIdType,
          clientId
        ) { enrolmentKey =>
          checkWithTaxIdentifier(
            arn,
            tUserId,
            enrolmentKey
          )
        }
    }
  }

  private def withValidEnrolment(
    service: String,
    clientIdType: String,
    clientId: String
  )(
    proceed: EnrolmentKey => Future[CheckRelationshipResult]
  )(implicit request: RequestHeader): Future[CheckRelationshipResult] = validationService
    .validateForEnrolmentKey(
      service,
      clientIdType,
      clientId
    )
    .flatMap {
      case Right(enrolmentKey) => proceed(enrolmentKey)
      case Left(validationError) =>
        logger.warn(s"Invalid parameters: $validationError")
        Future.successful(CheckRelationshipInvalidRequest)
    }

  private def checkWithTaxIdentifier(
    arn: Arn,
    maybeUserId: Option[UserId],
    enrolmentKey: EnrolmentKey
  )(implicit request: RequestHeader): Future[CheckRelationshipResult] = {
    implicit val auditData: AuditData = new AuditData()
    auditData.set(arnKey, arn)
    maybeUserId.foreach(auditData.set(credIdKey, _))

    deleteService.checkDeleteRecordAndEventuallyResume(arn, enrolmentKey).flatMap { isClear =>
      if (isClear)
        checkService.checkForRelationship(
          arn,
          maybeUserId,
          enrolmentKey
        ).recover {
          case _: RelationshipNotFound => false
          case ex => throw ex
        }.flatMap { res =>
          if (res)
            Future.successful(CheckRelationshipFound)
          else
            checkOldRelationship(
              arn,
              enrolmentKey,
              relationshipNotFound
            )
        }
      else {
        logger.warn("Denied access because relationship removal is pending.")
        Future.successful(CheckRelationshipNotFound(relationshipDeletePending))
      }
    }
  }

  private def checkOldRelationship(
    arn: Arn,
    enrolmentKey: EnrolmentKey,
    errorCode: String
  )(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[CheckRelationshipResult] = checkOldAndCopyService
    .checkForOldRelationshipAndCopy(arn, enrolmentKey)
    .map {
      case CopyRelationshipNotEnabled | CheckAndCopyNotImplemented => CheckRelationshipNotFound(errorCode)
      case AlreadyCopiedDidNotCheck => CheckRelationshipNotFound(relationshipNotFoundAlreadyCopied)
      case cesaResult =>
        if (cesaResult.grantAccess)
          CheckRelationshipFound
        else
          CheckRelationshipNotFound()
    }
    .recover {
      case upS: UpstreamErrorResponse => throw upS
      case NonFatal(ex) =>
        val taxIdentifier = enrolmentKey.oneTaxIdentifier()
        logger.warn(
          s"Error in checkForOldRelationshipAndCopy for ${arn.value}, ${taxIdentifier.value} (${taxIdentifier.getClass.getName}), ${ex.getMessage}"
        )
        CheckRelationshipNotFound(errorCode)
    }

  private def checkAgentFiRelationship(
    arn: Arn,
    service: String,
    clientId: String
  )(implicit request: RequestHeader) = agentFiRelationshipConnector
    .getRelationship(
      arn,
      service,
      clientId
    )
    .map {
      case Some(_) => CheckRelationshipFound
      case None => CheckRelationshipNotFound()
    }
  private def withMtdItId(clientId: String)(
    proceed: MtdItId => Future[CheckRelationshipResult]
  )(implicit request: RequestHeader): Future[CheckRelationshipResult] = hipConnector
    .getMtdIdFor(NinoWithoutSuffix(clientId))
    .flatMap {
      case Some(mtdItId) => proceed(mtdItId)
      case None => Future.successful(CheckRelationshipNotFound())
    }

  private def withIrSaSuspensionCheck(arn: Arn)(
    proceed: => Future[CheckRelationshipResult]
  )(implicit request: RequestHeader): Future[CheckRelationshipResult] = agentAssuranceService
    .getNonSuspendedAgentRecord(arn)
    .flatMap {
      case None =>
        logger.warn(s"agent with id : ${arn.value} is suspended")
        Future.successful(CheckRelationshipInvalidRequest)
      case _ => proceed
    }

  private def checkLegacyWithNinoOrPartialAuth(
    arn: Arn,
    nino: NinoWithoutSuffix
  )(implicit request: RequestHeader): Future[CheckRelationshipResult] = {
    implicit val auditData: AuditData = new AuditData()
    auditData.set(arnKey, arn)

    checkOldAndCopyService
      .hasPartialAuthOrLegacyRelationshipInCesa(arn, nino)
      .map {
        case true => CheckRelationshipFound
        case false => CheckRelationshipNotFound()
      }
      .recover {
        case error: UpstreamErrorResponse => throw error
        case NonFatal(ex) =>
          logger.warn(
            s"checkWithNino: lookupCesaForOldRelationship failed for arn: ${arn.value}, nino: $nino, ${ex.getMessage}"
          )
          CheckRelationshipNotFound()
      }
  }

}

// scalafmt: { binPack.parentConstructors = Always }

sealed trait CheckRelationshipResult
case object CheckRelationshipFound extends CheckRelationshipResult
case class CheckRelationshipNotFound(message: String = relationshipNotFound) extends CheckRelationshipResult
case object CheckRelationshipInvalidRequest extends CheckRelationshipResult

object CheckRelationshipResult {

  val relationshipNotFound = "RELATIONSHIP_NOT_FOUND"
  val relationshipNotFoundAlreadyCopied = "RELATIONSHIP_NOT_FOUND_ALREADY_COPIED"
  val relationshipDeletePending = "RELATIONSHIP_DELETE_PENDING"

}
