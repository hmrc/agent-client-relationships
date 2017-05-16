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
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.play.microservice.controller.BaseController

import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class Relationships @Inject()(val gg: GovernmentGatewayProxyConnector) extends BaseController {

  def check(arn: Arn, mtdItId: MtdItId): Action[AnyContent] = Action.async { implicit request =>

    val result = for {
      credentialIdentifier <- gg.getCredIdFor(arn)
      agentCode <- gg.getAgentCodeFor(credentialIdentifier)
      allocatedAgents <- gg.getAllocatedAgentCodes(mtdItId)
      result <- if (allocatedAgents.contains(agentCode)) returnValue(agentCode)
                else checkCesaForRelationship(arn,mtdItId)
    } yield result

    result map (_ => Ok ) recover {
      case RelationshipNotFound(errorCode) => NotFound(toJson(errorCode))
    }
  }

  private def checkCesaForRelationship(arn:Arn, mtdItId: MtdItId) = {
    raiseError(RelationshipNotFound("RELATIONSHIP_NOT_FOUND"))
    // #1. translate mtditid to nino using GetRegistrationBusinessDetails
    // 2. ask for agent's CESA references using /mappings/:arn
    // #3. query DES for agent-client relationships GetStatusAgentRelationship
    // 4. check if returned CESA list contains agent's CESA
  }
}
