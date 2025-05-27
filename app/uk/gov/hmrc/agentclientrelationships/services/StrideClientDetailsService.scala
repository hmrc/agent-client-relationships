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

package uk.gov.hmrc.agentclientrelationships.services

import cats.data.EitherT
import cats.implicits._
import uk.gov.hmrc.agentclientrelationships.connectors.AgentAssuranceConnector
import uk.gov.hmrc.agentclientrelationships.connectors.AgentFiRelationshipConnector
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.agentclientrelationships.model.stride._
import uk.gov.hmrc.agentclientrelationships.model.ActiveRelationship
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.RelationshipFailureResponse
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDIT
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentmtdidentifiers.model.Service.MtdIt
import uk.gov.hmrc.agentmtdidentifiers.model.Service.PersonalIncomeRecord
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.domain.TaxIdentifier
import play.api.mvc.RequestHeader

import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

@Singleton
class StrideClientDetailsService @Inject() (
  invitationsRepository: InvitationsRepository,
  agentAssuranceService: AgentAssuranceService,
  findRelationshipsService: FindRelationshipsService,
  agentFiRelationshipConnector: AgentFiRelationshipConnector,
  partialAuthRepository: PartialAuthRepository,
  clientDetailsService: ClientDetailsService,
  validationService: ValidationService
)(implicit ec: ExecutionContext) {

  def getClientDetailsWithChecks(
    ek: EnrolmentKey
  )(implicit request: RequestHeader): Future[Option[ClientDetailsStrideResponse]] = {

    val clientId: String = ek.oneTaxIdentifier().value
    val services =
      if (ek.service == HMRCMTDIT)
        Seq(ek.service, HMRCMTDITSUPP)
      else
        Seq(ek.service)

    for {
      invitations <- getNonSuspendedInvitations(clientId, services)
      clientName <- getClientName(
        invitations,
        ek.service,
        clientId
      )
      mActiveRel <- findActiveRelationship(ek.oneTaxIdentifier(), Service.forId(ek.service))
      mMainAgent <- findAgentDetails(mActiveRel)
      clientDetails = clientName.map(name =>
        ClientDetailsStrideResponse(
          name,
          invitations,
          mMainAgent
        )
      )
    } yield clientDetails
  }

  def findAllActiveRelationship(
    clientsRelationshipsRequest: ClientsRelationshipsRequest
  )(implicit request: RequestHeader): Future[Either[RelationshipFailureResponse, Seq[ActiveClientRelationship]]] =
    clientsRelationshipsRequest.clientRelationshipRequest
      .map { crr: ClientRelationshipRequest =>
        for {
          taxIdentifier <- EitherT.fromEither[Future](
            validationService.validateForTaxIdentifier(crr.clientIdType, crr.clientId)
          )
          activeRelationships <- EitherT(findAllActiveRelationshipForTaxId(taxIdentifier))
          clientAgentsData <- findAgentClientDataForRelationships(taxIdentifier, activeRelationships)
        } yield clientAgentsData._2
          .map(r =>
            ActiveClientRelationship(
              clientId = crr.clientId,
              clientName = clientAgentsData._1.name,
              arn = r.arn.value,
              agentName = r.agentName,
              service = r.service
            )
          )
      }
      .sequence
      .map(_.flatten)
      .value

  private def findAgentClientDataForRelationships(
    taxIdentifier: TaxIdentifier,
    activeRelationships: Seq[ClientRelationship]
  )(implicit
    request: RequestHeader
  ): EitherT[
    Future,
    RelationshipFailureResponse,
    (ClientDetailsResponse, Seq[ClientRelationshipWithAgentName])
  ] =
    for {
      activeRelationshipsWithAgentName <- findAgentNameForActiveRelationships(
        activeRelationships,
        taxIdentifier: TaxIdentifier
      )
      clientDetails <- EitherT(findClientDetailsByTaxIdentifier(taxIdentifier))

    } yield (clientDetails, activeRelationshipsWithAgentName)

  private def findAllActiveRelationshipForTaxId(
    taxIdentifier: TaxIdentifier
  )(implicit request: RequestHeader): Future[Either[RelationshipFailureResponse, Seq[ClientRelationship]]] =
    (
      taxIdentifier match {
        case Nino(_) =>
          (
            for {
              itsaActiveRelationships <- EitherT(
                findRelationshipsService.getAllActiveItsaRelationshipForClient(
                  nino = Nino(taxIdentifier.value),
                  activeOnly = true
                )
              )

              irvActiveRelationship <- EitherT(
                agentFiRelationshipConnector.findIrvActiveRelationshipForClient(taxIdentifier.value)
              ).map(Seq(_)).leftFlatMap(recoverNotFoundRelationship)

              partialAuthAuthorisations <- EitherT.right[RelationshipFailureResponse](
                getPartialAuthAuthorisations(taxIdentifier)
              )

            } yield itsaActiveRelationships ++ irvActiveRelationship ++ partialAuthAuthorisations
          ).value

        case _ => findRelationshipsService.getAllRelationshipsForClient(taxIdentifier = taxIdentifier, activeOnly = true)

      }
    ).map(_.map(_.filter(_.isActive))) // additional filtering for IF

  private def recoverNotFoundRelationship(relationshipFailureResponse: RelationshipFailureResponse)(implicit
    ec: ExecutionContext
  ): EitherT[
    Future,
    RelationshipFailureResponse,
    Seq[ClientRelationship]
  ] =
    relationshipFailureResponse match {
      case RelationshipFailureResponse.RelationshipNotFound | RelationshipFailureResponse.RelationshipSuspended =>
        EitherT.rightT[Future, RelationshipFailureResponse](Seq.empty[ClientRelationship])
      case otherError => EitherT.leftT[Future, Seq[ClientRelationship]](otherError)

    }

  private def getPartialAuthAuthorisations(taxIdentifier: TaxIdentifier): Future[Seq[ClientRelationship]] =
    taxIdentifier match {
      case nino @ Nino(_) =>
        partialAuthRepository
          .findActiveByNino(nino)
          .map(
            _.map(pa =>
              ClientRelationship(
                arn = Arn(pa.arn),
                dateTo = None,
                dateFrom = Some(pa.lastUpdated.atZone(ZoneId.of("UTC")).toLocalDate),
                authProfile = None,
                isActive = true,
                relationshipSource = RelationshipSource.AcrPartialAuthRepo,
                service = Service.findById(pa.service)
              )
            )
          )
      case _ => Future.successful(Seq.empty[ClientRelationship])
    }

  private def findAgentNameForActiveRelationships(
    activeRelationships: Seq[ClientRelationship],
    taxIdentifier: TaxIdentifier
  )(implicit
    request: RequestHeader
  ): EitherT[
    Future,
    RelationshipFailureResponse,
    Seq[ClientRelationshipWithAgentName]
  ] =
    activeRelationships.map { ar =>
      for {
        agentDetails <- EitherT.right(agentAssuranceService.getAgentRecord(ar.arn))
        service <- EitherT(
          validationService.validateAuthProfileToService(
            taxIdentifier,
            ar.authProfile,
            ar.relationshipSource,
            ar.service
          )
        )
      } yield ClientRelationshipWithAgentName(
        ar.arn,
        agentDetails.agencyDetails.agencyName,
        service.id,
        ar.dateTo,
        ar.dateFrom
      )
    }.sequence

  private def findClientDetailsByTaxIdentifier(taxIdentifier: TaxIdentifier)(implicit
    request: RequestHeader
  ): Future[Either[RelationshipFailureResponse, ClientDetailsResponse]] = clientDetailsService
    .findClientDetailsByTaxIdentifier(taxIdentifier)
    .map(
      _.left
        .map {
          case ClientDetailsNotFound => RelationshipFailureResponse.ClientDetailsNotFound
          case ErrorRetrievingClientDetails(status, msg) => RelationshipFailureResponse.ErrorRetrievingClientDetails(status, msg)
        }
    )

  private def getNonSuspendedInvitations(
    clientId: String,
    services: Seq[String]
  )(implicit request: RequestHeader): Future[Seq[InvitationWithAgentName]] =
    for {
      invitations <- invitationsRepository.findAllPendingForSuppliedClient(clientId, services)
      nonSuspended <- Future.sequence(
        invitations.map(i =>
          agentAssuranceService
            .getNonSuspendedAgentRecord(Arn(i.arn))
            .map {
              case Some(agentRecord) => Some(InvitationWithAgentName.fromInvitationAndAgentRecord(i, agentRecord))
              case None => None
            }
        )
      )
    } yield nonSuspended.flatten

  private def findActiveRelationship(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit request: RequestHeader): Future[Option[ActiveMainAgentRelationship]] =
    (taxIdentifier, service) match {
      case (_: Nino, MtdIt) =>
        for {
          partialAuth <- partialAuthRepository.findMainAgent(taxIdentifier.value)
          mRel <-
            if (partialAuth.isEmpty) {
              findRelationshipsService.getItsaRelationshipForClient(Nino(taxIdentifier.value), service)
            }
            else
              Future.successful(
                partialAuth.map(pa =>
                  ActiveRelationship(
                    Arn(pa.arn),
                    None,
                    None
                  )
                )
              )
          result = mRel.map(r => ActiveMainAgentRelationship(r.arn.value, service.id))
        } yield result

      case (_: Nino, PersonalIncomeRecord) =>
        for {
          irv <- agentFiRelationshipConnector.findIrvRelationshipForClient(taxIdentifier.value)
          activeRel = irv.map(r => ActiveMainAgentRelationship(r.arn.value, service.id))
        } yield activeRel

      case _ =>
        for {
          mRel <- findRelationshipsService.getActiveRelationshipsForClient(taxIdentifier, service)
          mMainRel = mRel.map(rel => ActiveMainAgentRelationship(rel.arn.value, service.id))
        } yield mMainRel
    }

  private def findAgentDetails(
    mActiveRelationship: Option[ActiveMainAgentRelationship]
  )(implicit request: RequestHeader): Future[Option[ActiveMainAgent]] =
    mActiveRelationship.fold[Future[Option[ActiveMainAgent]]](Future.successful(None)) { activeRelationship =>
      agentAssuranceService
        .getAgentRecord(Arn(activeRelationship.arn))
        .map(ar =>
          Some(
            ActiveMainAgent(
              ar.agencyDetails.agencyName,
              activeRelationship.arn,
              activeRelationship.service
            )
          )
        )
    }

  private def getClientName(
    invitations: Seq[InvitationWithAgentName],
    service: String,
    clientId: String
  )(implicit request: RequestHeader): Future[Option[String]] =
    for {
      fromInv <- Future.successful(invitations.headOption.map(i => i.clientName))
      result <-
        if (fromInv.isEmpty)
          clientDetailsService.findClientDetails(service, clientId).map(_.toOption).map(_.map(_.name))
        else
          Future.successful(fromInv)
    } yield result

  case class ActiveMainAgentRelationship(
    arn: String,
    service: String
  )

}
