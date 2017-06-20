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

import play.api.mvc.{Action, AnyContent}
import uk.gov.hmrc.agentclientrelationships.connectors.{GovernmentGatewayProxyConnector, RelationshipNotFound}
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax.{returnValue, _}
import uk.gov.hmrc.agentclientrelationships.services.RelationshipService
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._
import uk.gov.hmrc.play.microservice.controller.BaseController

@Singleton
class Relationships @Inject()(gg: GovernmentGatewayProxyConnector,
                              service: RelationshipService) extends BaseController {

  def checkWithMtdItId(arn: Arn, mtdItId: MtdItId) = check(arn, mtdItId)

  def checkWithNino(arn: Arn, nino: Nino) = check(arn, nino)

  private def check(arn: Arn, identifier: TaxIdentifier): Action[AnyContent] = Action.async { implicit request =>

    val agentCode = for {
      credentialIdentifier <- gg.getCredIdFor(arn)
      agentCode <- gg.getAgentCodeFor(credentialIdentifier)
    } yield agentCode

    val result = for {
      agentCode <- agentCode
      allocatedAgents <- gg.getAllocatedAgentCodes(identifier)
      result <- if (allocatedAgents.contains(agentCode)) returnValue(Right(true))
                else raiseError(RelationshipNotFound("RELATIONSHIP_NOT_FOUND"))
    } yield result

    result.recoverWith {
      case RelationshipNotFound(errorCode) =>
        service.checkForOldRelationship(arn, identifier, agentCode)
          .map(Right.apply)
          .recover { case _ => Left(errorCode) }
    }.map {
      case Left(errorCode) => NotFound(toJson(errorCode))
      case Right(false) => NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      case Right(true) => Ok
    }
  }
}
