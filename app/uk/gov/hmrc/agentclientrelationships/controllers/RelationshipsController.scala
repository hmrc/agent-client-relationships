/*
 * Copyright 2019 HM Revenue & Customs
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

import javax.inject.{Inject, Named, Provider, Singleton}
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, Request}
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.connectors.DesConnector
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax._
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentIdentifierValue, EnrolmentService}
import uk.gov.hmrc.agentclientrelationships.services._
import uk.gov.hmrc.agentclientrelationships.support.{AdminNotFound, RelationshipDeletePending, RelationshipNotFound}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Utr, Vrn}
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.Upstream5xxResponse
import uk.gov.hmrc.play.bootstrap.controller.BaseController

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class RelationshipsController @Inject()(
  override val authConnector: AuthConnector,
  checkService: CheckRelationshipsService,
  checkOldAndCopyService: CheckAndCopyRelationshipsService,
  createService: CreateRelationshipsService,
  deleteService: DeleteRelationshipsService,
  findService: FindRelationshipsService,
  agentUserService: AgentUserService,
  des: DesConnector,
  ecp: Provider[ExecutionContext],
  @Named("old.auth.stride.role") oldStrideRole: String,
  @Named("new.auth.stride.role") newStrideRole: String)
    extends BaseController
    with AuthActions {

  private val strideRoles = Seq(oldStrideRole, newStrideRole)

  implicit val ec: ExecutionContext = ecp.get

  private val utrPattern = "^\\d{10}$".r

  def checkForRelationship(arn: Arn, service: String, clientIdType: String, clientId: String): Action[AnyContent] =
    Action.async { implicit request =>
      if (service == "HMRC-MTD-IT" && MtdItId.isValid(clientId)) {
        checkWithTaxIdentifier(arn, MtdItId(clientId))
      } else if (service == "HMCE-VATDEC-ORG" && Vrn.isValid(clientId)) {
        checkWithVrn(arn, Vrn(clientId))
      } else if (service == "HMRC-MTD-VAT" && Vrn.isValid(clientId)) {
        checkWithTaxIdentifier(arn, Vrn(clientId))
      } else if (service == "HMRC-MTD-IT" && Nino.isValid(clientId)) {
        des.getMtdIdFor(Nino(clientId)).flatMap(checkWithTaxIdentifier(arn, _))
      } else if (service == "IR-SA" && Nino.isValid(clientId)) {
        checkLegacyWithNino(arn, Nino(clientId))
      } else if (service == "HMRC-TERS-ORG" && clientId.matches(utrPattern.regex)) {
        checkWithTaxIdentifier(arn, Utr(clientId))
      } else {
        Logger.warn(
          s"bad service and clientId combination for checking relationship, service: $service, clientId: $clientId")
        Future.successful(BadRequest)
      }
    }

  private def checkWithTaxIdentifier(arn: Arn, taxIdentifier: TaxIdentifier)(implicit request: Request[_]) = {
    implicit val auditData: AuditData = new AuditData()
    auditData.set("arn", arn)

    val agentUserFuture = agentUserService.getAgentAdminUserFor(arn)

    val result = for {
      agentUser <- agentUserFuture
      isClear   <- deleteService.checkDeleteRecordAndEventuallyResume(taxIdentifier, arn)
      result <- if (isClear) checkService.checkForRelationship(taxIdentifier, agentUser)
               else raiseError(RelationshipDeletePending())
    } yield result

    result
      .recoverWith {
        case RelationshipNotFound(errorCode) =>
          checkOldAndCopyService
            .checkForOldRelationshipAndCopy(arn, taxIdentifier, agentUserFuture)
            .map {
              case AlreadyCopiedDidNotCheck | CopyRelationshipNotEnabled =>
                Left(errorCode)
              case cesaResult =>
                Right(cesaResult.grantAccess)
            }
            .recover {
              case upS: Upstream5xxResponse =>
                throw upS
              case NonFatal(ex) =>
                Logger(getClass).warn(
                  s"Error in checkForOldRelationshipAndCopy for ${arn.value}, ${taxIdentifier.value} (${taxIdentifier.getClass.getName})",
                  ex)
                Left(errorCode)
            }
        case e @ RelationshipDeletePending() =>
          Logger(getClass).warn("Denied access because relationship removal is pending.")
          Future.successful(Left(e.getMessage))

        case e: AdminNotFound =>
          Logger(getClass).warn("Denied access because no admin users are found")
          Future.successful(Left(e.getMessage))
      }
      .map {
        case Left(errorCode) => NotFound(toJson(errorCode))
        case Right(false)    => NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
        case Right(true)     => Ok
      }
  }

  private def checkLegacyWithNino(arn: Arn, nino: Nino)(implicit request: Request[_]) = {
    implicit val auditData: AuditData = new AuditData()
    auditData.set("arn", arn)

    checkOldAndCopyService
      .lookupCesaForOldRelationship(arn, nino)
      .map {
        case references if references.nonEmpty => Ok
        case _                                 => NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      }
      .recover {
        case upS: Upstream5xxResponse =>
          throw upS
        case NonFatal(ex) =>
          Logger(getClass)
            .warn(s"checkWithNino: lookupCesaForOldRelationship failed for arn: ${arn.value}, nino: $nino", ex)
          NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      }
  }

  def create(arn: Arn, service: String, clientIdType: String, clientId: String): Action[AnyContent] =
    AuthorisedAgentOrClientOrStrideUser(arn, getTaxIdentifier(service, clientId), strideRoles) {
      implicit request => _ =>
        implicit val auditData: AuditData = new AuditData()
        auditData.set("arn", arn)

        println(s"clientId is $clientId")
        Logger.error(s"clientId is $clientId")

<<<<<<< HEAD
      (for {
        agentUser <- agentUserService.getAgentAdminUserFor(arn)
        _         <- createService.createRelationship(arn, identifier, Future.successful(agentUser), Set(), false, true)
      } yield ())
        .map(_ => Created)
        .recover {
          case upS: Upstream5xxResponse => throw upS
          case NonFatal(ex) =>
            Logger(getClass).warn("Could not create relationship", ex)
            NotFound(toJson(ex.getMessage))
        }
=======
        (for {
          agentUser <- agentUserService.getAgentUserFor(arn)
          _ <- createService.createRelationship(
                arn,
                getTaxIdentifier(service, clientId),
                Future.successful(agentUser),
                Set(),
                false,
                true)
        } yield ())
          .map(_ => Created)
          .recover {
            case upS: Upstream5xxResponse => throw upS
            case NonFatal(ex) =>
              Logger(getClass).warn("Could not create relationship", ex)
              NotFound(toJson(ex.getMessage))
          }
    }

  private def getTaxIdentifier(service: String, clientId: String) =
    if (service == "HMRC-MTD-IT" && MtdItId.isValid(clientId)) {
      MtdItId(clientId)
    } else if (service == "HMRC-MTD-IT" && Nino.isValid(clientId)) {
      Nino(clientId)
    } else if (service == "HMRC-MTD-VAT" && Vrn.isValid(clientId)) {
      Vrn(clientId)
    } else if (service == "HMRC-TERS-ORG" && clientId.matches(utrPattern.regex)) {
      Utr(clientId)
    } else {
      throw new RuntimeException("")
>>>>>>> PUT      /agent/:arn/service/:service/client/:clientIdType/:clientId
    }

  def delete(arn: Arn, service: String, clientIdType: String, clientId: String): Action[AnyContent] =
    AuthorisedAgentOrClientOrStrideUser(arn, getTaxIdentifier(service, clientId), strideRoles) { implicit request => implicit currentUser =>
      (for {
        id <- getTaxIdentifier(service, clientId) match {
               case nino @ Nino(_) => des.getMtdIdFor(nino)
               case _              => Future successful getTaxIdentifier(service, clientId)
             }
        _ <- deleteService.deleteRelationship(arn, id)
      } yield NoContent)
        .recover {
          case upS: Upstream5xxResponse => throw upS
          case NonFatal(ex) =>
            Logger(getClass).warn("Could not delete relationship", ex)
            NotFound(toJson(ex.getMessage))
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
          Logger(getClass).warn("checkWithVrn: lookupESForOldRelationship failed")
          NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      }
  }

  def getActiveRelationships: Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsClient { identifiers: Map[EnrolmentService, EnrolmentIdentifierValue] =>
      findService
        .getActiveRelationshipsForClient(identifiers)
        .map(relationships => Ok(Json.toJson(relationships.map { case (k, v) => (k.value, v) })))
    }
  }

  def getRelationshipsByServiceViaClient(service: String): Action[AnyContent] = AuthorisedAsClient(service) {
    implicit request => clientId =>
      findService.getActiveRelationships(clientId).map {
        case Some(relationship) => Ok(Json.toJson(relationship))
        case None               => NotFound
      }
  }

  def getItsaRelationshipsByNino(nino: Nino): Action[AnyContent] = AuthorisedWithStride(oldStrideRole, newStrideRole) {
    implicit request => _ =>
      findService.getItsaRelationshipForClient(nino).map {
        case Some(relationship) => Ok(Json.toJson(relationship))
        case None               => NotFound
      }
  }

  def getVatRelationshipsByVrn(vrn: Vrn): Action[AnyContent] = AuthorisedWithStride(oldStrideRole, newStrideRole) {
    implicit request => _ =>
      findService.getActiveRelationships(vrn).map {
        case Some(relationship) => Ok(Json.toJson(relationship))
        case None               => NotFound
      }
  }

  def cleanCopyStatusRecord(arn: Arn, mtdItId: MtdItId): Action[AnyContent] = Action.async { implicit request =>
    checkOldAndCopyService
      .cleanCopyStatusRecord(arn, mtdItId)
      .map(_ => NoContent)
      .recover {
        case ex: RelationshipNotFound => NotFound(ex.getMessage)
      }
  }

  def getInactiveRelationshipsAgent(service: String): Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsAgent { arn =>
      findService.getInactiveRelationships(arn, service).map { relationships =>
        if (relationships.nonEmpty) Ok(Json.toJson(relationships))
        else NotFound
      }
    }
  }
}
