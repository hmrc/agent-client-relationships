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

import cats.data.EitherT
import play.api.Logger
import play.api.libs.json.JsValue
import play.api.mvc.{Action, ControllerComponents, Request, Result}
import uk.gov.hmrc.agentclientrelationships.auth.{AuthActions, CurrentUser}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.AgentFiRelationshipConnector
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.{ClientRegistrationNotFound, EnrolmentKeyNotFound, InvalidClientId, RelationshipDeleteFailed, RelationshipNotFound, UnsupportedService}
import uk.gov.hmrc.agentclientrelationships.model.invitation.{InvitationFailureResponse, RemoveAuthorisationRequest, ValidRequest}
import uk.gov.hmrc.agentclientrelationships.services.{DeleteRelationshipsServiceWithAcr, RemoveAuthorisationService, ValidationService}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Service}
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class RemoveAuthorisationController @Inject() (
  deauthorisationService: RemoveAuthorisationService,
  agentFiRelationshipConnector: AgentFiRelationshipConnector,
  deleteService: DeleteRelationshipsServiceWithAcr,
  val authConnector: AuthConnector,
  val appConfig: AppConfig,
  validationService: ValidationService,
  cc: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServices

  def removeAuthorisation(arn: Arn): Action[JsValue] = Action.async(parse.json) { implicit request =>
    request.body
      .validate[RemoveAuthorisationRequest]
      .fold(
        errs => Future.successful(BadRequest(s"Invalid payload: $errs")),
        delInvReq => {
          val responseT = for {

            validRequest <-
              EitherT.fromEither[Future](deauthorisationService.validateRequest(delInvReq.service, delInvReq.clientId))
            // TODO WG - do auth check here
            result <- EitherT(removeAuthorisationForValidRequest(arn, validRequest))
          } yield result

          responseT.value
            .map(
              _.fold(
                err => invitationErrorHandler(err, delInvReq.service, delInvReq.clientId),
                result => result
              )
            )
        }
      )
  }

  private def removeAuthorisationForValidRequest(
    arn: Arn,
    validRequest: ValidRequest
  )(implicit
    hc: HeaderCarrier,
    request: Request[Any]
  ): Future[Either[InvitationFailureResponse, Result]] =
    validRequest.service match {

      // TODO WG - do audit for PersonalIncome
      case Service.PersonalIncomeRecord =>
        agentFiRelationshipConnector
          .deleteRelationship(arn, validRequest.service.id, validRequest.suppliedClientId.value)
          .map {
            case true  => Right(NoContent)
            case false => Left(RelationshipNotFound)
          }
          .recover { case error: UpstreamErrorResponse =>
            Left[InvitationFailureResponse, Result](RelationshipDeleteFailed(error.getMessage))
          }

      case Service.MtdIt | Service.MtdItSupp =>
        deauthorisationService
          .findPartialAuthInvitation(arn, validRequest.suppliedClientId, validRequest.service)
          .flatMap {
            case Some(_) =>
              deauthorisationService
                .deletePartialAuthInvitation(arn, validRequest.suppliedClientId, validRequest.service)
                .map { result =>
                  if (result) Right[InvitationFailureResponse, Result](NoContent)
                  else
                    Left[InvitationFailureResponse, Result](
                      RelationshipDeleteFailed("Remove PartialAuth invitation failed.")
                    )
                }

            case None =>
              getEnrolmentAndDeleteRelationship(arn, validRequest)

          }

      case _ => getEnrolmentAndDeleteRelationship(arn, validRequest)

    }

  private def getEnrolmentAndDeleteRelationship(arn: Arn, validRequest: ValidRequest)(implicit
    hc: HeaderCarrier,
    request: Request[Any]
  ): Future[Either[InvitationFailureResponse, Result]] =
    (for {
      enrolmentKey <- EitherT(getEnrolmentKey(validRequest))
      result <- EitherT.right[InvitationFailureResponse] {

                  authorisedUser(
                    arn = Some(arn),
                    clientId = enrolmentKey.oneTaxIdentifier(),
                    strideRoles = Seq(appConfig.oldAuthStrideRole, appConfig.newAuthStrideRole)
                  ) { implicit currentUser =>
                    deleteRelationship(arn, enrolmentKey)
                      .map(
                        _.fold(
                          err =>
                            invitationErrorHandler(err, validRequest.service.id, validRequest.suppliedClientId.value),
                          _ => NoContent
                        )
                      )
                  }
                }
    } yield result).value

  private def getEnrolmentKey(validRequest: ValidRequest)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[InvitationFailureResponse, EnrolmentKey]] = {
    val resultT = for {
      suppliedEnrolmentKey <- EitherT(
                                validationService.validateForEnrolmentKey(
                                  validRequest.service.id,
                                  validRequest.suppliedClientId.enrolmentId,
                                  validRequest.suppliedClientId.value
                                )
                              ).leftMap(_ => EnrolmentKeyNotFound)

      enrolmentKey <-
        EitherT(
          deauthorisationService
            .replaceEnrolmentKeyForItsa(validRequest.suppliedClientId, suppliedEnrolmentKey, validRequest.service)
        )
    } yield enrolmentKey
    resultT.value
  }

  private def deleteRelationship(arn: Arn, enrolmentKey: EnrolmentKey)(implicit
    hc: HeaderCarrier,
    request: Request[Any],
    currentUser: CurrentUser
  ): Future[Either[InvitationFailureResponse, Unit]] =
    deleteService
      .deleteRelationship(arn, enrolmentKey, currentUser.affinityGroup)
      .map(Right[InvitationFailureResponse, Unit](_))
      .recover {
        case upS: UpstreamErrorResponse =>
          Left[InvitationFailureResponse, Unit](RelationshipDeleteFailed(upS.getMessage))
        case NonFatal(ex) =>
          Left[InvitationFailureResponse, Unit](RelationshipDeleteFailed(ex.getMessage))
      }

  private def invitationErrorHandler(
    invitationFailureResponse: InvitationFailureResponse,
    service: String,
    clientId: String
  ): Result =
    invitationFailureResponse match {
      case UnsupportedService =>
        val msg = s"""Unsupported service "$service""""
        Logger(getClass).warn(msg)
        UnsupportedService.getResult(msg)

      case InvalidClientId =>
        val msg =
          s"""Invalid clientId "$clientId", for service type "$service""""
        Logger(getClass).warn(msg)
        InvalidClientId.getResult(msg)

      case ClientRegistrationNotFound =>
        val msg =
          s"""The Client's MTDfB registration or SAUTR (if alt-itsa is enabled) was not found.
             | for clientId "$clientId",
             | for service type "$service"""".stripMargin
        Logger(getClass).warn(msg)
        ClientRegistrationNotFound.getResult(msg)

      case relationshipDeleteFailed @ RelationshipDeleteFailed(msg) =>
        Logger(getClass).warn(s"Could not delete relationship: $msg")
        relationshipDeleteFailed.getResult("")

      case RelationshipNotFound =>
        RelationshipNotFound.getResult("")

      case _ => BadRequest
    }

}
