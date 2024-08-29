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
import uk.gov.hmrc.agentclientrelationships.connectors.{DesConnector, EnrolmentStoreProxyConnector, IFConnector, MappingConnector}
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax._
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentKey, UserId}
import uk.gov.hmrc.agentclientrelationships.services._
import uk.gov.hmrc.agentclientrelationships.support.{AdminNotFound, RelationshipDeletePending, RelationshipNotFound}
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Provider, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class RelationshipsController @Inject() (
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
  ifConnector: IFConnector,
  ecp: Provider[ExecutionContext],
  esConnector: EnrolmentStoreProxyConnector,
  mappingConnector: MappingConnector,
  auditService: AuditService,
  override val controllerComponents: ControllerComponents
) extends BackendController(controllerComponents)
    with AuthActions {

  private val strideRoles = Seq(appConfig.oldAuthStrideRole, appConfig.newAuthStrideRole)

  implicit val ec: ExecutionContext = ecp.get

  val supportedServices: Seq[Service] = appConfig.supportedServices

  def checkForRelationship(
    arn: Arn,
    service: String,
    clientIdType: String,
    clientId: String,
    userId: Option[String]
  ): Action[AnyContent] = Action.async { implicit request =>
    val tUserId = userId.map(UserId)
    def useMtdIdInEnrolmentKey(serviceId: String): Future[Result] =
      ifConnector
        .getMtdIdFor(Nino(clientId))
        .flatMap(
          _.fold(Future.successful(NotFound(toJson("RELATIONSHIP_NOT_FOUND"))))(mtdItId =>
            checkWithTaxIdentifier(
              arn,
              tUserId,
              EnrolmentKey(serviceId, mtdItId)
            )
          )
        )

    (service, clientIdType, clientId) match {
      // "special" cases
      case ("IR-SA", _, _) if Nino.isValid(clientId) =>
        withIrSaSuspensionCheck(arn) {
          checkLegacyWithNinoOrPartialAuth(arn, Nino(clientId))
        }
      case (Service.MtdIt.id, "ni" | "NI", _) if Nino.isValid(clientId) =>
        useMtdIdInEnrolmentKey(Service.MtdIt.id)
      case (Service.MtdItSupp.id, "ni" | "NI", _) if Nino.isValid(clientId) =>
        useMtdIdInEnrolmentKey(Service.MtdItSupp.id)
      case ("HMCE-VATDEC-ORG", "vrn", _) if Vrn.isValid(clientId) => checkWithVrn(arn, Vrn(clientId))
      // "normal" cases
      case (svc, idType, id) =>
        // TODO, unnecessary ES20 call?
        validateForEnrolmentKey(svc, idType, id).flatMap {
          case Right(enrolmentKey) => checkWithTaxIdentifier(arn, tUserId, enrolmentKey)
          case Left(validationError) =>
            logger.warn(s"Invalid parameters: $validationError")
            Future.successful(BadRequest)
        }
    }
  }

  private def withIrSaSuspensionCheck(
    agentId: Arn
  )(proceed: => Future[Result])(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Result] =
    des.getAgentRecord(agentId).flatMap {
      case None => Future.successful(BadRequest)
      case Some(record) if record.isSuspended && record.suspendedFor("ITSA") =>
        logger.warn(s"agent with id : ${agentId.value} is suspended for regime ITSA")
        Future.successful(BadRequest)
      case _ => proceed
    }

  private def checkWithTaxIdentifier(arn: Arn, maybeUserId: Option[UserId], enrolmentKey: EnrolmentKey)(implicit
    request: Request[_]
  ) = {
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
      isClear <- deleteService.checkDeleteRecordAndEventuallyResume(arn, enrolmentKey)
      res <- if (isClear) checkService.checkForRelationship(arn, maybeUserId, enrolmentKey)
             else Future.failed(RelationshipDeletePending())
    } yield if (res) Right(true) else throw RelationshipNotFound("RELATIONSHIP_NOT_FOUND")

    result
      .recoverWith {
        case RelationshipNotFound(errorCode) =>
          checkOldRelationship(arn, enrolmentKey, errorCode)
        case AdminNotFound(errorCode) =>
          checkOldRelationship(arn, enrolmentKey, errorCode)
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

  private def checkOldRelationship(arn: Arn, enrolmentKey: EnrolmentKey, errorCode: String)(implicit
    ec: ExecutionContext,
    hc: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData
  ): Future[Either[String, Boolean]] =
    checkOldAndCopyService
      .checkForOldRelationshipAndCopy(arn, enrolmentKey)
      .map {
        case AlreadyCopiedDidNotCheck | CopyRelationshipNotEnabled | CheckAndCopyNotImplemented =>
          Left(errorCode)
        case cesaResult =>
          Right(cesaResult.grantAccess)
      }
      .recover {
        case upS: UpstreamErrorResponse =>
          throw upS
        case NonFatal(ex) =>
          val taxIdentifier = enrolmentKey.oneTaxIdentifier()
          logger.warn(
            s"Error in checkForOldRelationshipAndCopy for ${arn.value}, ${taxIdentifier.value} (${taxIdentifier.getClass.getName}), ${ex.getMessage}"
          )
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
        case upS: UpstreamErrorResponse => throw upS
        case NonFatal(ex) =>
          logger.warn(
            s"checkWithNino: lookupCesaForOldRelationship failed for arn: ${arn.value}, nino: $nino, ${ex.getMessage}"
          )
          NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      }
  }

  def create(arn: Arn, serviceId: String, clientIdType: String, clientId: String): Action[AnyContent] = Action.async {
    implicit request =>
      validateForEnrolmentKey(serviceId, clientIdType, clientId).flatMap {
        case Right(enrolmentKey) =>
          val taxIdentifier = enrolmentKey.oneTaxIdentifier()
          authorisedClientOrStrideUserOrAgent(taxIdentifier, strideRoles) { currentUser =>
            implicit val auditData: AuditData = new AuditData()
            auditData.set("arn", arn)

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
                case None    => logger.warn(s"create relationship is currently in Locked state"); Locked
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

  // noinspection ScalaStyle
  private def validateForEnrolmentKey(serviceKey: String, clientType: String, clientId: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[String, EnrolmentKey]] =
    (serviceKey, clientType) match {
      // "special" cases
      case ("IR-SA", "ni" | "NI") if Nino.isValid(clientId) =>
        Future.successful(Right(EnrolmentKey("IR-SA", Nino(clientId))))
      case (Service.MtdIt.id, "ni" | "NI") if Nino.isValid(clientId) =>
        Future.successful(Right(EnrolmentKey(Service.MtdIt.id, Nino(clientId))))
      case (Service.MtdItSupp.id, "ni" | "NI") if Nino.isValid(clientId) =>
        Future.successful(Right(EnrolmentKey(Service.MtdItSupp.id, Nino(clientId))))
      case ("HMCE-VATDEC-ORG", "vrn") if Vrn.isValid(clientId) =>
        Future.successful(Right(EnrolmentKey("HMCE-VATDEC-ORG", Vrn(clientId))))
      case (Service.Cbc.id, CbcIdType.enrolmentId) =>
        makeSanitisedCbcEnrolmentKey(CbcId(clientId))
      // "normal" cases
      case (serviceKey, _) =>
        if (appConfig.supportedServices.exists(_.id == serviceKey)) {
          validateSupportedServiceForEnrolmentKey(serviceKey, clientType, clientId)
        } else Future.successful(Left(s"Unknown service $serviceKey"))
    }

  /** This is needed because sometimes we call the ACR endpoints specifying HMRC-CBC-ORG but it could actually be
    * HMRC-CBC-NONUK-ORG (if the caller has no way of knowing). We check and correct the enrolment key as needed. Also,
    * if it is HMRC-CBC-ORG, we must add a UTR to the enrolment key (alongside the cbcId) as required by specs. First,
    * query EACD assuming enrolment to be HMRC-CBC-ORG (UK version). If that fails, try as HMRC-CBC-NONUK-ORG.
    */
  private[controllers] def makeSanitisedCbcEnrolmentKey(
    cbcId: CbcId
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Either[String, EnrolmentKey]] =
    // Try as HMRC-CBC-ORG (UK version)
    esConnector.queryKnownFacts(Service.Cbc, Seq(Identifier("cbcId", cbcId.value))).flatMap {
      case None => // No results from EACD for HMRC-CBC-ORG (UK version). Try non-uk instead.
        logger.info(s"CbcId ${cbcId.value} not found as as HMRC-CBC-ORG. Trying as HMRC-CBC-NONUK-ORG.")
        esConnector.queryKnownFacts(Service.CbcNonUk, Seq(Identifier("cbcId", cbcId.value))).map {
          case Some(_) => Right(EnrolmentKey(Service.CbcNonUk.id, Seq(Identifier(CbcIdType.enrolmentId, cbcId.value))))
          case None    => Left(s"CbcId ${cbcId.value}: tried as both HMRC-CBC-ORG and HMRC-CBC-NONUK-ORG, not found.")
        }
      case Some(identifiers) =>
        Future.successful(
          Right(
            EnrolmentKey(
              Service.Cbc.id,
              identifiers
            )
          )
        )
    }

  private def validateSupportedServiceForEnrolmentKey(
    serviceKey: String,
    taxIdType: String,
    clientId: String
  ): Future[Either[String, EnrolmentKey]] = {
    val service: Service = Service.forId(serviceKey)
    val clientIdType: ClientIdType[TaxIdentifier] = service.supportedClientIdType
    if (taxIdType == clientIdType.enrolmentId) {
      if (clientIdType.isValid(clientId))
        Future.successful(Right(EnrolmentKey(service, clientIdType.createUnderlying(clientId))))
      else
        Future.successful(
          Left(s"Identifier $clientId of stated type $taxIdType provided for service $serviceKey failed validation")
        )
    } else
      Future.successful(Left(s"Identifier $clientId of stated type $taxIdType cannot be used for service $serviceKey"))
  }

  def delete(arn: Arn, serviceId: String, clientIdType: String, clientId: String): Action[AnyContent] = Action.async {
    implicit request =>
      validateForEnrolmentKey(serviceId, clientIdType, clientId).flatMap {
        case Right(enrolmentKey) =>
          val taxIdentifier =
            enrolmentKey.oneTaxIdentifier()
          authorisedUser(arn, taxIdentifier, strideRoles) { implicit currentUser =>
            (for {
              maybeEk <- taxIdentifier match {
                           // turn a NINO-based enrolment key for IT into a MtdItId-based one if necessary
                           case nino @ Nino(_) =>
                             ifConnector.getMtdIdFor(nino).map(_.map(EnrolmentKey(Service.MtdIt, _)))
                           case _ => Future.successful(Some(enrolmentKey))
                         }
              _ <- maybeEk.fold {
                     Future.successful(logger.error(s"Could not identify $taxIdentifier for $clientIdType"))
                   } { ek =>
                     deleteService
                       .deleteRelationship(arn, ek, currentUser.affinityGroup)
                   }
            } yield NoContent)
              .recover {
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
        case upS: UpstreamErrorResponse => throw upS
        case NonFatal(_) =>
          logger.warn("checkWithVrn: lookupESForOldRelationship failed")
          NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      }
  }

  def getActiveRelationshipsForClient: Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsClient { identifiers: Map[Service, TaxIdentifier] =>
      findService
        .getActiveRelationshipsForClient(identifiers)
        .map(relationships => Ok(Json.toJson(relationships.map { case (k, v) => (k.id, v) })))
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
      findService.getActiveRelationshipsForClient(clientId).map {
        case Some(relationship) => Ok(Json.toJson(relationship))
        case None               => NotFound
      }
    }
  }

  def getRelationships(service: String, clientIdType: String, clientId: String): Action[AnyContent] = Action.async {
    implicit request =>
      validateForEnrolmentKey(service, clientIdType, clientId).flatMap {
        case Right(enrolmentKey) =>
          val taxIdentifier = enrolmentKey.oneTaxIdentifier()
          authorisedWithStride(appConfig.oldAuthStrideRole, appConfig.newAuthStrideRole) { _ =>
            val relationships = if (service == Service.MtdIt.id) {
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

  // Note test only?? Move!
  def cleanCopyStatusRecord(arn: Arn, mtdItId: MtdItId): Action[AnyContent] = Action.async { _ =>
    checkOldAndCopyService
      .cleanCopyStatusRecord(arn, mtdItId)
      .map(_ => NoContent)
      .recover { case ex: RelationshipNotFound =>
        NotFound(ex.getMessage)
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
          },
          result => Ok(Json.toJson(result))
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
