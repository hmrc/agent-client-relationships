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

import play.api.mvc.Action
import uk.gov.hmrc.agentclientrelationships.connectors.{GovernmentGatewayProxyConnector, RelationshipNotFound}
import uk.gov.hmrc.agentclientrelationships.controllers.actionSyntax._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.play.microservice.controller.BaseController

import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class Relationships @Inject()(val gg: GovernmentGatewayProxyConnector) extends BaseController {

  def check(arn: Arn, mtditid: MtdItId) = Action.async { implicit request =>

    val result: FutureOfEither[AgentCode] = for {
      credentialIdentifier <- gg.getCredIdFor(arn).orRaiseError("INVALID_ARN")
      agentCode <- gg.getAgentCodeFor(credentialIdentifier).orRaiseError("UNKNOWN_AGENT_CODE")
      allocatedAgents <- gg.getAllocatedAgentCodes(mtditid).orFail
      result <- if (allocatedAgents.contains(agentCode)) returnValue(agentCode) else raiseError("RELATIONSHIP_NOT_FOUND", RelationshipNotFound())
    } yield result

    result fold {
      case failure(RelationshipNotFound(), errorCode) => NotFound(toJson(errorCode))
      case failure(exception, _) => throw exception
      case success(_) => Ok("")
    }

  }
}
