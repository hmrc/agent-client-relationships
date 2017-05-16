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
import uk.gov.hmrc.agentclientrelationships.connectors.{DesConnector, GovernmentGatewayProxyConnector, MappingConnector, RelationshipNotFound}
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax.{returnValue, _}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.microservice.controller.BaseController

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class Relationships @Inject()(val gg: GovernmentGatewayProxyConnector,
                              val des: DesConnector,
                              val mapping: MappingConnector) extends BaseController {

  def check(arn: Arn, mtdItId: MtdItId): Action[AnyContent] = Action.async { implicit request =>

    val result = for {
      credentialIdentifier <- gg.getCredIdFor(arn)
      agentCode <- gg.getAgentCodeFor(credentialIdentifier)
      allocatedAgents <- gg.getAllocatedAgentCodes(mtdItId)
      result <- if (allocatedAgents.contains(agentCode)) returnValue(true)
                else checkCesaForOldRelationship(arn, mtdItId)
    } yield result

    result map {
      case true => Ok
      case false => NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
    } recover {
      case RelationshipNotFound(errorCode) => NotFound(toJson(errorCode))
    }
  }

  def checkCesaForOldRelationship(arn: Arn, mtdItId: MtdItId)(implicit hc: HeaderCarrier): Future[Boolean] = {

    def intersection[A](a: Seq[A], b:Seq[A]) = returnValue(b.toSet.intersect(a.toSet))

    val result = for {
      nino <- des.getNinoFor(mtdItId)
      agentSaReferences <- mapping.getSaAgentReferencesFor(arn)
      clientAgentsSaReferences <- des.getClientAgentsSaReferences(nino)
      references <- intersection(agentSaReferences, clientAgentsSaReferences)
    } yield references.nonEmpty

    result recover {
      case _ => false
    }

  }
}
