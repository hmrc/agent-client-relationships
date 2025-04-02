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

import cats.data.{EitherT, OptionT}
import cats.implicits._
import uk.gov.hmrc.agentclientrelationships.connectors.{AgentAssuranceConnector, AgentFiRelationshipConnector, IFConnector}
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.agentclientrelationships.model.stride.ClientRelationship
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Service}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{LocalDate, ZoneId}
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ClientTaxAgentsDataService @Inject() (
  invitationsRepository: InvitationsRepository,
  agentAssuranceConnector: AgentAssuranceConnector,
  invitationLinkService: InvitationLinkService,
  agentFiRelationshipConnector: AgentFiRelationshipConnector,
  findRelationshipsService: FindRelationshipsService,
  partialAuthRepository: PartialAuthRepository,
  ifConnector: IFConnector
)(implicit ec: ExecutionContext) {

  def getClientTaxAgentsData(
    identifiers: Map[Service, TaxIdentifier]
  )(implicit hc: HeaderCarrier): Future[Either[RelationshipFailureResponse, ClientTaxAgentsData]] =
    (for {
      allInvitations    <- getAllInvitationsForAllServices(identifiers)
      allAuthorisations <- getAllAuthorisationsForAllServices(identifiers, allInvitations)

      agentsAuthorisations <- getAgentDateForRelationships(allAuthorisations)
      agentsInvitations    <- getAgentDataForInvitation(allInvitations)
      authorisationEvents  <- getAuthorisationEvent(allInvitations, allAuthorisations)

    } yield ClientTaxAgentsData(
      agentsInvitations = AgentsInvitationsResponse(agentsInvitations),
      agentsAuthorisations = AgentsAuthorisationsResponse(agentsAuthorisations),
      authorisationEvents = AuthorisationEventsResponse(authorisationEvents)
    )).value

  private def getAuthorisationEvent(
    invitations: Seq[Invitation],
    clientAuthorisations: Seq[ClientAuthorisationForTaxId]
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, RelationshipFailureResponse, Seq[AuthorisationEvent]] = {

    case class AuthorisationEventWithoutAgentName(
      arn: String,
      service: String,
      eventDate: LocalDate,
      eventType: InvitationStatus
    )

    val invitationEvents = invitations
      .filter(invitation => Seq(Expired, Rejected, Cancelled).contains(invitation.status))
      .map(invitation =>
        AuthorisationEventWithoutAgentName(
          invitation.arn,
          invitation.service,
          invitation.lastUpdated.atZone(ZoneId.of("UTC")).toLocalDate,
          invitation.status
        )
      )

    // PartialAuth are reported as Accepted on event history tab
    val authorisationsAcceptedEvents = clientAuthorisations.flatMap { authorisation =>
      authorisation.dateFrom.map { dateFrom =>
        AuthorisationEventWithoutAgentName(
          arn = authorisation.arn.value,
          service = authorisation.service.id,
          eventDate = dateFrom,
          eventType = Accepted
        )
      }
    }

    val authorisationsDeAuthorisedEvents = clientAuthorisations
      .filterNot(_.isActive)
      .flatMap { authorisation =>
        authorisation.dateTo.map { dateTo =>
          AuthorisationEventWithoutAgentName(
            arn = authorisation.arn.value,
            service = authorisation.service.id,
            eventDate = dateTo,
            eventType = DeAuthorised
          )
        }
      }

    val authorisationEventWithoutAgentName =
      invitationEvents ++ authorisationsAcceptedEvents ++ authorisationsDeAuthorisedEvents

    val result = authorisationEventWithoutAgentName
      .map { authorisation =>
        for {
          agentDetails <- EitherT(findAgentDetailsByArn(Arn(authorisation.arn)))
        } yield (AuthorisationEvent(
          agentDetails.agencyDetails.agencyName,
          authorisation.service,
          authorisation.eventDate,
          authorisation.eventType
        ))
      }
      .map(x => filterOutSuspendedAgent(x))
      .sequence
      .map(_.flatten)
      .map(partitionMap)

    EitherT(result)
  }

  private def getAllAuthorisationsForAllServices(
    identifiers: Map[Service, TaxIdentifier],
    allInvitations: Seq[Invitation]
  )(implicit hc: HeaderCarrier): EitherT[Future, RelationshipFailureResponse, Seq[ClientAuthorisationForTaxId]] = {
    val authorisations = identifiers
      .map { case (service, taxIdentifier) =>
        for {
          relationshipsWithAuthProfile <- findAllRelationshipForTaxId(taxIdentifier, service)
        } yield relationshipsWithAuthProfile
      }
      .toSeq
      .sequence
      .map(_.flatten)

    val activePartialAuth = allInvitations
      .filter(_.status == PartialAuth)
      .map(pa =>
        ClientAuthorisationForTaxId(
          arn = Arn(pa.arn),
          dateTo = None,
          dateFrom = Some(pa.lastUpdated.atZone(ZoneId.of("UTC")).toLocalDate),
          isActive = true,
          service = Service.forId(pa.service),
          clientId = pa.clientId
        )
      )

    authorisations.map(_ ++ activePartialAuth)
  }

  private def findAllRelationshipForTaxId(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit hc: HeaderCarrier): EitherT[Future, RelationshipFailureResponse, Seq[ClientAuthorisationForTaxId]] =
    taxIdentifier match {
      case Nino(_) =>
        for {
          irvActiveRelationship <-
            EitherT(agentFiRelationshipConnector.findIrvActiveRelationshipForClient(taxIdentifier.value))
              .map(Seq(_))
              .leftFlatMap(recoverNotFoundRelationship)
          irvInactiveRelationship <-
            EitherT(agentFiRelationshipConnector.findIrvInactiveRelationshipForClient)
          irvAllRelationship = irvActiveRelationship ++ irvInactiveRelationship
        } yield irvAllRelationship.map(r =>
          ClientAuthorisationForTaxId(r.arn, service, taxIdentifier.value, r.dateTo, r.dateFrom, r.isActive)
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

          ClientAuthorisationForTaxId(cr.arn, adjustedService, taxIdentifier.value, cr.dateTo, cr.dateFrom, cr.isActive)
        }
    }

  private def recoverNotFoundRelationship(relationshipFailureResponse: RelationshipFailureResponse)(implicit
    ec: ExecutionContext
  ): EitherT[Future, RelationshipFailureResponse, Seq[ClientRelationship]] =
    relationshipFailureResponse match {
      case RelationshipFailureResponse.RelationshipNotFound | RelationshipFailureResponse.RelationshipSuspended =>
        EitherT.rightT[Future, RelationshipFailureResponse](Seq.empty[ClientRelationship])
      case otherError =>
        EitherT.leftT[Future, Seq[ClientRelationship]](otherError)

    }

  private def getAgentDateForRelationships(
    relationshipsWithAuthProfile: Seq[ClientAuthorisationForTaxId]
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, RelationshipFailureResponse, Seq[AgentAuthorisations]] = {
    val result = relationshipsWithAuthProfile
      .filter(_.isActive)
      .groupBy(_.arn)
      .map { case (arn, relationships) =>
        for {
          agentDetails   <- EitherT(findAgentDetailsByArn(arn))
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

  private def getAuthorisations(relationships: Seq[ClientAuthorisationForTaxId], agentName: String)(implicit
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

  private def getAllInvitationsForAllServices(
    identifiers: Map[Service, TaxIdentifier]
  )(implicit hc: HeaderCarrier): EitherT[Future, RelationshipFailureResponse, Seq[Invitation]] =
    identifiers
      .map { case (service, taxIdentifier) =>
        val services = if (service.id == HMRCMTDIT) Seq(service.id, HMRCMTDITSUPP) else Seq(service.id)
        for {
          invitations <- EitherT.right[RelationshipFailureResponse](
                           invitationsRepository.findAllForClient(taxIdentifier.value, services)
                         )
          partialAuthInvitations <- EitherT.right[RelationshipFailureResponse](getPartialAuthInvitations(taxIdentifier))
        } yield invitations ++ partialAuthInvitations.map(_.asInvitation)
      }
      .toSeq
      .sequence
      .map(_.flatten)

  private def getPartialAuthInvitations(
    taxIdentifier: TaxIdentifier
  )(implicit hc: HeaderCarrier): Future[Seq[PartialAuthRelationship]] =
    taxIdentifier match {
      case mtdItId @ MtdItId(_) =>
        (for {
          nino <- OptionT(ifConnector.getNinoFor(mtdItId))
          c    <- OptionT.liftF(partialAuthRepository.findAllByNino(nino))
        } yield c).value.map(_.getOrElse(Seq.empty[PartialAuthRelationship]))
      case _ => Future.successful(Seq.empty[PartialAuthRelationship])
    }

  private def getAgentDataForInvitation(invitations: Seq[Invitation])(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, RelationshipFailureResponse, Seq[AgentInvitations]] = {
    val result = invitations
      .filter(_.status == Pending)
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
