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
import play.api.mvc.{Action, AnyContent}
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.connectors.DesConnector
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax._
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentIdentifierValue, EnrolmentService}
import uk.gov.hmrc.agentclientrelationships.services._
import uk.gov.hmrc.agentclientrelationships.support.{RelationshipDeletePending, RelationshipNotFound}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Eori, MtdItId, Utr, Vrn}
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

  def checkWithMtdItId(arn: Arn, mtdItId: MtdItId): Action[AnyContent] = checkWithTaxIdentifier(arn, mtdItId)
  def checkWithMtdVat(arn: Arn, vrn: Vrn): Action[AnyContent] = checkWithTaxIdentifier(arn, vrn)

  def checkWithNino(arn: Arn, nino: Nino): Action[AnyContent] = Action.async { implicit request =>
    des.getMtdIdFor(nino).flatMap(checkWithTaxIdentifier(arn, _)(request))
  }

  def checkWithTrust(arn: Arn, utr: Utr): Action[AnyContent] = checkWithTaxIdentifier(arn, utr)

  //noinspection ScalaStyle
  private def checkWithTaxIdentifier(arn: Arn, taxIdentifier: TaxIdentifier): Action[AnyContent] = Action.async {
    implicit request =>
      implicit val auditData: AuditData = new AuditData()
      auditData.set("arn", arn)

      val agentUserFuture = agentUserService.getAgentUserFor(arn)

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
        }
        .map {
          case Left(errorCode) => NotFound(toJson(errorCode))
          case Right(false)    => NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
          case Right(true)     => Ok
        }
  }

  def checkLegacyWithNino(arn: Arn, nino: Nino): Action[AnyContent] = Action.async { implicit request =>
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

  def createForMtdIt(arn: Arn, identifier: TaxIdentifier) = create(arn, identifier)
  def createForMtdVat(arn: Arn, identifier: TaxIdentifier) = create(arn, identifier)

  private def create(arn: Arn, identifier: TaxIdentifier) =
    AuthorisedAgentOrClientOrStrideUser(arn, identifier, strideRoles) { implicit request => _ =>
      implicit val auditData: AuditData = new AuditData()
      auditData.set("arn", arn)

      (for {
        agentUser <- agentUserService.getAgentUserFor(arn)
        _         <- createService.createRelationship(arn, identifier, Future.successful(agentUser), Set(), false, true)
      } yield ())
        .map(_ => Created)
        .recover {
          case upS: Upstream5xxResponse => throw upS
          case NonFatal(ex) =>
            Logger(getClass).warn("Could not create relationship", ex)
            NotFound(toJson(ex.getMessage))
        }
    }

  def deleteItsaRelationship(arn: Arn, mtdItId: MtdItId) = delete(arn, mtdItId)
  def deleteItsaRelationshipByNino(arn: Arn, nino: Nino) = delete(arn, nino)
  def deleteVatRelationship(arn: Arn, vrn: Vrn) = delete(arn, vrn)

  private def delete(arn: Arn, taxIdentifier: TaxIdentifier): Action[AnyContent] =
    AuthorisedAgentOrClientOrStrideUser(arn, taxIdentifier, strideRoles) { implicit request => implicit currentUser =>
      (for {
        id <- taxIdentifier match {
               case nino @ Nino(_) => des.getMtdIdFor(nino)
               case _              => Future successful taxIdentifier
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

  def checkWithVrn(arn: Arn, vrn: Vrn): Action[AnyContent] = Action.async { implicit request =>
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

  def getItsaRelationships: Action[AnyContent] = AuthorisedAsItSaClient(ec) { implicit request => clientId =>
    findService
      .getItsaRelationshipForClient(clientId)
      .map {
        case Some(relationship) => Ok(Json.toJson(relationship))
        case None               => NotFound
      }
  }

  def getVatRelationships: Action[AnyContent] = AuthorisedAsVatClient(ec) { implicit request => clientId =>
    findService.getVatRelationshipForClient(clientId).map {
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
      findService.getVatRelationshipForClient(vrn).map {
        case Some(relationship) => Ok(Json.toJson(relationship))
        case None               => NotFound
      }
  }

  def getInactiveItsaRelationshipsAgent: Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsAgent { arn =>
      findService.getInactiveItsaRelationshipForAgent(arn).map { relationships =>
        if (relationships.nonEmpty) Ok(Json.toJson(relationships))
        else NotFound
      }
    }
  }

  def getInactiveVatRelationshipsAgent: Action[AnyContent] = Action.async { implicit request =>
    withAuthorisedAsAgent { arn =>
      findService.getInactiveVatRelationshipForAgent(arn).map { relationships =>
        if (relationships.nonEmpty) Ok(Json.toJson(relationships))
        else NotFound
      }
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
}
