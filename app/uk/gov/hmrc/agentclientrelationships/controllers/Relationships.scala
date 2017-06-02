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
import scala.concurrent.Future
import play.api.Logger

import play.api.mvc.{Action, AnyContent}
import uk.gov.hmrc.agentclientrelationships.connectors.{DesConnector, GovernmentGatewayProxyConnector, MappingConnector, RelationshipNotFound}
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax.{returnValue, _}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._
import uk.gov.hmrc.play.microservice.controller.BaseController

@Singleton
class Relationships @Inject()(val gg: GovernmentGatewayProxyConnector,
                              val des: DesConnector,
                              val mapping: MappingConnector) extends BaseController {

  def check(arn: Arn, mtdItId: MtdItId): Action[AnyContent] = Action.async { implicit request =>

    val result = for {
      credentialIdentifier <- gg.getCredIdFor(arn)
      agentCode <- gg.getAgentCodeFor(credentialIdentifier)
      allocatedAgents <- gg.getAllocatedAgentCodes(mtdItId)
      result <- if (allocatedAgents.contains(agentCode)) returnValue(Right(true))
                else raiseError(RelationshipNotFound("RELATIONSHIP_NOT_FOUND"))
    } yield result

    result.recoverWith {
      case RelationshipNotFound(errorCode) =>
        checkCesaForOldRelationship(arn, mtdItId).map(Right.apply) recover {
          case _ => Left(errorCode)
        }
    }.map {
      case Left(errorCode) => NotFound(toJson(errorCode))
      case Right(false) => NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      case Right(true) => Ok
    }
  }

  private def checkCesaForOldRelationship(
    arn: Arn,
    mtdItId: MtdItId)(implicit hc: HeaderCarrier): Future[Boolean] = {

    for {
      nino <- des.getNinoFor(mtdItId)
      references <- des.getClientSaAgentSaReferences(nino)
      matching <- intersection(references) {
        mapping.getSaAgentReferencesFor(arn)
      }
    } yield matching.nonEmpty
  }

  private def intersection[A](a: Seq[A])(b: => Future[Seq[A]])(implicit hc: HeaderCarrier): Future[Set[A]] = {
    val sa = a.toSet

    if (sa.isEmpty) {
      Logger.warn("The sa references are empty.")
      Future.successful(Set.empty)
    } else
      b.map { x =>
        Logger.warn(s"The contents of sa reference are $x")
        x.toSet.intersect(sa)
      }
  }
}
