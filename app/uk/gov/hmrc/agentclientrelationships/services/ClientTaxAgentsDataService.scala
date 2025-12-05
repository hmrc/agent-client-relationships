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
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.AgentFiRelationshipConnector
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.agentclientrelationships.model.stride.ClientRelationship
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.domain.TaxIdentifier

import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class ClientTaxAgentsDataService @Inject() (
  invitationsRepository: InvitationsRepository,
  agentAssuranceService: AgentAssuranceService,
  invitationLinkService: InvitationLinkService,
  agentFiRelationshipConnector: AgentFiRelationshipConnector,
  findRelationshipsService: FindRelationshipsService,
  partialAuthRepository: PartialAuthRepository
)(implicit
  ec: ExecutionContext,
  appConfig: AppConfig
) {

  val supportedServices = appConfig.supportedServices

  def getClientTaxAgentsData(
    authResponse: EnrolmentsWithNino
  )(implicit request: RequestHeader): Future[Either[RelationshipFailureResponse, ClientTaxAgentsData]] = {
    val clientIds: Seq[String] = authResponse.getIdentifierMap(supportedServices).values.toSeq.map(_.value)
    val identifiers: Map[String, TaxIdentifier] = authResponse.getIdentifierKeyMap(supportedServices)
    val nino: Option[String] = authResponse.getNino
    val allClientIds: Seq[String] = nino.fold(clientIds)(n => clientIds ++ Seq(n))
    (
      for {
        allInvitations <- getAllInvitationsForAllServices(allClientIds)
        allAuthorisations <- getAllAuthorisationsForAllServices(identifiers)
        activePartialAuth <- getActivePartialAuth(nino)
        allAuth = allAuthorisations ++ activePartialAuth
        agentsAuthorisations <- getAgentDateForRelationships(allAuth)
        agentsInvitations <- getAgentDataForInvitation(allInvitations)
        authorisationEvents <- getAuthorisationEvent(allInvitations, allAuth)
      } yield ClientTaxAgentsData(
        agentsInvitations = AgentsInvitationsResponse(agentsInvitations),
        agentsAuthorisations = AgentsAuthorisationsResponse(agentsAuthorisations),
        authorisationEvents = AuthorisationEventsResponse(authorisationEvents)
      )
    ).value
  }

  private def getAuthorisationEvent(
    invitations: Seq[Invitation],
    clientAuthorisations: Seq[ClientAuthorisationForTaxId]
  )(implicit
    request: RequestHeader,
    ec: ExecutionContext
  ): EitherT[
    Future,
    RelationshipFailureResponse,
    Seq[AuthorisationEvent]
  ] = {

    final case class AuthorisationEventWithoutAgentName(
      arn: String,
      service: String,
      eventDate: LocalDate,
      eventType: InvitationStatus
    )

    val invitationEvents = invitations
      .filter(invitation =>
        Seq(
          Expired,
          Rejected,
          Cancelled
        ).contains(invitation.status)
      )
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
      authorisation.dateFrom
        .map { dateFrom =>
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
        authorisation.dateTo
          .map { dateTo =>
            AuthorisationEventWithoutAgentName(
              arn = authorisation.arn.value,
              service = authorisation.service.id,
              eventDate = dateTo,
              eventType = DeAuthorised
            )
          }
      }

    val authorisationEventWithoutAgentName = invitationEvents ++ authorisationsAcceptedEvents ++ authorisationsDeAuthorisedEvents

    val result = authorisationEventWithoutAgentName
      .groupBy(_.arn)
      .map { case (arn, authorisations) =>
        for {
          agentDetails <- findAgentDetailsByArn(Arn(arn))
        } yield authorisations.map(authorisation =>
          AuthorisationEvent(
            agentDetails.agencyDetails.agencyName,
            authorisation.service,
            authorisation.eventDate,
            authorisation.eventType
          )
        )
      }
      .toSeq
      .map(x => filterOutSuspendedAgent(x))
      .sequence
      .map(_.flatten)
      .map(partitionMap)
      .map(_.map(_.flatten))

    EitherT(result)
  }

  private def getActivePartialAuth(nino: Option[String]): EitherT[
    Future,
    RelationshipFailureResponse,
    Seq[ClientAuthorisationForTaxId]
  ] =
    nino match {
      case Some(n) =>
        EitherT.right[RelationshipFailureResponse](
          partialAuthRepository
            .findByNino(NinoWithoutSuffix(n))
            .map(
              _.map(pa =>
                ClientAuthorisationForTaxId(
                  arn = Arn(pa.arn),
                  dateTo =
                    if (pa.active)
                      None
                    else
                      Some(pa.lastUpdated.atZone(ZoneId.of("UTC")).toLocalDate),
                  dateFrom = Some(pa.created.atZone(ZoneId.of("UTC")).toLocalDate),
                  isActive = pa.active,
                  service = Service.forId(pa.service),
                  clientId = pa.nino
                )
              )
            )
        )
      case _ => EitherT.right[RelationshipFailureResponse](Future.successful(Seq.empty[ClientAuthorisationForTaxId]))
    }

  private def getAllAuthorisationsForAllServices(identifiers: Map[String, TaxIdentifier])(implicit
    request: RequestHeader
  ): EitherT[
    Future,
    RelationshipFailureResponse,
    Seq[ClientAuthorisationForTaxId]
  ] = identifiers
    .map { case (service, taxIdentifier) =>
      for {
        relationshipsWithAuthProfile <- findAllRelationshipForTaxId(taxIdentifier, Service.forId(service))
      } yield relationshipsWithAuthProfile
    }
    .toSeq
    .sequence
    .map(_.flatten)

  private def findAllRelationshipForTaxId(
    taxIdentifier: TaxIdentifier,
    service: Service
  )(implicit
    request: RequestHeader
  ): EitherT[
    Future,
    RelationshipFailureResponse,
    Seq[ClientAuthorisationForTaxId]
  ] =
    taxIdentifier match {
      case NinoWithoutSuffix(_) =>
        for {
          irvActiveRelationship <- EitherT(
            agentFiRelationshipConnector.findIrvActiveRelationshipForClient(taxIdentifier.value)
          ).leftFlatMap(recoverNotFoundRelationship)
          irvInactiveRelationship <- EitherT(agentFiRelationshipConnector.findIrvInactiveRelationshipForClient)
            .leftFlatMap(recoverNotFoundRelationship)
          irvAllRelationship = irvActiveRelationship ++ irvInactiveRelationship
        } yield irvAllRelationship.map(r =>
          ClientAuthorisationForTaxId(
            r.arn,
            service,
            taxIdentifier.value,
            r.dateTo,
            r.dateFrom,
            r.isActive
          )
        )

      case _ =>
        for {
          relationshipsWithAuthProfile <- EitherT(
            findRelationshipsService.getAllRelationshipsForClient(taxIdentifier = taxIdentifier, activeOnly = false)
          )
        } yield relationshipsWithAuthProfile.map { cr =>
          val adjustedService =
            cr.authProfile match {
              case Some(authProfile) =>
                if (authProfile == "ITSAS001")
                  Service.MtdItSupp
                else
                  service
              case None => service
            }

          ClientAuthorisationForTaxId(
            cr.arn,
            adjustedService,
            taxIdentifier.value,
            cr.dateTo,
            cr.dateFrom,
            cr.isActive
          )
        }
    }

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

  private def getAgentDateForRelationships(relationshipsWithAuthProfile: Seq[ClientAuthorisationForTaxId])(implicit
    request: RequestHeader,
    ec: ExecutionContext
  ): EitherT[
    Future,
    RelationshipFailureResponse,
    Seq[AgentAuthorisations]
  ] = {
    val result = relationshipsWithAuthProfile
      .filter(_.isActive)
      .groupBy(_.arn)
      .map { case (arn, relationships) =>
        for {
          agentDetails <- findAgentDetailsByArn(arn)
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

  private def getAuthorisations(
    relationships: Seq[ClientAuthorisationForTaxId],
    agentName: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[
    Future,
    RelationshipFailureResponse,
    Seq[Authorisation]
  ] =
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

  private def getAllInvitationsForAllServices(clientIds: Seq[String]): EitherT[
    Future,
    RelationshipFailureResponse,
    Seq[Invitation]
  ] =
    for {
      invitations <- EitherT.right[RelationshipFailureResponse](invitationsRepository.findAllBy(clientIds = clientIds))
    } yield invitations

  private def getAgentDataForInvitation(invitations: Seq[Invitation])(implicit
    request: RequestHeader,
    ec: ExecutionContext
  ): EitherT[
    Future,
    RelationshipFailureResponse,
    Seq[AgentInvitations]
  ] = {
    val result = invitations
      .filter(_.status == Pending)
      .groupBy(_.arn)
      .map { case (arn, invitations) =>
        for {
          agentDetails <- findAgentDetailsByArn(Arn(arn))
          normalizedName = invitationLinkService.normaliseAgentName(agentDetails.agencyDetails.agencyName)
          agentReference <- EitherT.right[RelationshipFailureResponse](
            invitationLinkService.getAgentReferenceRecordByArn(Arn(arn), normalizedName)
          )
        } yield AgentInvitations(
          agentReference.uid,
          agentDetails.agencyDetails.agencyName,
          invitations
        )
      }
      .toSeq
      .map(x => filterOutSuspendedAgent(x))
      .sequence
      .map(_.flatten)
      .map(partitionMap)

    EitherT(result)

  }

  // TODO WG - that is called multiple time ofr same ARN
  private def findAgentDetailsByArn(arn: Arn)(implicit
    request: RequestHeader,
    ec: ExecutionContext
  ): EitherT[
    Future,
    RelationshipFailureResponse,
    AgentDetailsDesResponse
  ] = EitherT.fromOptionF(agentAssuranceService.getNonSuspendedAgentRecord(arn), RelationshipFailureResponse.AgentSuspended)

  private def filterOutSuspendedAgent[A](
    myEitherT: EitherT[
      Future,
      RelationshipFailureResponse,
      A
    ]
  ): Future[Option[Either[RelationshipFailureResponse, A]]] = myEitherT.value
    .map {
      case r @ Right(_) => Some(r)
      case l @ Left(error) if error != RelationshipFailureResponse.AgentSuspended => Some(l)
      case Left(_) => None

    }

  private def partitionMap[A](
    seq: Seq[Either[RelationshipFailureResponse, A]]
  ): Either[RelationshipFailureResponse, Seq[A]] = {
    val (lefts, rights) = seq.partitionMap(identity)
    lefts.headOption.toLeft(rights)
  }

}
