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

import play.api.mvc.Action
import play.api.mvc.ControllerComponents
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.audit.AuditService
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.AgentFiRelationshipConnector
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdIt
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdItSupp
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.PersonalIncomeRecord
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.AlreadyBeingProcessed
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.RelationshipNotFound
import uk.gov.hmrc.agentclientrelationships.model.invitation.RemoveAuthorisationRequest
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository.endedByHMRC
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.services.ClientDetailsService
import uk.gov.hmrc.agentclientrelationships.services.DeleteRelationshipsService
import uk.gov.hmrc.agentclientrelationships.services.InvitationService
import uk.gov.hmrc.agentclientrelationships.services.ValidationService
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class RemoveAuthorisationController @Inject() (
  invitationService: InvitationService,
  deleteService: DeleteRelationshipsService,
  validationService: ValidationService,
  auditService: AuditService,
  partialAuthRepository: PartialAuthRepository,
  agentFiRelationshipConnector: AgentFiRelationshipConnector,
  clientDetailsService: ClientDetailsService,
  val authConnector: AuthConnector,
  val appConfig: AppConfig,
  cc: ControllerComponents
)(implicit val executionContext: ExecutionContext)
extends BackendController(cc)
with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir

  private val strideRoles = Seq(appConfig.oldAuthStrideRole, appConfig.newAuthStrideRole)

  def removeAuthorisation(arn: Arn): Action[RemoveAuthorisationRequest] =
    Action.async(parse.json[RemoveAuthorisationRequest]) { implicit request =>
      for {
        enrolmentKey <- validationService.validateForEnrolmentKey(
          request.body.service,
          request.body.clientId
        )
        (suppliedClientId, optClientId) <- clientDetailsService.expandClientId(enrolmentKey.serviceType, enrolmentKey.oneTaxIdentifier())
        enrolmentKeyForAuth = optClientId.fold(enrolmentKey)(_ => EnrolmentKey(enrolmentKey.serviceType, suppliedClientId))
        refinedEnrolmentKey = optClientId.fold(enrolmentKey)(clientId => EnrolmentKey(enrolmentKey.serviceType, clientId))
        // Generating two enrolment keys because API may have been called with either NINO or MTDITID
        // we need to know both to use NINO for auth (business requirement) and MTDITID for relationship removal
        // Ideally frontend/API would always call with just NINO but that would require a refactor to MYTA and extra calls to get NINO from MTDITID there
        result <-
          authorisedUser(
            arn = Some(arn),
            clientId = enrolmentKeyForAuth.oneTaxIdentifier(),
            strideRoles = strideRoles
          ) { implicit currentUser =>
            removeAuthorisationForValidRequest(
              arn,
              refinedEnrolmentKey
            ).map {
              case Some(true) => NoContent
              case Some(false) => RelationshipNotFound.getResult()
              case None => AlreadyBeingProcessed.getResult()
            }
          }
      } yield result
    }

  // scalastyle:off method.length
  private def removeAuthorisationForValidRequest(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  )(implicit
    request: RequestHeader,
    currentUser: CurrentUser
  ): Future[Option[Boolean]] =
    (enrolmentKey.serviceType, enrolmentKey.oneTaxIdentifier()) match {
      case (service @ PersonalIncomeRecord, clientId) =>
        agentFiRelationshipConnector
          .deleteRelationship(
            arn,
            service.id,
            clientId.value
          )
          .map { result: Boolean =>
            if (result) {
              val userType = deleteService.determineUserTypeFromAG(currentUser.affinityGroup).getOrElse(endedByHMRC)
              invitationService.deauthoriseInvitation(
                arn,
                enrolmentKey,
                userType
              )
              auditService.auditForPirTermination(arn, enrolmentKey)
            }
            Some(result)
          }
      case (service @ (MtdIt | MtdItSupp), clientId @ NinoWithoutSuffix(_)) => // Alt ITSA
        partialAuthRepository.deauthorise(
          service.id,
          NinoWithoutSuffix(clientId.value),
          arn,
          Instant.now
        ).map { result: Boolean =>
          if (result) {
            val userType = deleteService.determineUserTypeFromAG(currentUser.affinityGroup).getOrElse(endedByHMRC)
            invitationService.deauthoriseInvitation(
              arn,
              enrolmentKey,
              userType
            )
            auditService.sendTerminatePartialAuthAuditEvent(
              arn.value,
              enrolmentKey.service,
              enrolmentKey.oneIdentifier().value
            )
          }
          Some(result)
        }
      case _ => // Handles invitation deauth and auditing on its own
        deleteService
          .deleteRelationship(
            arn,
            enrolmentKey,
            currentUser.affinityGroup
          )
    }
  // scalastyle:on method.length

}
