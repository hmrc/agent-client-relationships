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
import org.mongodb.scala.MongoException
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.ControllerComponents
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.audit.AuditService
import uk.gov.hmrc.agentclientrelationships.auth.AuthActions
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.IfOrHipConnector
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientDetailsNotFound
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientDetailsResponse
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ErrorRetrievingClientDetails
import uk.gov.hmrc.agentclientrelationships.model.invitation._
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgencyDetails
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.Pending
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.services._
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDIT
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentmtdidentifiers.model.Service.MtdIt
import uk.gov.hmrc.agentmtdidentifiers.model.Service.MtdItSupp
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class ApiCreateInvitationController @Inject() (
  ifOrHipConnector: IfOrHipConnector,
  clientDetailsService: ClientDetailsService,
  knowFactsCheckService: KnowFactsCheckService,
  checkRelationshipsService: CheckRelationshipsOrchestratorService,
  agentAssuranceService: AgentAssuranceService,
  invitationsRepository: InvitationsRepository,
  partialAuthRepository: PartialAuthRepository,
  auditService: AuditService,
  val authConnector: AuthConnector,
  val appConfig: AppConfig,
  cc: ControllerComponents
)(implicit val executionContext: ExecutionContext)
extends BackendController(cc)
with AuthActions {

  val supportedServices: Seq[Service] = appConfig.supportedServicesWithoutPir

  val apiSupportedServices: Seq[Service] = appConfig.apiSupportedServices

  def createInvitation(arn: Arn): Action[JsValue] =
    Action.async(parse.json) { implicit request =>
      request.body
        .validate[ApiCreateInvitationRequest]
        .fold(
          _ => Future.successful(ApiFailureResponse.InvalidPayload.getResult),
          apiCreateInvitationRequest =>
            createInvitation(
              arn,
              apiCreateInvitationRequest,
              apiSupportedServices
            ).map { response =>
              response.fold(
                {
                  case apiErrorResults: ApiFailureResponse => apiErrorResults.getResult
                  case _ => InternalServerError
                },
                invitation => {
                  auditService.sendCreateInvitationAuditEvent(invitation)
                  Created(Json.toJson(CreateInvitationResponse(invitation.invitationId)))
                }
              )
            }
        )
    }

  private def createInvitation(
    arn: Arn,
    apiCreateInvitationInputData: ApiCreateInvitationRequest,
    supportedServices: Seq[Service]
  )(implicit
    request: RequestHeader
  ): Future[Either[ApiFailureResponse, Invitation]] = {
    val invitationT =
      for {

        // casting/parsing inputData
        suppliedClientId <- EitherT.fromEither[Future](apiCreateInvitationInputData.getSuppliedClientId(supportedServices))
        service <- EitherT.fromEither[Future](apiCreateInvitationInputData.getService(supportedServices))
        clientId <- EitherT(getClientId(suppliedClientId, service))
        clientType <- EitherT.fromEither[Future](apiCreateInvitationInputData.getClientType)

        _ <- EitherT(checkPendingInvitation(
          arn,
          service.id,
          suppliedClientId.value
        ))

        agentRecord <- EitherT.fromOptionF(agentAssuranceService.getNonSuspendedAgentRecord(arn), ApiFailureResponse.AgentSuspended)

        clientDetails <- EitherT(clientDetailsService.findClientDetails(service.id, suppliedClientId.value))
          .leftMap[ApiFailureResponse] {
            case ClientDetailsNotFound => ApiFailureResponse.ClientRegistrationNotFound
            case ErrorRetrievingClientDetails(_, _) => ApiFailureResponse.ClientRegistrationNotFound
          }
          .flatMap[ApiFailureResponse, ClientDetailsResponse] { clientDetailsResponse =>
            if (clientDetailsResponse.status.nonEmpty)
              EitherT.leftT[Future, ClientDetailsResponse](ApiFailureResponse.VatClientInsolvent)
            else
              EitherT.rightT[Future, ApiFailureResponse](clientDetailsResponse)
          }

        _ <- EitherT.fromEither[Future](knowFactsCheckService.checkKnowFacts(apiCreateInvitationInputData.knownFact, clientDetails))
          .leftMap {
            case KnowFactsFailure.UnsupportedKnowFacts => ApiFailureResponse.InvalidPayload
            case KnowFactsFailure.PostcodeFormatInvalid => ApiFailureResponse.PostcodeFormatInvalid
            case KnowFactsFailure.VatRegDateFormatInvalid => ApiFailureResponse.VatRegDateFormatInvalid
            case KnowFactsFailure.PostcodeDoesNotMatch => ApiFailureResponse.PostcodeDoesNotMatch
            case KnowFactsFailure.VatRegDateDoesNotMatch => ApiFailureResponse.VatRegDateDoesNotMatch
          }

        _ <- EitherT(getExistingRelationship(
          arn,
          service.id,
          suppliedClientId.enrolmentId,
          suppliedClientId.value
        ))

        // create invitation
        invitation <- EitherT(
          create(
            arn = arn,
            service = service,
            clientId = clientId,
            suppliedClientId = suppliedClientId,
            clientName = clientDetails.name,
            clientType = clientType,
            agentDetails = agentRecord.agencyDetails
          )
        )
      } yield invitation
    invitationT.value
  }

  private def create(
    arn: Arn,
    service: Service,
    clientId: ClientId,
    suppliedClientId: ClientId,
    clientName: String,
    clientType: Option[String],
    agentDetails: AgencyDetails
  )(implicit requestHeader: RequestHeader): Future[Either[ApiFailureResponse, Invitation]] = {
    val expiryDate = currentTime().plusSeconds(invitationExpiryDuration.toSeconds).toLocalDate
    (for {
      invitation <- invitationsRepository.create(
        arn.value,
        service,
        clientId,
        suppliedClientId,
        clientName,
        agentDetails.agencyName,
        agentDetails.agencyEmail,
        expiryDate,
        clientType
      )
    } yield {
      logger.info(s"""Created invitation with id: "${invitation.invitationId}".""")
      Right(invitation)
    }).recover {
      case e: MongoException if e.getMessage.contains("E11000 duplicate key error") => Left(ApiFailureResponse.DuplicateAuthorisationRequest(None))
    }
  }

  private def getClientId(
    suppliedClientId: ClientId,
    service: Service
  )(implicit
    requestHeader: RequestHeader
  ): Future[Either[ApiFailureResponse, ClientId]] =
    (service, suppliedClientId.typeId) match {
      case (MtdIt | MtdItSupp, NinoType.id) =>
        ifOrHipConnector
          .getMtdIdFor(Nino(suppliedClientId.value))
          .map(
            _.fold[Either[ApiFailureResponse, ClientId]](Right(suppliedClientId))(mdtId =>
              Right(ClientIdentifier(mdtId))
            )
          )
      case _ => Future successful Right(suppliedClientId)
    }

  private def checkPendingInvitation(
    arn: Arn,
    service: String,
    suppliedClientId: String
  ): Future[Either[ApiFailureResponse, Boolean]] =
    (for {
      _ <- EitherT(getPendingInvitation(
        arn,
        service,
        suppliedClientId
      ))
      _ <-
        if (multiAgentServices.contains(service))
          EitherT(getPendingInvitation(
            arn,
            multiAgentServicesOtherService(service),
            suppliedClientId
          ))
        else
          EitherT[
            Future,
            ApiFailureResponse,
            Boolean
          ](Future.successful(Right(false)))

    } yield false).value

  private def getPendingInvitation(
    arn: Arn,
    service: String,
    clientId: String
  ): Future[Either[ApiFailureResponse, Boolean]] = invitationsRepository
    .findAllForAgent(
      arn.value,
      Seq(service),
      Seq(clientId),
      isSuppliedClientId = true
    )
    .map(_.filter(_.status == Pending))
    .map {
      case Nil => Right(false)
      case invitation +: _ => Left(ApiFailureResponse.DuplicateAuthorisationRequest(Some(invitation.invitationId)))
    }

  private def getExistingRelationship(
    arn: Arn,
    service: String,
    clientIdType: String,
    clientId: String
  )(implicit
    requestHeader: RequestHeader
  ): Future[Either[ApiFailureResponse, Boolean]] = checkRelationshipsService
    .checkForRelationship(
      arn,
      service,
      clientIdType,
      clientId,
      None
    )
    .map {
      case CheckRelationshipFound => Left(ApiFailureResponse.AlreadyAuthorised)
      case CheckRelationshipNotFound(_) => Right(false)
      case CheckRelationshipInvalidRequest => Left(ApiFailureResponse.ApiInternalServerError(""))
    }
    .flatMap {
      case Right(false) if ItsaServices.contains(service) =>
        partialAuthRepository
          .findActive(
            service,
            Nino(clientId),
            arn
          )
          .map {
            case Some(_) => Left(ApiFailureResponse.AlreadyAuthorised)
            case None => Right(false)
          }
      case result => Future.successful(result)
    }

  private def currentTime() = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime

  private val invitationExpiryDuration = appConfig.invitationExpiringDuration

  private val multiAgentServices = Seq(HMRCMTDIT, HMRCMTDITSUPP)

  private val ItsaServices = Seq(HMRCMTDIT, HMRCMTDITSUPP)

  private val multiAgentServicesOtherService: Map[String, String] = Map(HMRCMTDIT -> HMRCMTDITSUPP, HMRCMTDITSUPP -> HMRCMTDIT)

}
