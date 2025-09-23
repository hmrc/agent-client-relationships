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

package uk.gov.hmrc.agentclientrelationships.testOnly.controllers

import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax.toJson
import uk.gov.hmrc.agentclientrelationships.services.AuthorisationAcceptService
import uk.gov.hmrc.agentclientrelationships.services.CheckAndCopyRelationshipsService
import uk.gov.hmrc.agentclientrelationships.services.CreateRelationshipLocked
import uk.gov.hmrc.agentclientrelationships.services.ValidationService
import uk.gov.hmrc.agentclientrelationships.support.RelationshipNotFound
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.MtdItId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoType
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service._
import uk.gov.hmrc.http.UpstreamErrorResponse

import java.time.Instant
import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

class TestOnlyRelationshipsController @Inject() (
  checkOldAndCopyService: CheckAndCopyRelationshipsService,
  controllerComponents: ControllerComponents,
  validationService: ValidationService,
  authorisationAcceptService: AuthorisationAcceptService
)(implicit ec: ExecutionContext)
extends BackendController(controllerComponents) {

  def cleanCopyStatusRecord(
    arn: Arn,
    mtdItId: MtdItId
  ): Action[AnyContent] = Action.async { implicit request =>
    checkOldAndCopyService
      .cleanCopyStatusRecord(arn, mtdItId)
      .map(_ => NoContent)
      .recover { case ex: RelationshipNotFound => NotFound(ex.getMessage) }
  }

  def createRelationship(
    arn: Arn,
    service: String,
    clientIdType: String,
    clientId: String
  ): Action[AnyContent] = Action.async { implicit request =>
    validationService.validateForEnrolmentKey(
      service,
      clientIdType,
      clientId
    ).flatMap {
      case Right(enrolmentKey) =>
        implicit val auditData: AuditData = new AuditData()
        implicit val currentUser: CurrentUser = new CurrentUser(None, None) // Only needed for audits, pointless for test endpoint
        authorisationAcceptService.createRelationship(
          arn = arn,
          suppliedClientId = "", // This only gets used to deauth existing partial auth for a normal ITSA user, not very relevant for a test endpoint
          enrolment = enrolmentKey,
          isAltItsa = Seq(MtdIt.id, MtdItSupp.id).contains(enrolmentKey.service) && enrolmentKey.oneIdentifier().key == NinoType.enrolmentId,
          timestamp = Instant.now()
        )
          .map(_ => Created)
          .recover {
            case CreateRelationshipLocked => Locked
            case upS: UpstreamErrorResponse => InternalServerError(toJson(upS.getMessage))
            case NonFatal(ex) => InternalServerError(toJson(ex.getMessage))
          }
      case Left(error) => Future.successful(BadRequest(error))
    }
  }

}
