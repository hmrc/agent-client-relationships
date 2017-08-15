/*
 * Copyright 2017 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.controllers

import javax.inject.{Inject, Singleton}

import play.api.Logger
import play.api.mvc.{Action, AnyContent, Result}
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.auth.{AgentOrClientRequest, AuthActions}
import uk.gov.hmrc.agentclientrelationships.connectors.RelationshipNotFound
import uk.gov.hmrc.agentclientrelationships.controllers.ErrorResults.NoPermissionOnAgencyOrClient
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax._
import uk.gov.hmrc.agentclientrelationships.services.{AlreadyCopiedDidNotCheck, RelationshipsService}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._
import uk.gov.hmrc.play.microservice.controller.BaseController

import scala.concurrent.Future
import scala.util.control.NonFatal

@Singleton
class Relationships @Inject()(
  override val authConnector: AuthConnector,
  service: RelationshipsService)
  extends BaseController with AuthActions {

  def checkWithMtdItId(arn: Arn, mtdItId: MtdItId): Action[AnyContent] = Action.async { implicit request =>

    implicit val auditData = new AuditData()
    auditData.set("arn", arn)

    val agentCode = service.getAgentCodeFor(arn)

    val result = for {
      agentCode <- agentCode
      result <- service.checkForRelationship(mtdItId, agentCode)
    } yield result

    result.recoverWith {
      case RelationshipNotFound(errorCode) =>
        service.checkCesaForOldRelationshipAndCopy(arn, mtdItId, agentCode)
          .map { cesaResult =>
            if (cesaResult == AlreadyCopiedDidNotCheck) {
              Logger.warn(s"CESA result for ${arn.value}, ${mtdItId.value} was already copied, so relationship should have existed in GG/ETMP" +
                          s" - but it didn't or we wouldn't have checked CESA")
            }
            Right(cesaResult.relationshipExists)
          }.recover {
            case NonFatal(ex) =>
              Logger.warn(s"Error in checkCesaForOldRelationshipAndCopy for ${arn.value}, ${mtdItId.value}", ex)
              Left(errorCode)
          }
    }.map {
      case Left(errorCode) => NotFound(toJson(errorCode))
      case Right(false) => NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      case Right(true) => Ok
    }
  }

  def checkWithNino(arn: Arn, nino: Nino): Action[AnyContent] = Action.async { implicit request =>

    implicit val auditData = new AuditData()
    auditData.set("arn", arn)

    service.lookupCesaForOldRelationship(arn, nino)
      .map {
        case references if references.nonEmpty => Ok
        case _ => NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      }.recover {
        case NonFatal(ex) =>
          Logger.warn(s"checkWithNino: lookupCesaForOldRelationship failed for ${arn.value}, $nino", ex)
          NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      }
  }

  def create(arn: Arn, mtdItId: MtdItId) = AuthorisedAgent {
    implicit request => implicit agent =>
    forThisAgentOrClient(arn, mtdItId) {
      implicit val auditData = new AuditData()
      auditData.set("arn", arn)

      (for {
        agentCode <- service.getAgentCodeFor(arn)
        _ <- service.checkForRelationship(mtdItId, agentCode)
          .map(_ => throw new Exception("RELATIONSHIP_ALREADY_EXISTS"))
          .recover {
            case RelationshipNotFound("RELATIONSHIP_NOT_FOUND") => ()
          }
        _ <- service.createRelationship(arn, mtdItId, Future.successful(agentCode), Set(), false, true)
      } yield ())
        .map(_ => Created)
        .recover {
          case NonFatal(ex) =>
            Logger.warn(s"Could not create relationship for ${arn.value}, ${mtdItId.value}", ex)
            NotFound(toJson(ex.getMessage))
        }
    }
  }

  def delete(arn: Arn, mtdItId: MtdItId): Action[AnyContent] = AuthorisedAgent { implicit request => implicit agent =>
    forThisAgentOrClient(arn, mtdItId) {
      implicit val auditData = new AuditData()
      auditData.set("arn", arn)

      (for {
        agentCode <- service.getAgentCodeFor(arn)
        _ <- service.checkForRelationship(mtdItId, agentCode)
        _ <- service.deleteRelationship(arn, mtdItId)
      } yield ())
        .map(_ => NoContent)
        .recover {
          case ex: RelationshipNotFound =>
            Logger.warn(s"Could not delete relationship for ${arn.value}, ${mtdItId.value}: ${ex.getMessage}")
            NotFound(toJson(ex.getMessage))
        }
    }
  }

  private[controllers] def forThisAgentOrClient(requiredArn: Arn, requiredMtdItId: MtdItId)
    (block: => Future[Result])(implicit request: AgentOrClientRequest[_]) = {
    val taxId: String = request.taxIdentifier.value

    if (requiredArn.value.equals(taxId) || requiredMtdItId.value.equals(taxId)) block
    else Future successful NoPermissionOnAgencyOrClient
  }

  def cleanCopyStatusRecord(arn: Arn, mtdItId: MtdItId): Action[AnyContent] = Action.async { implicit request =>
    service.cleanCopyStatusRecord(arn, mtdItId)
      .map(_ => NoContent)
      .recover {
        case ex: RelationshipNotFound => NotFound(ex.getMessage)
      }
  }
}
