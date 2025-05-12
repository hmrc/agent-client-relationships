/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.services

import cats.data.EitherT
import org.mongodb.scala.MongoException
import play.api.Logging
import play.api.mvc.{AnyContent, Request}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.{AgentAssuranceConnector, IfOrHipConnector}
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.{ClientDetailsFailureResponse, ClientDetailsNotFound, ClientDetailsResponse, ErrorRetrievingClientDetails}
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse.DuplicateAuthorisationRequest
import uk.gov.hmrc.agentclientrelationships.model.invitation.{ApiAuthorisationRequestInfo, ApiCreateInvitationRequest, InvitationFailureResponse}
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.{AgencyDetails, AgentDetailsDesResponse, AgentReferenceRecord}
import uk.gov.hmrc.agentclientrelationships.model.{Invitation, Pending}
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.ClientIdentifier.ClientId
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP, MtdIt, MtdItSupp}
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.agentclientrelationships.util.RequestSupport._

import java.time.{Instant, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc.RequestHeader

@Singleton
class ApiService @Inject() (
  invitationsRepository: InvitationsRepository,
  ifOrHipConnector: IfOrHipConnector,
  agentAssuranceConnector: AgentAssuranceConnector,
  appConfig: AppConfig,
  clientDetailsService: ClientDetailsService,
  knowFactsCheckService: KnowFactsCheckService,
  invitationLinkService: InvitationLinkService,
  checkRelationshipsService: CheckRelationshipsOrchestratorService,
  partialAuthRepository: PartialAuthRepository
)(implicit ec: ExecutionContext)
    extends Logging {

  def createInvitation(
    arn: Arn,
    apiCreateInvitationInputData: ApiCreateInvitationRequest,
    supportedServices: Seq[Service]
  )(implicit hc: HeaderCarrier, request: Request[Any]): Future[Either[InvitationFailureResponse, Invitation]] = {
    val invitationT = for {

      // validate inputData
      suppliedClientId <-
        EitherT.fromEither[Future](apiCreateInvitationInputData.getSuppliedClientId(supportedServices))
      service  <- EitherT.fromEither[Future](apiCreateInvitationInputData.getService(supportedServices))
      clientId <- EitherT(getClientId(suppliedClientId, service))

      _           <- EitherT(checkPendingInvitation(arn, service.id, suppliedClientId.value))
      agentRecord <- EitherT(getAgentDetailsByArn(arn))

      clientDetails <- EitherT(clientDetailsService.findClientDetails(service.id, suppliedClientId.value))
                         .leftMap[InvitationFailureResponse] {
                           case ClientDetailsNotFound => InvitationFailureResponse.ClientRegistrationNotFound
                           case ErrorRetrievingClientDetails(status, message) =>
                             InvitationFailureResponse.ErrorRetrievingClientDetails(status, message)
                         }
                         .flatMap[InvitationFailureResponse, ClientDetailsResponse] { clientDetailsResponse =>
                           if (clientDetailsResponse.status.nonEmpty)
                             EitherT.leftT[Future, ClientDetailsResponse](InvitationFailureResponse.VatClientInsolvent)
                           else EitherT.rightT[Future, InvitationFailureResponse](clientDetailsResponse)
                         }

      _ <- EitherT.fromEither[Future](knowFactsCheckService.checkKnowFacts(apiCreateInvitationInputData, clientDetails))

      _ <- EitherT(getExistingRelationship(arn, service.id, suppliedClientId.enrolmentId, suppliedClientId.value))

      // create invitation
      invitation <- EitherT(
                      create(
                        arn = arn,
                        service = service,
                        clientId = clientId,
                        suppliedClientId = suppliedClientId,
                        clientName = clientDetails.name,
                        clientType = None,
                        agentDetails = agentRecord.agencyDetails
                      )
                    )
    } yield invitation
    invitationT.value
  }

  def findInvitationForAgent(arn: Arn, invitationId: String, supportedServices: Seq[Service])(implicit
    request: RequestHeader
  ): Future[Either[InvitationFailureResponse, ApiAuthorisationRequestInfo]] =
    (for {

      _ <- EitherT.fromEither[Future](
             if (InvitationId.isValid(invitationId)) Right(invitationId)
             else Left(InvitationFailureResponse.InvitationNotFound)
           )

      agentRecord <- EitherT(getAgentDetailsByArn(arn))

      newNormaliseAgentName = invitationLinkService.normaliseAgentName(agentRecord.agencyDetails.agencyName)

      agentReferenceRecord <-
        EitherT.right[InvitationFailureResponse](
          invitationLinkService.getAgentReferenceRecordByArn(arn = arn, newNormaliseAgentName = newNormaliseAgentName)
        )

      invitation <- EitherT(findInvitation(invitationId, arn, supportedServices))

    } yield ApiAuthorisationRequestInfo.createApiAuthorisationRequestInfo(
      invitation,
      agentReferenceRecord.uid,
      newNormaliseAgentName
    )).value

  private def getClientId(suppliedClientId: ClientId, service: Service)(implicit
    hc: HeaderCarrier
  ): Future[Either[InvitationFailureResponse, ClientId]] = (service, suppliedClientId.typeId) match {
    case (MtdIt | MtdItSupp, NinoType.id) =>
      ifOrHipConnector
        .getMtdIdFor(Nino(suppliedClientId.value))
        .map(
          _.fold[Either[InvitationFailureResponse, ClientId]](Right(suppliedClientId))(mdtId =>
            Right(ClientIdentifier(mdtId))
          )
        )
    case _ => Future successful Right(suppliedClientId)
  }

  def findInvitation(
    invitationId: String,
    arn: Arn,
    supportedServices: Seq[Service]
  ): Future[Either[InvitationFailureResponse, Invitation]] =
    invitationsRepository
      .findOneById(invitationId)
      .map {
        _.fold[Either[InvitationFailureResponse, Invitation]](Left(InvitationFailureResponse.InvitationNotFound))(
          invitation =>
            if (invitation.arn == arn.value)
              if (supportedServices.map(_.id).contains(invitation.service)) Right(invitation)
              else Left(InvitationFailureResponse.UnsupportedService)
            else Left(InvitationFailureResponse.NoPermissionOnAgency)
        )
      }

  private def create(
    arn: Arn,
    service: Service,
    clientId: ClientId,
    suppliedClientId: ClientId,
    clientName: String,
    clientType: Option[String],
    agentDetails: AgencyDetails
  ): Future[Either[InvitationFailureResponse, Invitation]] = {
    val expiryDate = currentTime().plusSeconds(invitationExpiryDuration.toSeconds).toLocalDate
    (for {
      invitation <-
        invitationsRepository.create(
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
      case e: MongoException if e.getMessage.contains("E11000 duplicate key error") =>
        Left(DuplicateAuthorisationRequest(None))
    }
  }

  private def getAgentDetailsByArn(arn: Arn)(implicit
    hc: HeaderCarrier
  ): Future[Either[InvitationFailureResponse, AgentDetailsDesResponse]] =
    agentAssuranceConnector
      .getAgentRecordWithChecks(arn)
      .map { agentRecord =>
        if (agentIsSuspended(agentRecord)) Left(InvitationFailureResponse.AgentSuspended)
        else Right(agentRecord)
      }
      .recover { case ex: Throwable =>
        Left(InvitationFailureResponse.ErrorRetrievingAgentDetails(ex.getMessage))
      }

  private def agentIsSuspended(agentRecord: AgentDetailsDesResponse): Boolean =
    agentRecord.suspensionDetails.exists(_.suspensionStatus)

  private def checkPendingInvitation(
    arn: Arn,
    service: String,
    suppliedClientId: String
  ): Future[Either[InvitationFailureResponse, Boolean]] =
    (for {
      _ <- EitherT(getPendingInvitation(arn, service, suppliedClientId))
      _ <- if (multiAgentServices.contains(service))
             EitherT(getPendingInvitation(arn, multiAgentServicesOtherService(service), suppliedClientId))
           else EitherT[Future, InvitationFailureResponse, Boolean](Future.successful(Right(false)))

    } yield false).value

  private def getPendingInvitation(
    arn: Arn,
    service: String,
    clientId: String
  ): Future[Either[InvitationFailureResponse, Boolean]] =
    invitationsRepository
      .findAllForAgent(arn.value, Seq(service), Seq(clientId), isSuppliedClientId = true)
      .map(_.filter(_.status == Pending))
      .map {
        case Nil => Right(false)
        case invitation +: _ =>
          Left(InvitationFailureResponse.DuplicateAuthorisationRequest(Some(invitation.invitationId)))
      }

  private def getExistingRelationship(arn: Arn, service: String, clientIdType: String, clientId: String)(implicit
    hc: HeaderCarrier,
    request: Request[Any]
  ): Future[Either[InvitationFailureResponse, Boolean]] = checkRelationshipsService
    .checkForRelationship(arn, service, clientIdType, clientId, None)
    .map {
      case CheckRelationshipFound =>
        Left(InvitationFailureResponse.DuplicateRelationshipRequest)
      case CheckRelationshipNotFound(_) =>
        Right(false)
      case CheckRelationshipInvalidRequest =>
        Left(InvitationFailureResponse.ErrorRetrievingRelationships)
    }
    .flatMap {
      case Right(false) if ItsaServices.contains(service) =>
        partialAuthRepository
          .findActive(service, Nino(clientId), arn)
          .map {
            case Some(_) =>
              Left(InvitationFailureResponse.DuplicateRelationshipRequest)
            case None => Right(false)
          }
      case result => Future.successful(result)
    }

  private def currentTime() = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime

  private val invitationExpiryDuration = appConfig.invitationExpiringDuration

  private val multiAgentServices = Seq(HMRCMTDIT, HMRCMTDITSUPP)

  private val ItsaServices = Seq(HMRCMTDIT, HMRCMTDITSUPP)

  private val multiAgentServicesOtherService: Map[String, String] =
    Map(HMRCMTDIT -> HMRCMTDITSUPP, HMRCMTDITSUPP -> HMRCMTDIT)

}
