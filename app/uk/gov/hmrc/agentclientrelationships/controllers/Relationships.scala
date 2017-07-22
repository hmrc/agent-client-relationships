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
import uk.gov.hmrc.agentclientrelationships.connectors.RelationshipNotFound
import uk.gov.hmrc.agentclientrelationships.controllers.actions.{AgentOrClient, AuthActions}
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax._
import uk.gov.hmrc.agentclientrelationships.services.RelationshipsService
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.agentclientrelationships.connectors.AuthConnector
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._
import uk.gov.hmrc.play.microservice.controller.BaseController
import uk.gov.hmrc.agentclientrelationships.controllers.ErrorResults.{NoPermissionOnAgencyOrClient}

import scala.concurrent.Future

@Singleton
class Relationships @Inject()(service: RelationshipsService,
                              override val authConnector: AuthConnector) extends BaseController with AuthActions {

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
          .map(Right.apply)
          .recover {
            case _ => Left(errorCode)
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
      case _ => NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
    }
  }

  def create(arn: Arn, mtdItId: MtdItId): Action[AnyContent] = agentOrClient.async { implicit request =>
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
          case ex =>
            Logger.warn(s"Could not create relationship: ${ex.getMessage}")
            NotFound(toJson(ex.getMessage))
        }
    }
  }

  def delete(arn: Arn, mtdItId: MtdItId): Action[AnyContent] = agentOrClient.async { implicit request =>
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
          case ex =>
            Logger.warn(s"Could not delete relationship: ${ex.getMessage}")
            NotFound(toJson(ex.getMessage))
        }
    }
  }

  private def forThisAgentOrClient(arn: Arn, mtdItId: MtdItId)
                                  (block: => Future[Result])(implicit request: AgentOrClient[_]) = {
    (arn != request.arn.getOrElse(""), mtdItId != request.mtdItId.getOrElse("")) match {
      case (true, true) => Future successful NoPermissionOnAgencyOrClient
      case (_, _) => block
    }
  }

  def cleanCopyStatusRecord(arn: Arn, mtdItId: MtdItId): Action[AnyContent] = Action.async { implicit request =>

    service.cleanCopyStatusRecord(arn, mtdItId)
      .map(_ => NoContent)
      .recover {
        case ex => NotFound(ex.getMessage)
      }
  }
}
