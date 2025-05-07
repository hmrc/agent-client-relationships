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

import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.IfOrHipConnector
import uk.gov.hmrc.agentclientrelationships.services.{AltItsaCreateRelationshipSuccess, AltItsaNotFoundOrFailed, CheckAndCopyRelationshipsService, FoundAndCopied, NotFound => CheckAndCopyNotFound}
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDIT
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ItsaPostSignupController @Inject() (
  ifOrHipConnector: IfOrHipConnector,
  checkAndCopyRelationshipsService: CheckAndCopyRelationshipsService,
  val authConnector: AuthConnector,
  val appConfig: AppConfig,
  cc: ControllerComponents
)(implicit val executionContext: ExecutionContext)
    extends BackendController(cc)
    with AuthActions
    with Logging {

  val supportedServices: Seq[Service] = appConfig.supportedServices

  def itsaPostSignupCreateRelationship(nino: Nino): Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsAgent { arn =>
      ifOrHipConnector.getMtdIdFor(nino).flatMap {
        case Some(mtdItId) =>
          implicit val auditData: AuditData = new AuditData()
          checkAndCopyRelationshipsService
            .tryCreateITSARelationshipFromPartialAuthOrCopyAcross(arn, mtdItId, mService = None, mNino = Some(nino))
            .map {
              case AltItsaCreateRelationshipSuccess(service) => Created(Json.parse(s"""{"service": "$service"}"""))
              case FoundAndCopied                            => Created(Json.parse(s"""{"service": "$HMRCMTDIT"}"""))
              case AltItsaNotFoundOrFailed =>
                val msg = s"itsa-post-signup create relationship failed: no partial auth"
                logger.warn(msg)
                NotFound(msg)
              case CheckAndCopyNotFound =>
                val msg = "itsa-post-signup create relationship failed: no partial-auth and no legacy SA relationship"
                logger.warn(msg)
                NotFound(msg)
              case other =>
                val msg = s"itsa-post-signup create relationship failed: $other"
                logger.warn(msg)
                InternalServerError(msg)
            }
        case None =>
          val msg = "itsa-post-signup create relationship failed: no MTDITID found for nino"
          logger.warn(msg)
          Future.successful(NotFound(msg))
      }
    }
  }
}
