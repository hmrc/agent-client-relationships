/*
 * Copyright 2016 HM Revenue & Customs
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

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import play.api.mvc.Action
import uk.gov.hmrc.agentclientrelationships.model.Relationship
import uk.gov.hmrc.agentclientrelationships.repositories.RelationshipRepository
import uk.gov.hmrc.domain.{AgentCode, SaUtr}
import uk.gov.hmrc.play.microservice.controller.BaseController

import scala.concurrent.Future

class Relationships(relationshipRepository: RelationshipRepository) extends BaseController {

    def create(saUtr: SaUtr, agentCode: AgentCode) = Action.async {
        findNonRemoved(saUtr, agentCode).flatMap {
            case Nil => relationshipRepository.create(saUtr.toString(), "sa", agentCode).map(Json.toJson(_)).map(Ok(_))
            case r :: _ => Future successful Ok(Json.toJson(r))
        }
    }

    def getRelationship(saUtr: SaUtr, agentCode: AgentCode) = Action.async {
        findNonRemoved(saUtr, agentCode).map {
            case Nil => NotFound
            case r :: _ => Ok(Json.toJson(r))
        }
    }

    def removeRelationship(saUtr: SaUtr, agentCode: AgentCode) = Action.async {
        findNonRemoved(saUtr, agentCode).flatMap {
            case Nil => Future successful NoContent
            case r :: _ => relationshipRepository.removeRelationship(saUtr.toString(), "sa", agentCode).map(_ => NoContent)
        }
    }

    def findNonRemoved(saUtr: SaUtr, agentCode: AgentCode): Future[List[Relationship]] = {
        relationshipRepository.list(saUtr.toString(), "sa", agentCode).map(
            _.filter(r => !r.isRemoved))
    }

    def getAgentRelationships(agentCode: AgentCode) = Action.async {
        relationshipRepository.list(agentCode).map(Json.toJson(_)).map(Ok(_))
    }
}
