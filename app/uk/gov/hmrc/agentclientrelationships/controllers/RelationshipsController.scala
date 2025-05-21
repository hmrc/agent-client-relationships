/*
 * Copyright 2023 HM Revenue & Customs
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

import cats.implicits._
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys._
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.audit.AuditService
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.DesConnector
import uk.gov.hmrc.agentclientrelationships.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.agentclientrelationships.connectors.IfOrHipConnector
import uk.gov.hmrc.agentclientrelationships.connectors.MappingConnector
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.services._
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@Singleton
class RelationshipsController @Inject() (
  override val authConnector: AuthConnector,
  val appConfig: AppConfig,
  checkOrchestratorService: CheckRelationshipsOrchestratorService,
  checkOldAndCopyService: CheckAndCopyRelationshipsService,
  createService: CreateRelationshipsService,
  deleteService: DeleteRelationshipsService,
  findService: FindRelationshipsService,
  agentTerminationService: AgentTerminationService,
  des: DesConnector,
  ifOrHipConnector: IfOrHipConnector,
  val esConnector: EnrolmentStoreProxyConnector,
  mappingConnector: MappingConnector,
  auditService: AuditService,
  validationService: ValidationService,
  override val controllerComponents: ControllerComponents
)(implicit val executionContext: ExecutionContext)
extends BackendController(controllerComponents)
with AuthActions
with RequestAwareLogging {

  private val strideRoles = Seq(appConfig.oldAuthStrideRole, appConfig.newAuthStrideRole)

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir

  def checkForRelationship(
    arn: Arn,
    service: String,
    clientIdType: String,
    clientId: String,
    userId: Option[String]
  ): Action[AnyContent] = Action.async { implicit request =>
    checkOrchestratorService
      .checkForRelationship(
        arn,
        service,
        clientIdType,
        clientId,
        userId
      )
      .map {
        case CheckRelationshipFound => Ok
        case CheckRelationshipNotFound(message) => NotFound(toJson(message))
        case CheckRelationshipInvalidRequest => BadRequest
      }
  }

  def create(
    arn: Arn,
    serviceId: String,
    clientIdType: String,
    clientId: String
  ): Action[AnyContent] = Action.async { implicit request =>
    validationService
      .validateForEnrolmentKey(
        serviceId,
        clientIdType,
        clientId
      )
      .flatMap {
        case Right(enrolmentKey) =>
          val taxIdentifier = enrolmentKey.oneTaxIdentifier()
          authorisedClientOrStrideUserOrAgent(taxIdentifier, strideRoles) { currentUser =>
            implicit val auditData: AuditData = new AuditData()
            auditData.set(arnKey, arn)
            auditData.set(serviceKey, serviceId)
            auditData.set(clientIdKey, clientId)
            auditData.set(clientIdTypeKey, clientIdType)
            auditData.set(
              howRelationshipCreatedKey,
              if (currentUser.isStride)
                hmrcAcceptedInvitation
              else
                clientAcceptedInvitation
            )

            createService
              .createRelationship(
                arn,
                enrolmentKey,
                Set(),
                failIfCreateRecordFails = false,
                failIfAllocateAgentInESFails = true
              )
              .map {
                case Some(_) => Created
                case None =>
                  logger.warn(s"create relationship is currently in Locked state");
                  Locked
              }
              .recover {
                case upS: UpstreamErrorResponse =>
                  logger.warn(s"Could not create relationship due to ${upS.getMessage}")
                  InternalServerError(toJson(upS.getMessage))
                case NonFatal(ex) =>
                  logger.warn(s"Could not create relationship due to ${ex.getMessage}")
                  InternalServerError(toJson(ex.getMessage))
              }
          }

        case Left(error) => Future.successful(BadRequest(error))
      }
  }

  def delete(
    arn: Arn,
    serviceId: String,
    clientIdType: String,
    clientId: String
  ): Action[AnyContent] = Action.async { implicit request =>
    validationService
      .validateForEnrolmentKey(
        serviceId,
        clientIdType,
        clientId
      )
      .flatMap {
        case Right(enrolmentKey) =>
          val taxIdentifier = enrolmentKey.oneTaxIdentifier()
          authorisedUser(
            Some(arn),
            taxIdentifier,
            strideRoles
          ) { implicit currentUser =>
            (
              for {
                maybeEk <-
                  taxIdentifier match {
                    // turn a NINO-based enrolment key for IT into a MtdItId-based one if necessary
                    case nino @ Nino(_) => ifOrHipConnector.getMtdIdFor(nino).map(_.map(EnrolmentKey(Service.MtdIt, _)))
                    case _ => Future.successful(Some(enrolmentKey))
                  }
                _ <-
                  maybeEk.fold {
                    Future.successful(logger.error(s"Could not identify $taxIdentifier for $clientIdType"))
                  } { ek =>
                    deleteService.deleteRelationship(
                      arn,
                      ek,
                      currentUser.affinityGroup
                    )
                  }
              } yield NoContent
            ).recover {
              case upS: UpstreamErrorResponse =>
                logger.warn(s"Could not delete relationship: ${upS.getMessage}")
                InternalServerError(toJson(upS.getMessage))
              case NonFatal(ex) =>
                logger.warn(s"Could not delete relationship: ${ex.getMessage}")
                InternalServerError(toJson(ex.getMessage))
            }
          }
        case Left(error) => Future.successful(BadRequest(error))
      }
  }

  def getActiveRelationshipsForClient: Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsClient { identifiers: Map[Service, TaxIdentifier] =>
      findService
        .getActiveRelationshipsForClient(identifiers)
        .map(relationships =>
          Ok(
            Json.toJson(
              relationships.map { case (k, v) => (k.id, v) }
            )
          )
        )
    }
  }

  def getInactiveRelationshipsForClient: Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsClient { identifiers: Map[Service, TaxIdentifier] =>
      findService
        .getInactiveRelationshipsForClient(identifiers)
        .map(inactiveRelationships => Ok(Json.toJson(inactiveRelationships)))
    }
  }

  def getRelationshipsByServiceViaClient(service: String): Action[AnyContent] = Action.async { implicit request =>
    authorisedAsClient(service) { implicit clientId =>
      findService
        .getActiveRelationshipsForClient(clientId, Service(service))
        .map {
          case Some(relationship) => Ok(Json.toJson(relationship))
          case None => NotFound
        }
    }
  }

  def getRelationships(
    service: String,
    clientIdType: String,
    clientId: String
  ): Action[AnyContent] = Action.async { implicit request =>
    validationService
      .validateForEnrolmentKey(
        service,
        clientIdType,
        clientId
      )
      .flatMap {
        case Right(enrolmentKey) =>
          val taxIdentifier = enrolmentKey.oneTaxIdentifier()
          authorisedWithStride(appConfig.oldAuthStrideRole, appConfig.newAuthStrideRole) { _ =>
            val relationships =
              if (service == Service.MtdIt.id || service == Service.MtdItSupp.id) {
                findService.getItsaRelationshipForClient(Nino(taxIdentifier.value), Service(service))
              }
              else {
                findService.getActiveRelationshipsForClient(taxIdentifier, Service(service))
              }
            relationships.map {
              case Some(relationship) => Ok(Json.toJson(relationship))
              case None => NotFound
            }
          }
        case Left(error) => Future.successful(BadRequest(error))
      }
  }

  def getInactiveRelationshipsAgent: Action[AnyContent] = Action.async { implicit request =>
    logger.info("sialala")

    withAuthorisedAsAgent { arn =>
      findService
        .getInactiveRelationshipsForAgent(arn)
        .map { relationships =>
          if (relationships.nonEmpty)
            Ok(Json.toJson(relationships))
          else
            NotFound
        }
    }
  }

  def terminateAgent(arn: Arn): Action[AnyContent] = Action.async { implicit request =>
    withBasicAuth(appConfig.expectedAuth) {
      agentTerminationService
        .terminateAgent(arn)
        .fold(
          error => {
            logger.warn(s"unexpected error during agent termination: $arn, error = $error")
            InternalServerError
          },
          result => Ok(Json.toJson(result))
        )
    }
  }

  /*
   * This endpoint is used by agent-invitations-frontend to determine if the client has a legacy SA relationship in CESA
   * and whether the relationship has been mapped to the Arn.
   * */
  def getLegacySaRelationshipStatus(
    arn: Arn,
    nino: Nino
  ): Action[AnyContent] =
    Action async { implicit request =>
      implicit val auditData: AuditData = new AuditData()
      auditData.set(arnKey, arn)
      auditData.set(howRelationshipCreatedKey, "hasLegacyMapping")
      auditData.set(serviceKey, "mtd-it")
      auditData.set(clientIdKey, nino)
      auditData.set(clientIdTypeKey, "nino")

      withAuthorisedAsAgent { arn =>
        des
          .getClientSaAgentSaReferences(nino)
          .flatMap { references =>
            if (references.nonEmpty) {
              checkOldAndCopyService
                .intersection(references)(mappingConnector.getSaAgentReferencesFor(arn))
                .map { matching =>
                  if (matching.nonEmpty) {
                    auditData.set(saAgentRefKey, matching.mkString(","))
                    auditData.set(cesaRelationshipKey, matching.nonEmpty)
                    auditService.sendCheckCesaAndPartialAuthAuditEvent()
                    NoContent // a legacy SA relationship was found and it is mapped to the Arn
                  }
                  else
                    Ok // A legacy SA relationship was found but it is not mapped to the Arn
                }
                .recover {
                  case e: UpstreamErrorResponse if e.statusCode == 404 => Ok
                }
            }
            else
              Future successful NotFound // No legacy SA relationship was found
          }
      }
    }

}
