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
import play.api.mvc.{Action, AnyContent}
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.connectors.{DesConnector, GovernmentGatewayProxyConnector, RelationshipNotFound}
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax._
import uk.gov.hmrc.agentclientrelationships.services.RelationshipsService
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._
import uk.gov.hmrc.play.microservice.controller.BaseController

@Singleton
class Relationships @Inject()(service: RelationshipsService) extends BaseController {

  def checkWithMtdItId(arn: Arn, mtdItId: MtdItId) = check(arn, mtdItId)

  def checkWithNino(arn: Arn, nino: Nino) = check(arn, nino)

  def delete(arn: Arn, mtdItId: MtdItId): Action[AnyContent] = Action.async { implicit request =>
    implicit val auditData = new AuditData()

    service.deleteRelationship(arn, mtdItId)
      .map(_ => NoContent)
      .recover {
        case ex =>
          Logger.warn(s"Could not delete relationship: ${ex.getMessage}")
          NotFound
    }
  }

  private def check(arn: Arn, identifier: TaxIdentifier): Action[AnyContent] = Action.async { implicit request =>

    implicit val auditData = new AuditData()
    auditData.set("arn", arn)

    val agentCode = service.getAgentCodeFor(arn)

    val result = for {
      agentCode <- agentCode
      result <- service.checkForRelationship(identifier, agentCode)
    } yield result

    result.recoverWith {
      case RelationshipNotFound(errorCode) =>
        service.checkForOldRelationship(arn, identifier, agentCode)
          .map(Right.apply)
          .recover {
            case _ => Left(errorCode)
          }
    }.map {
      case Left(errorCode) => NotFound(toJson(errorCode))
      case Right(false)    => NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      case Right(true)     => Ok
    }
  }
}
