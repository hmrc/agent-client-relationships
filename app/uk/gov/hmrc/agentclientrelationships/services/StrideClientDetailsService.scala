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
import uk.gov.hmrc.agentclientrelationships.model.clientDetails._
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.agentclientrelationships.model.stride._
import uk.gov.hmrc.agentclientrelationships.model.{ActiveRelationship, EnrolmentKey, RelationshipFailureResponse}
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP, MtdIt, PersonalIncomeRecord}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Service}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

@Singleton
class StrideClientDetailsService @Inject() (
  invitationsRepository: InvitationsRepository,
  agentAssuranceConnector: AgentAssuranceConnector,
  findRelationshipsService: FindRelationshipsService,
  agentFiRelationshipConnector: AgentFiRelationshipConnector,
  partialAuthRepository: PartialAuthRepository,
  clientDetailsService: ClientDetailsService,
  validationService: ValidationService
)(implicit ec: ExecutionContext) {

  def getClientDetailsWithChecks(
    ek: EnrolmentKey
  )(implicit hc: HeaderCarrier): Future[Option[ClientDetailsStrideResponse]] = {

    val clientId: String = ek.oneTaxIdentifier().value
    val services = if (ek.service == HMRCMTDIT) Seq(ek.service, HMRCMTDITSUPP) else Seq(ek.service)

    for {
      invitations <- getNonSuspendedInvitations(clientId, services)
      clientName  <- getClientName(invitations, ek.service, clientId)
      mActiveRel  <- findActiveRelationship(ek.oneTaxIdentifier(), Service.forId(ek.service))
      mMainAgent  <- findAgentDetails(mActiveRel)
      clientDetails = clientName.map(name => ClientDetailsStrideResponse(name, invitations, mMainAgent))
    } yield clientDetails
  }

  def findAllActiveRelationship(
    clientsRelationshipsRequest: ClientsRelationshipsRequest
  )(implicit hc: HeaderCarrier): Future[Either[RelationshipFailureResponse, Seq[ActiveClientRelationship]]] =
    clientsRelationshipsRequest.clientRelationshipRequest
      .map { crr =>
        for {
          taxIdentifier <-
            EitherT.fromEither[Future](validationService.validateForTaxIdentifier(crr.clientIdType, crr.clientId))
          activeRelationships <- EitherT(findAllActiveRelationshipForTaxId(taxIdentifier))
          clientAgentsData    <- findAgentClientDataForRelationships(taxIdentifier, activeRelationships)
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
    hc: HeaderCarrier
  ): EitherT[Future, RelationshipFailureResponse, (ClientDetailsResponse, Seq[ClientRelationshipWithAgentName])] =
    for {
      activeRelationshipsWithAgentName <-
        findAgentNameForActiveRelationships(activeRelationships, taxIdentifier: TaxIdentifier)
      clientDetails <- EitherT(findClientDetailsByTaxIdentifier(taxIdentifier))

    } yield (clientDetails, activeRelationshipsWithAgentName)

  private def findAllActiveRelationshipForTaxId(
    taxIdentifier: TaxIdentifier
  )(implicit hc: HeaderCarrier): Future[Either[RelationshipFailureResponse, Seq[ClientRelationship]]] =
    (taxIdentifier match {
      case Nino(_) =>
        findRelationshipsService.getAllActiveItsaRelationshipForClient(
          nino = Nino(taxIdentifier.value),
          activeOnly = true
        )
      case _ => findRelationshipsService.getAllRelationshipsForClient(taxIdentifier = taxIdentifier, activeOnly = true)

    }).map(_.map(_.filter(_.isActive))) // additional filtering for IF

  private def findAgentNameForActiveRelationships(
    activeRelationships: Seq[ClientRelationship],
    taxIdentifier: TaxIdentifier
  )(implicit hc: HeaderCarrier): EitherT[Future, RelationshipFailureResponse, Seq[ClientRelationshipWithAgentName]] =
    activeRelationships.map { ar =>
      for {
        agentDetails <- findAgentDetailsByArn(ar.arn)
        service      <- EitherT(validationService.validateAuthProfileToService(taxIdentifier, ar.authProfile))
      } yield ClientRelationshipWithAgentName(
        ar.arn,
        agentDetails.agencyDetails.agencyName,
        service.id,
        ar.dateTo,
        ar.dateFrom
      )
    }.sequence

  private def findAgentDetailsByArn(
    arn: Arn
  )(implicit hc: HeaderCarrier): EitherT[Future, RelationshipFailureResponse, AgentDetailsDesResponse] =
    EitherT(
      Try(agentAssuranceConnector.getAgentRecordWithChecks(arn)).toEither
        .pure[Future]
    ).semiflatMap(identity)
      .leftMap(er => RelationshipFailureResponse.ErrorRetrievingAgentDetails(er.getMessage))

  private def findClientDetailsByTaxIdentifier(
    taxIdentifier: TaxIdentifier
  )(implicit hc: HeaderCarrier): Future[Either[RelationshipFailureResponse, ClientDetailsResponse]] =
    clientDetailsService
      .findClientDetailsByTaxIdentifier(taxIdentifier)
      .map(_.left.map {
        case ClientDetailsNotFound => RelationshipFailureResponse.ClientDetailsNotFound
        case ErrorRetrievingClientDetails(status, msg) =>
          RelationshipFailureResponse.ErrorRetrievingClientDetails(status, msg)
      })

  private def agentIsSuspended(agentRecord: AgentDetailsDesResponse): Boolean =
    agentRecord.suspensionDetails.exists(_.suspensionStatus)

  private def getNonSuspendedInvitations(clientId: String, services: Seq[String])(implicit
    hc: HeaderCarrier
  ): Future[Seq[InvitationWithAgentName]] =
    for {
      invitations <- invitationsRepository.findAllPendingForSuppliedClient(clientId, services)
      nonSuspended <- Future.sequence(
                        invitations.map(i =>
                          agentAssuranceConnector
                            .getAgentRecordWithChecks(Arn(i.arn))
                            .map(agentRecord =>
                              if (agentIsSuspended(agentRecord)) None
                              else Some(InvitationWithAgentName.fromInvitationAndAgentRecord(i, agentRecord))
                            )
                        )
                      )
    } yield nonSuspended.flatten

  private def findActiveRelationship(taxIdentifier: TaxIdentifier, service: Service)(implicit
    hc: HeaderCarrier
  ): Future[Option[ActiveMainAgentRelationship]] =
    (taxIdentifier, service) match {
      case (_: Nino, MtdIt) =>
        for {
          partialAuth <- partialAuthRepository.findMainAgent(taxIdentifier.value)
          mRel <- if (partialAuth.isEmpty) {
                    findRelationshipsService.getItsaRelationshipForClient(Nino(taxIdentifier.value), service)
                  } else Future.successful(partialAuth.map(pa => ActiveRelationship(Arn(pa.arn), None, None)))
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
  )(implicit hc: HeaderCarrier): Future[Option[ActiveMainAgent]] =
    mActiveRelationship.fold[Future[Option[ActiveMainAgent]]](Future.successful(None)) { activeRelationship =>
      agentAssuranceConnector
        .getAgentRecordWithChecks(Arn(activeRelationship.arn))
        .map(ar =>
          Option(ActiveMainAgent(ar.agencyDetails.agencyName, activeRelationship.arn, activeRelationship.service))
        )
    }

  private def getClientName(invitations: Seq[InvitationWithAgentName], service: String, clientId: String)(implicit
    hc: HeaderCarrier
  ): Future[Option[String]] =
    for {
      fromInv <- Future.successful(invitations.headOption.map(i => i.clientName))
      result <- if (fromInv.isEmpty)
                  clientDetailsService.findClientDetails(service, clientId).map(_.toOption).map(_.map(_.name))
                else Future.successful(fromInv)
    } yield result

  case class ActiveMainAgentRelationship(arn: String, service: String)

}
