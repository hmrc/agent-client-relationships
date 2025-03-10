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
import uk.gov.hmrc.agentclientrelationships.connectors.{AgentAssuranceConnector, AgentFiRelationshipConnector}
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Service}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.HeaderCarrier

import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ClientTaxAgentsDataService @Inject() (
  invitationsRepository: InvitationsRepository,
  agentAssuranceConnector: AgentAssuranceConnector,
  invitationLinkService: InvitationLinkService,
  agentFiRelationshipConnector: AgentFiRelationshipConnector,
  findRelationshipsService: FindRelationshipsService
)(implicit ec: ExecutionContext) {

  def getClientTaxAgentsData(
    identifiers: Map[Service, TaxIdentifier]
  )(implicit hc: HeaderCarrier): Future[Either[RelationshipFailureResponse, ClientTaxAgentsData]] =
    (for {
      agentsInvitations    <- getAgentsInvitations(identifiers)
      agentsAuthorisations <- getAgentsAuthorisations(identifiers)
    } yield ClientTaxAgentsData(
      agentsInvitations = AgentsInvitationsResponse(agentsInvitations),
      agentsAuthorisations = AgentsAuthorisationsResponse(agentsAuthorisations),
      authorisationEvents = AuthorisationEventsResponse(Seq.empty[AuthorisationEvent])
    )).value

  private def getAgentsAuthorisations(identifiers: Map[Service, TaxIdentifier])(implicit
    hc: HeaderCarrier
  ): EitherT[Future, RelationshipFailureResponse, Seq[AgentAuthorisations]] =
    for {
      // TODO WG - make sure only active
      relationshipsForService <- getAllAuthorisationsForAllServices(identifiers).map(_.filter(_.isActive))
      agentsInvitations       <- getAgentDateForRelationships(relationshipsForService)
    } yield agentsInvitations

  private def getAllAuthorisationsForAllServices(
    identifiers: Map[Service, TaxIdentifier]
  )(implicit hc: HeaderCarrier): EitherT[Future, RelationshipFailureResponse, Seq[ClientRelationshipForTaxId]] =
    identifiers
      .map { case (service, taxIdentifier) =>
        for {
          relationshipsWithAuthProfile <- findAllRelationshipForTaxId(taxIdentifier, service)
        } yield relationshipsWithAuthProfile
      }
      .toSeq
      .sequence
      .map(_.flatten)

  private def findAllRelationshipForTaxId(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit hc: HeaderCarrier): EitherT[Future, RelationshipFailureResponse, Seq[ClientRelationshipForTaxId]] =
    taxIdentifier match {
      case Nino(_) =>
        for {
          irvActiveRelationship <-
            EitherT(agentFiRelationshipConnector.findIrvActiveRelationshipForClient(taxIdentifier.value))
          irvInactiveRelationship <-
            EitherT(agentFiRelationshipConnector.findIrvInactiveRelationshipForClient)
          irvAllRelationship = irvActiveRelationship +: irvInactiveRelationship
        } yield irvAllRelationship.map(r =>
          ClientRelationshipForTaxId(r.arn, service, taxIdentifier.value, r.dateTo, r.dateFrom, r.isActive)
        )

      case _ =>
        for {
          relationshipsWithAuthProfile <-
            EitherT(
              findRelationshipsService.getAllRelationshipsForClient(taxIdentifier = taxIdentifier, activeOnly = false)
            )
        } yield relationshipsWithAuthProfile.map { cr =>
          val adjustedService = cr.authProfile match {
            case Some(authProfile) => if (authProfile == "ITSAS001") Service.MtdItSupp else service
            case None              => service
          }

          ClientRelationshipForTaxId(cr.arn, adjustedService, taxIdentifier.value, cr.dateTo, cr.dateFrom, cr.isActive)
        }
    }

  private def getAgentDateForRelationships(relationshipsWithAuthProfile: Seq[ClientRelationshipForTaxId])(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, RelationshipFailureResponse, Seq[AgentAuthorisations]] = {
    val result = relationshipsWithAuthProfile
      .groupBy(_.arn)
      .map { case (arn, relationships) =>
        for {
          agentDetails <- EitherT(findAgentDetailsByArn(arn))
          normalizedName = invitationLinkService.normaliseAgentName(agentDetails.agencyDetails.agencyName)
          authorisations <- getAuthorisations(relationships, agentDetails.agencyDetails.agencyName)
        } yield AgentAuthorisations(
          agentName = agentDetails.agencyDetails.agencyName,
          arn = arn.value,
          authorisations = authorisations
        )
      }
      .toSeq
      .map(x => filterOutSuspendedAgent(x))
      .sequence
      .map(_.flatten)
      .map(partitionMap)

    EitherT(result)

  }

  private def getAuthorisations(relationships: Seq[ClientRelationshipForTaxId], agentName: String)(implicit
    ec: ExecutionContext
  ): EitherT[Future, RelationshipFailureResponse, Seq[Authorisation]] =
    relationships.map { ar =>
      for {
        dateFrom <- EitherT.fromOption[Future](
                      ar.dateFrom,
                      RelationshipFailureResponse.RelationshipStartDateMissing: RelationshipFailureResponse
                    )
      } yield Authorisation(
        uid = UUID.randomUUID().toString,
        service = ar.service.id,
        clientId = ar.clientId,
        date = dateFrom,
        arn = ar.arn.value,
        agentName = agentName
      )
    }.sequence

  private def getAgentsInvitations(identifiers: Map[Service, TaxIdentifier])(implicit
    hc: HeaderCarrier
  ): EitherT[Future, RelationshipFailureResponse, Seq[AgentInvitations]] =
    for {
      invitations       <- getAllPendingInvitationsForAllServices(identifiers)
      agentsInvitations <- getAgentDataForInvitation(invitations)
    } yield agentsInvitations

  private def getAllPendingInvitationsForAllServices(
    identifiers: Map[Service, TaxIdentifier]
  ): EitherT[Future, RelationshipFailureResponse, Seq[Invitation]] =
    identifiers
      .map { case (service, taxIdentifier) =>
        val services = if (service.id == HMRCMTDIT) Seq(service.id, HMRCMTDITSUPP) else Seq(service.id)
        for {
          invitations <- EitherT.right[RelationshipFailureResponse](
                           invitationsRepository.findAllPendingForClient(taxIdentifier.value, services)
                         )
        } yield invitations
      }
      .toSeq
      .sequence
      .map(_.flatten)

  private def getAgentDataForInvitation(invitations: Seq[Invitation])(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, RelationshipFailureResponse, Seq[AgentInvitations]] = {
    val result = invitations
      .groupBy(_.arn)
      .map { case (arn, invitations) =>
        for {
          agentDetails <- EitherT(findAgentDetailsByArn(Arn(arn)))
          normalizedName = invitationLinkService.normaliseAgentName(agentDetails.agencyDetails.agencyName)
          agentReference <- EitherT.right[RelationshipFailureResponse](
                              invitationLinkService.getAgentReferenceRecordByArn(Arn(arn), normalizedName)
                            )
        } yield (AgentInvitations(agentReference.uid, agentDetails.agencyDetails.agencyName, invitations))
      }
      .toSeq
      .map(x => filterOutSuspendedAgent(x))
      .sequence
      .map(_.flatten)
      .map(partitionMap)

    EitherT(result)

  }

  private def findAgentDetailsByArn(
    arn: Arn
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[RelationshipFailureResponse, AgentDetailsDesResponse]] =
    agentAssuranceConnector
      .getAgentRecordWithChecks(arn)
      .map { agentRecord =>
        if (agentIsSuspended(agentRecord))
          Left(RelationshipFailureResponse.AgentSuspended)
        else
          Right(agentRecord)
      }
      .recover { case ex: Throwable =>
        Left(RelationshipFailureResponse.ErrorRetrievingAgentDetails(ex.getMessage))
      }

  private def agentIsSuspended(agentRecord: AgentDetailsDesResponse): Boolean =
    agentRecord.suspensionDetails.exists(_.suspensionStatus)

  private def filterOutSuspendedAgent[A](
    myEitherT: EitherT[Future, RelationshipFailureResponse, A]
  ): Future[Option[Either[RelationshipFailureResponse, A]]] =
    myEitherT.value.map {
      case r @ Right(_)                                                           => Some(r)
      case l @ Left(error) if error != RelationshipFailureResponse.AgentSuspended => Some(l)
      case Left(_)                                                                => None

    }

  private def partitionMap[A](
    seq: Seq[Either[RelationshipFailureResponse, A]]
  ): Either[RelationshipFailureResponse, Seq[A]] = {
    val (lefts, rights) = seq.partitionMap(identity)
    lefts.headOption.toLeft(rights)
  }

}
