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
import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.{DesConnector, MappingConnector}
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax._
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentIdentifierValue, EnrolmentService, UserId}
import uk.gov.hmrc.agentclientrelationships.services._
import uk.gov.hmrc.agentclientrelationships.support.{AdminNotFound, RelationshipDeletePending, RelationshipNotFound}
import uk.gov.hmrc.agentmtdidentifiers.model.Service._
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.{HeaderCarrier, Upstream5xxResponse, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Provider, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class RelationshipsController @Inject()(
  override val authConnector: AuthConnector,
  val appConfig: AppConfig,
  checkService: CheckRelationshipsService,
  checkOldAndCopyService: CheckAndCopyRelationshipsService,
  createService: CreateRelationshipsService,
  deleteService: DeleteRelationshipsService,
  findService: FindRelationshipsService,
  agentUserService: AgentUserService,
  agentTerminationService: AgentTerminationService,
  des: DesConnector,
  ecp: Provider[ExecutionContext],
  mappingConnector: MappingConnector,
  auditService: AuditService,
  override val controllerComponents: ControllerComponents)
    extends BackendController(controllerComponents)
    with AuthActions {

  private val strideRoles = Seq(appConfig.oldAuthStrideRole, appConfig.newAuthStrideRole)

  implicit val ec: ExecutionContext = ecp.get

  def checkForRelationship(
    arn: Arn,
    service: String,
    clientIdType: String,
    clientId: String,
    userId: Option[String]): Action[AnyContent] = Action.async { implicit request =>
    val tUserId = userId.map(UserId)
    (service, clientIdType, clientId) match {
      case (HMRC_MTD_IT, IdentifierKeys.mtdItId, _) if MtdItId.isValid(clientId) =>
        checkWithTaxIdentifier(arn, tUserId, MtdItId(clientId))
      case (HMRC_VAT_DEC_ORG, "vrn", _) if Vrn.isValid(clientId) => checkWithVrn(arn, Vrn(clientId))
      case (HMRC_MTD_VAT, _, _) if Vrn.isValid(clientId)         => checkWithTaxIdentifier(arn, tUserId, Vrn(clientId))
      case (HMRC_MTD_IT, "NI", _) if Nino.isValid(clientId) =>
        des
          .getMtdIdFor(Nino(clientId))
          .flatMap(_.fold(Future.successful(NotFound(toJson("RELATIONSHIP_NOT_FOUND"))))(
            checkWithTaxIdentifier(arn, tUserId, _)))
      case (IR_SA, _, _) if Nino.isValid(clientId) =>
        withIrSaSuspensionCheck(arn) {
          checkLegacyWithNinoOrPartialAuth(arn, Nino(clientId))
        }
      case (HMRC_TERS_ORG, _, _) if Utr.isValid(clientId) => checkWithTaxIdentifier(arn, tUserId, Utr(clientId))
      case (HMRC_TERSNT_ORG, _, _) if Urn.isValid(clientId) =>
        checkWithTaxIdentifier(arn, tUserId, Urn(clientId))
      case (HMRC_CGT_PD, IdentifierKeys.cgtPdRef, _) if CgtRef.isValid(clientId) =>
        checkWithTaxIdentifier(arn, tUserId, CgtRef(clientId))
      case (HMRC_PPT_ORG, IdentifierKeys.etmpRegNum, _) if PptRef.isValid(clientId) =>
        checkWithTaxIdentifier(arn, tUserId, PptRef(clientId))
      case (HMRC_CBC_ORG, IdentifierKeys.cbcId, _) if CbcId.isValid(clientId) =>
        checkWithTaxIdentifier(arn, tUserId, CbcId(clientId))
      case (HMRC_CBC_NON_UK_ORG, IdentifierKeys.cbcId, _) if CbcNonUkId.isValid(clientId) =>
        checkWithTaxIdentifier(arn, tUserId, CbcNonUkId(clientId))
      case _ =>
        logger.warn(s"invalid (service, clientIdType) combination or clientId is invalid")
        Future.successful(BadRequest)
    }
  }

  private def withIrSaSuspensionCheck(agentId: TaxIdentifier)(
    proceed: => Future[Result])(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Result] =
    des.getAgentRecord(agentId).flatMap {
      case None => Future.successful(BadRequest)
      case Some(record) if record.isSuspended && record.suspendedFor("ITSA") =>
        logger.warn(s"agent with id : ${agentId.value} is suspended for regime ITSA")
        Future.successful(BadRequest)
      case _ => proceed
    }

  private def checkWithTaxIdentifier(arn: Arn, maybeUserId: Option[UserId], taxIdentifier: TaxIdentifier)(
    implicit request: Request[_]) = {
    implicit val auditData: AuditData = new AuditData()
    auditData.set("arn", arn)
    maybeUserId.foreach(auditData.set("credId", _))

    val result = for {
      _ <- agentUserService.getAgentAdminUserFor(arn)
      /* The method above (agentUserService.getAgentAdminUserFor) is no longer necessary and is called only so that
         the relevant auditData fields are populated, which our tests expect.
         TODO: Must refactor to remove these hidden side-effects and put them somewhere more explicit.
         Statements populating audit data should be gathered together as much as possible, preferably at the controller level,
         and not scattered throughout lots of methods in different classes. */
      isClear <- deleteService.checkDeleteRecordAndEventuallyResume(taxIdentifier, arn)
      res <- if (isClear) checkService.checkForRelationship(arn, maybeUserId, taxIdentifier)
            else Future.failed(RelationshipDeletePending())
    } yield {
      if (res) Right(true) else throw RelationshipNotFound("RELATIONSHIP_NOT_FOUND")
    }

    result
      .recoverWith {
        case RelationshipNotFound(errorCode) =>
          checkOldRelationship(arn, taxIdentifier, errorCode)
        case AdminNotFound(errorCode) =>
          checkOldRelationship(arn, taxIdentifier, errorCode)
        case e @ RelationshipDeletePending() =>
          logger.warn("Denied access because relationship removal is pending.")
          Future.successful(Left(e.getMessage))
      }
      .map {
        case Left(errorCode) => NotFound(toJson(errorCode)) // no access (due to error)
        case Right(false)    => NotFound(toJson("RELATIONSHIP_NOT_FOUND")) // do not grant access
        case Right(true)     => Ok // grant access
      }
  }

  private def checkOldRelationship(arn: Arn, taxIdentifier: TaxIdentifier, errorCode: String)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData): Future[Either[String, Boolean]] =
    checkOldAndCopyService
      .checkForOldRelationshipAndCopy(arn, taxIdentifier)
      .map {
        case AlreadyCopiedDidNotCheck | CopyRelationshipNotEnabled | CheckAndCopyNotImplemented =>
          Left(errorCode)
        case cesaResult =>
          Right(cesaResult.grantAccess)
      }
      .recover {
        case upS: Upstream5xxResponse =>
          throw upS
        case NonFatal(ex) =>
          logger.warn(
            s"Error in checkForOldRelationshipAndCopy for ${arn.value}, ${taxIdentifier.value} (${taxIdentifier.getClass.getName}), ${ex.getMessage}")
          Left(errorCode)
      }

  private def checkLegacyWithNinoOrPartialAuth(arn: Arn, nino: Nino)(implicit request: Request[_]) = {
    implicit val auditData: AuditData = new AuditData()
    auditData.set("arn", arn)

    checkOldAndCopyService
      .hasLegacyRelationshipInCesaOrHasPartialAuth(arn, nino)
      .map {
        case true  => Ok
        case false => NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      }
      .recover {
        case upS: Upstream5xxResponse => throw upS
        case NonFatal(ex) =>
          logger.warn(
            s"checkWithNino: lookupCesaForOldRelationship failed for arn: ${arn.value}, nino: $nino, ${ex.getMessage}")
          NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      }
  }

  def create(arn: Arn, service: String, clientIdType: String, clientId: String): Action[AnyContent] = Action.async {
    implicit request =>
      getTaxIdentifier(service, clientIdType, clientId) match {
        case Right((_, taxIdentifier)) =>
          authorisedClientOrStrideUserOrAgent(taxIdentifier, strideRoles) { currentUser =>
            implicit val auditData: AuditData = new AuditData()
            auditData.set("arn", arn)

            createService
              .createRelationship(arn, taxIdentifier, Set(), false, true)
              .map {
                case Some(_) => Created
                case None    => logger.warn(s"create relationship is currently in Locked state"); Locked
              }
              .recover {
                case upS: Upstream5xxResponse =>
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

  private def getTaxIdentifier(
    service: String,
    clientType: String,
    clientId: String): Either[String, (String, TaxIdentifier)] =
    (service, clientType) match {
      case (HMRC_MTD_IT, IdentifierKeys.mtdItId) if MtdItId.isValid(clientId) => Right((HMRC_MTD_IT, MtdItId(clientId)))
      case (HMRC_MTD_IT, IdentifierKeys.ni) if Nino.isValid(clientId)         => Right((HMRC_MTD_IT, Nino(clientId)))
      case (HMRC_MTD_VAT, IdentifierKeys.vrn) if Vrn.isValid(clientId)        => Right((HMRC_MTD_VAT, Vrn(clientId)))
      case (IR_SA, "ni") if Nino.isValid(clientId)                            => Right((IR_SA, Nino(clientId)))
      case (HMRC_VAT_DEC_ORG, "vrn") if Vrn.isValid(clientId)                 => Right((HMRC_VAT_DEC_ORG, Vrn(clientId)))
      case (HMRC_TERS_ORG, IdentifierKeys.sautr) if Utr.isValid(clientId)     => Right((HMRC_TERS_ORG, Utr(clientId)))
      case (HMRC_TERSNT_ORG, IdentifierKeys.urn) if Urn.isValid(clientId)     => Right((HMRC_TERSNT_ORG, Urn(clientId)))
      case (HMRC_CGT_PD, IdentifierKeys.cgtPdRef) if CgtRef.isValid(clientId) => Right((HMRC_CGT_PD, CgtRef(clientId)))
      case (HMRC_CBC_ORG, IdentifierKeys.cbcId) if CbcId.isValid(clientId)    => Right((HMRC_CBC_ORG, CbcId(clientId)))
      case (HMRC_CBC_NON_UK_ORG, IdentifierKeys.cbcId) if CbcId.isValid(clientId) =>
        Right((HMRC_CBC_NON_UK_ORG, CbcId(clientId)))
      case (HMRC_PPT_ORG, IdentifierKeys.etmpRegNum) if PptRef.isValid(clientId) =>
        Right((HMRC_PPT_ORG, PptRef(clientId)))
      case (a, b) => Left(s"invalid combination ($a, $b) or clientId is invalid")
    }

  def delete(arn: Arn, service: String, clientIdType: String, clientId: String): Action[AnyContent] = Action.async {
    implicit request =>
      getTaxIdentifier(service, clientIdType, clientId) match {
        case Right((_, taxIdentifier)) =>
          authorisedUser(arn, taxIdentifier, strideRoles) { implicit currentUser =>
            (for {
              id <- taxIdentifier match {
                     case nino @ Nino(_) => des.getMtdIdFor(nino)
                     case _              => Future successful Option(taxIdentifier)
                   }
              _ <- id.fold {
                    Future.successful(logger.error(s"Could not identify $taxIdentifier for $clientIdType"))
                  } {
                    deleteService.deleteRelationship(arn, _, currentUser.affinityGroup)
                  }
            } yield NoContent)
              .recover {
                case upS: Upstream5xxResponse =>
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

  private def checkWithVrn(arn: Arn, vrn: Vrn)(implicit request: Request[_]) = {
    implicit val auditData: AuditData = new AuditData()
    auditData.set("arn", arn)

    checkOldAndCopyService
      .lookupESForOldRelationship(arn, vrn)
      .map {
        case references if references.nonEmpty =>
          Ok
        case _ =>
          NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      }
      .recover {
        case upS: Upstream5xxResponse => throw upS
        case NonFatal(_) =>
          logger.warn("checkWithVrn: lookupESForOldRelationship failed")
          NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      }
  }

  def getActiveRelationshipsForClient: Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsClient { identifiers: Map[EnrolmentService, EnrolmentIdentifierValue] =>
      findService
        .getActiveRelationshipsForClient(identifiers)
        .map(relationships => Ok(Json.toJson(relationships.map { case (k, v) => (k.value, v) })))
    }
  }

  def getInactiveRelationshipsForClient: Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsClient { identifiers: Map[EnrolmentService, EnrolmentIdentifierValue] =>
      findService
        .getInactiveRelationshipsForClient(identifiers)
        .map(inactiveRelationships => Ok(Json.toJson(inactiveRelationships)))
    }
  }

  def getRelationshipsByServiceViaClient(service: String): Action[AnyContent] = Action.async { implicit request =>
    authorisedAsClient(service) { implicit clientId =>
      findService.getActiveRelationshipsForClient(clientId).map {
        case Some(relationship) => Ok(Json.toJson(relationship))
        case None               => NotFound
      }
    }
  }

  def getRelationships(service: String, clientIdType: String, clientId: String): Action[AnyContent] = Action.async {
    implicit request =>
      getTaxIdentifier(service, clientIdType, clientId) match {
        case Right((service, taxIdentifier)) =>
          authorisedWithStride(appConfig.oldAuthStrideRole, appConfig.newAuthStrideRole) { _ =>
            val relationships = if (service == HMRC_MTD_IT) {
              findService.getItsaRelationshipForClient(Nino(taxIdentifier.value))
            } else {
              findService.getActiveRelationshipsForClient(taxIdentifier)
            }
            relationships.map {
              case Some(relationship) => Ok(Json.toJson(relationship))
              case None               => NotFound
            }
          }
        case Left(error) => Future.successful(BadRequest(error))
      }
  }

  def cleanCopyStatusRecord(arn: Arn, mtdItId: MtdItId): Action[AnyContent] = Action.async { _ =>
    checkOldAndCopyService
      .cleanCopyStatusRecord(arn, mtdItId)
      .map(_ => NoContent)
      .recover {
        case ex: RelationshipNotFound => NotFound(ex.getMessage)
      }
  }

  def getInactiveRelationshipsAgent: Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsAgent { arn =>
      findService.getInactiveRelationshipsForAgent(arn).map { relationships =>
        if (relationships.nonEmpty) Ok(Json.toJson(relationships))
        else NotFound
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
          }, { result =>
            Ok(Json.toJson(result))
          }
        )
    }
  }

  /*
   * This endpoint is used by agent-invitations-frontend to determine if the client has a legacy SA relationship in CESA
   * and whether the relationship has been mapped to the Arn.
   * */
  def getLegacySaRelationshipStatus(arn: Arn, nino: Nino): Action[AnyContent] = Action async { implicit request =>
    implicit val auditData: AuditData = new AuditData()
    auditData.set("arn", arn)
    auditData.set("Journey", "hasLegacyMapping")
    auditData.set("service", "mtd-it")
    auditData.set("clientId", nino)
    auditData.set("clientIdType", "nino")

    withAuthorisedAsAgent { arn =>
      des.getClientSaAgentSaReferences(nino).flatMap { references =>
        if (references.nonEmpty) {
          checkOldAndCopyService
            .intersection(references)(mappingConnector.getSaAgentReferencesFor(arn))
            .map { matching =>
              if (matching.nonEmpty) {
                auditData.set("saAgentRef", matching.mkString(","))
                auditData.set("CESARelationship", matching.nonEmpty)
                auditService.sendCheckCESAAuditEvent
                NoContent // a legacy SA relationship was found and it is mapped to the Arn
              } else Ok // A legacy SA relationship was found but it is not mapped to the Arn
            }
            .recover {
              case e: UpstreamErrorResponse if e.statusCode == 404 => Ok
            }
        } else Future successful NotFound // No legacy SA relationship was found
      }
    }
  }
}
