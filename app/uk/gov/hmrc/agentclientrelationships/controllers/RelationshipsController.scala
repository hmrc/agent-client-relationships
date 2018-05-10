/*
 * Copyright 2018 HM Revenue & Customs
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
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent}
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.auth.{AuthActions, CurrentUser}
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax._
import uk.gov.hmrc.agentclientrelationships.services.{AlreadyCopiedDidNotCheck, CopyRelationshipNotEnabled, RelationshipsService}
import uk.gov.hmrc.agentclientrelationships.support.RelationshipNotFound
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.Upstream5xxResponse
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._
import uk.gov.hmrc.play.microservice.controller.BaseController

import scala.concurrent.Future
import scala.util.control.NonFatal

@Singleton
class RelationshipsController @Inject()(override val authConnector: AuthConnector, service: RelationshipsService)
    extends BaseController
    with AuthActions {

  def checkWithMtdItId(arn: Arn, mtdItId: MtdItId): Action[AnyContent] = checkWithTaxIdentifier(arn, mtdItId)

  def checkWithMtdVat(arn: Arn, vrn: Vrn): Action[AnyContent] = checkWithTaxIdentifier(arn, vrn)

  private def checkWithTaxIdentifier(arn: Arn, identifier: TaxIdentifier): Action[AnyContent] = Action.async {
    implicit request =>
      implicit val auditData = new AuditData()
      auditData.set("arn", arn)

      val agentUserFuture = service.getAgentUserFor(arn)

      val result = for {
        agentUser <- agentUserFuture
        result    <- service.checkForRelationship(identifier, agentUser)
      } yield result

      result
        .recoverWith {
          case RelationshipNotFound(errorCode) =>
            service
              .checkForOldRelationshipAndCopy(arn, identifier, agentUserFuture)
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
                  Logger.warn(
                    s"Error in checkForOldRelationshipAndCopy for ${arn.value}, ${identifier.value} (${identifier.getClass.getName})",
                    ex)
                  Left(errorCode)
              }
        }
        .map {
          case Left(errorCode) => NotFound(toJson(errorCode))
          case Right(false)    => NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
          case Right(true)     => Ok
        }
  }

  def checkWithNino(arn: Arn, nino: Nino): Action[AnyContent] = Action.async { implicit request =>
    implicit val auditData = new AuditData()
    auditData.set("arn", arn)

    service
      .lookupCesaForOldRelationship(arn, nino)
      .map {
        case references if references.nonEmpty => Ok
        case _                                 => NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      }
      .recover {
        case upS: Upstream5xxResponse =>
          throw upS
        case NonFatal(ex) =>
          Logger.warn(s"checkWithNino: lookupCesaForOldRelationship failed for arn: ${arn.value}, nino: $nino", ex)
          NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      }
  }

  def createForMtdIt(arn: Arn, identifier: TaxIdentifier) = create(arn, identifier)

  def createForMtdVat(arn: Arn, identifier: TaxIdentifier) = create(arn, identifier)

  private def create(arn: Arn, identifier: TaxIdentifier) = AuthorisedAgentOrClient(arn, identifier) {
    implicit request => _ =>
      implicit val auditData = new AuditData()
      auditData.set("arn", arn)

      (for {
        agentUser <- service.getAgentUserFor(arn)
        _ <- service
              .checkForRelationship(identifier, agentUser)
              .map(_ => throw new Exception("RELATIONSHIP_ALREADY_EXISTS"))
              .recover {
                case RelationshipNotFound("RELATIONSHIP_NOT_FOUND") => ()
              }
        _ <- service.createRelationship(arn, identifier, Future.successful(agentUser), Set(), false, true)
      } yield ())
        .map(_ => Created)
        .recover {
          case upS: Upstream5xxResponse => throw upS
          case NonFatal(ex) =>
            Logger.warn("Could not create relationship")
            NotFound(toJson(ex.getMessage))
        }
  }

  private def delete(arn: Arn, taxIdentifier: TaxIdentifier): Action[AnyContent] =
    AuthorisedAgentOrClient(arn, taxIdentifier) { implicit request => implicit currentUser =>
      service
        .deleteRelationship(arn, taxIdentifier)
        .map(_ => NoContent)
        .recover {
          case ex: RelationshipNotFound =>
            Logger.warn("Could not delete relationship", ex)
            NotFound(ex.getMessage)
        }
    }

  def deleteItsaRelationship(arn: Arn, mtdItId: MtdItId) = delete(arn, mtdItId)

  def deleteVatRelationship(arn: Arn, vrn: Vrn) = delete(arn, vrn)

  def cleanCopyStatusRecord(arn: Arn, mtdItId: MtdItId): Action[AnyContent] = Action.async { implicit request =>
    service
      .cleanCopyStatusRecord(arn, mtdItId)
      .map(_ => NoContent)
      .recover {
        case ex: RelationshipNotFound => NotFound(ex.getMessage)
      }
  }

  def checkWithVrn(arn: Arn, vrn: Vrn): Action[AnyContent] = Action.async { implicit request =>
    implicit val auditData = new AuditData()
    auditData.set("arn", arn)

    service
      .lookupESForOldRelationship(arn, vrn)
      .map {
        case references if references.nonEmpty => {
          Ok
        }
        case _ => {
          NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
        }
      }
      .recover {
        case upS: Upstream5xxResponse => throw upS
        case NonFatal(_) =>
          Logger.warn("checkWithVrn: lookupESForOldRelationship failed")
          NotFound(toJson("RELATIONSHIP_NOT_FOUND"))
      }
  }

  def getItsaRelationship: Action[AnyContent] = AuthorisedAsItSaClient { implicit request => clientId =>
    service.getItsaRelationshipForClient(clientId).map {
      case Some(relationship) => Ok(Json.toJson(relationship))
      case None               => NotFound
    }
  }

  def getVatRelationship: Action[AnyContent] = AuthorisedAsVatClient { implicit request => clientId =>
    service.getVatRelationshipForClient(clientId).map {
      case Some(relationship) => Ok(Json.toJson(relationship))
      case None               => NotFound
    }
  }
}
