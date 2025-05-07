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

import play.api.Logging
import play.api.mvc.{Request, RequestHeader}
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys.{arnKey, credIdKey}
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentKey, UserId}
import uk.gov.hmrc.agentclientrelationships.support.{AdminNotFound, Monitoring, RelationshipDeletePending, RelationshipNotFound}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Service, Vrn}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class CheckRelationshipsOrchestratorService @Inject() (
  val metrics: Metrics,
  checkService: CheckRelationshipsService,
  checkOldAndCopyService: CheckAndCopyRelationshipsService,
  validationService: ValidationService,
  agentUserService: AgentUserService,
  deleteService: DeleteRelationshipsService,
  ifOrHipConnector: IfOrHipConnector,
  desConnector: DesConnector,
  agentFiRelationshipConnector: AgentFiRelationshipConnector
)(implicit executionContext: ExecutionContext)
extends Monitoring
with Logging {

  def checkForRelationship(arn: Arn, service: String, clientIdType: String, clientId: String, userId: Option[String])(
    implicit request: RequestHeader
  ): Future[CheckRelationshipResult] = {
    val tUserId = userId.map(UserId)

    (service, clientIdType, clientId) match {
      // Used by BTA to handle non MTD ITSA users
      case ("IR-SA", _, _) if Nino.isValid(clientId) =>
        withIrSaSuspensionCheck(arn) {
          checkLegacyWithNinoOrPartialAuth(arn, Nino(clientId))
        }
      // MTD ITSA uses nino as supplied clientId while the actual clientId is MTDITID
      case (Service.MtdIt.id | Service.MtdItSupp.id, "ni" | "NI" | "NINO", _) if Nino.isValid(clientId) =>
        withMtdItId(clientId) { mtdItId =>
          checkWithTaxIdentifier(arn, tUserId, EnrolmentKey(service, mtdItId))
        }
      // Legacy VAT enrolment check
      case ("HMCE-VATDEC-ORG", "vrn", _) if Vrn.isValid(clientId) => checkWithVrn(arn, Vrn(clientId))
      // PIR relationships are done through agent-fi-relationships
      case (Service.PersonalIncomeRecord.id, _, _) => checkAgentFiRelationship(arn, service, clientId)
      // "normal" cases
      case (svc, idType, id) =>
        withValidEnrolment(service, clientIdType, clientId) { enrolmentKey =>
          checkWithTaxIdentifier(arn, tUserId, enrolmentKey)
        }
    }
  }

  private def withValidEnrolment(service: String, clientIdType: String, clientId: String)(
    proceed: EnrolmentKey => Future[CheckRelationshipResult]
  )(implicit request: RequestHeader): Future[CheckRelationshipResult] = validationService
    .validateForEnrolmentKey(service, clientIdType, clientId)
    .flatMap {
      case Right(enrolmentKey) => proceed(enrolmentKey)
      case Left(validationError) =>
        logger.warn(s"Invalid parameters: $validationError")
        Future.successful(CheckRelationshipInvalidRequest)
    }

  private def checkWithTaxIdentifier(arn: Arn, maybeUserId: Option[UserId], enrolmentKey: EnrolmentKey)(implicit
    request: RequestHeader
  ): Future[CheckRelationshipResult] = {
    implicit val auditData: AuditData = new AuditData()
    auditData.set(arnKey, arn)
    maybeUserId.foreach(auditData.set(credIdKey, _))

    val result =
      for {
        _       <- agentUserService.getAgentAdminAndSetAuditData(arn)
        isClear <- deleteService.checkDeleteRecordAndEventuallyResume(arn, enrolmentKey)
        res <-
          if (isClear)
            checkService.checkForRelationship(arn, maybeUserId, enrolmentKey)
          else
            Future.failed(RelationshipDeletePending())
      } yield
        if (res)
          CheckRelationshipFound
        else
          throw RelationshipNotFound("RELATIONSHIP_NOT_FOUND")

    result.recoverWith {
      case RelationshipNotFound(errorCode) => checkOldRelationship(arn, enrolmentKey, errorCode)
      case AdminNotFound(errorCode)        => checkOldRelationship(arn, enrolmentKey, errorCode)
      case e @ RelationshipDeletePending() =>
        logger.warn("Denied access because relationship removal is pending.")
        Future.successful(CheckRelationshipNotFound(e.getMessage))
    }
  }

  private def checkOldRelationship(arn: Arn, enrolmentKey: EnrolmentKey, errorCode: String)(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[CheckRelationshipResult] = checkOldAndCopyService
    .checkForOldRelationshipAndCopy(arn, enrolmentKey)
    .map {
      case AlreadyCopiedDidNotCheck | CopyRelationshipNotEnabled | CheckAndCopyNotImplemented =>
        CheckRelationshipNotFound(errorCode)
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
          s"Error in checkForOldRelationshipAndCopy for ${arn.value}, ${taxIdentifier
              .value} (${taxIdentifier.getClass.getName}), ${ex.getMessage}"
        )
        CheckRelationshipNotFound(errorCode)
    }

  private def checkAgentFiRelationship(arn: Arn, service: String, clientId: String)(implicit request: RequestHeader) =
    agentFiRelationshipConnector
      .getRelationship(arn, service, clientId)
      .map {
        case Some(_) => CheckRelationshipFound
        case None    => CheckRelationshipNotFound()
      }
  private def withMtdItId(clientId: String)(
    proceed: MtdItId => Future[CheckRelationshipResult]
  )(implicit request: RequestHeader): Future[CheckRelationshipResult] = ifOrHipConnector
    .getMtdIdFor(Nino(clientId))
    .flatMap {
      case Some(mtdItId) => proceed(mtdItId)
      case None          => Future.successful(CheckRelationshipNotFound())
    }

  private def withIrSaSuspensionCheck(
    arn: Arn
  )(proceed: => Future[CheckRelationshipResult])(implicit request: RequestHeader): Future[CheckRelationshipResult] =
    desConnector
      .getAgentRecord(arn)
      .flatMap {
        case None => Future.successful(CheckRelationshipInvalidRequest)
        case Some(record) if record.isSuspended && record.suspendedFor("ITSA") =>
          logger.warn(s"agent with id : ${arn.value} is suspended for regime ITSA")
          Future.successful(CheckRelationshipInvalidRequest)
        case _ => proceed
      }

  private def checkLegacyWithNinoOrPartialAuth(arn: Arn, nino: Nino)(implicit
    request: RequestHeader
  ): Future[CheckRelationshipResult] = {
    implicit val auditData: AuditData = new AuditData()
    auditData.set(arnKey, arn)

    checkOldAndCopyService
      .hasPartialAuthOrLegacyRelationshipInCesa(arn, nino)
      .map {
        case true  => CheckRelationshipFound
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

  private def checkWithVrn(arn: Arn, vrn: Vrn)(implicit request: RequestHeader): Future[CheckRelationshipResult] = {
    implicit val auditData: AuditData = new AuditData()
    auditData.set(arnKey, arn)

    checkOldAndCopyService
      .lookupESForOldRelationship(arn, vrn)
      .map {
        case references if references.nonEmpty => CheckRelationshipFound
        case _                                 => CheckRelationshipNotFound()
      }
      .recover {
        case upS: UpstreamErrorResponse => throw upS
        case NonFatal(_) =>
          logger.warn("checkWithVrn: lookupESForOldRelationship failed")
          CheckRelationshipNotFound()
      }
  }
}

sealed trait CheckRelationshipResult
case object CheckRelationshipFound
extends CheckRelationshipResult
case class CheckRelationshipNotFound(message: String = "RELATIONSHIP_NOT_FOUND")
extends CheckRelationshipResult
case object CheckRelationshipInvalidRequest
extends CheckRelationshipResult
