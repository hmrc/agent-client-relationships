/*
 * Copyright 2025 HM Revenue & Customs
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

import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.controllers.ErrorResults.ErrorBody
import uk.gov.hmrc.agentclientrelationships.model.stride.PartialAuthWithAgentName
import uk.gov.hmrc.agentclientrelationships.model.stride.PartialAuthRelationships
import uk.gov.hmrc.agentclientrelationships.services.AgentAssuranceService
import uk.gov.hmrc.agentclientrelationships.services.CitizenDetailsService
import uk.gov.hmrc.agentclientrelationships.services.StrideClientDetailsService
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class StrideGetPartialAuthsController @Inject() (
  val authConnector: AuthConnector,
  strideClientDetailsService: StrideClientDetailsService,
  citizenDetailsService: CitizenDetailsService,
  agentAssuranceService: AgentAssuranceService,
  cc: ControllerComponents,
  appConfig: AppConfig
)(implicit val executionContext: ExecutionContext)
extends BackendController(cc)
with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir

  def getPartialAuths(nino: String): Action[AnyContent] = Action.async { implicit request =>
    authorisedWithStride(appConfig.partialAuthStrideRole) { _ =>
      for {
        partialAuthsWithoutAgentName <- strideClientDetailsService.getPartialAuthsForNino(Nino(nino))
        citizenDetails <- citizenDetailsService.getCitizenDetails(nino)
        partialAuths <- Future.sequence(
          partialAuthsWithoutAgentName.map { partialAuth =>
            for {
              agentDetails <- agentAssuranceService.getAgentRecord(Arn(partialAuth.arn))
            } yield {
              if (agentDetails.suspensionDetails.exists(_.suspensionStatus)) {
                None
              }
              else {
                Some(
                  PartialAuthWithAgentName(
                    arn = partialAuth.arn,
                    service = partialAuth.service,
                    agentName = agentDetails.agencyDetails.agencyName,
                    startDate = partialAuth.lastUpdated.atZone(ZoneId.of("UTC")).toLocalDate
                  )
                )
              }

            }
          }
        )
      } yield {
        if (partialAuths.collect { case Some(x) => x }.nonEmpty) {
          Ok(Json.toJson(PartialAuthRelationships(
            clientName = citizenDetails.name.getOrElse(throw new RuntimeException("[StrideGetPartialAuths] Required name is missing from Citizen Details")),
            nino = nino,
            partialAuths = partialAuths.collect { case Some(x) => x }
          )))
        }
        else {
          UnprocessableEntity(Json.toJson(
            ErrorBody(
              code = "NOT_FOUND",
              message = "No partial authorisations for Making Tax Digital for Income Tax were found for this client id"
            )
          ))
        }

      }
    }
  }

}
