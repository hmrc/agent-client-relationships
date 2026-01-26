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

package uk.gov.hmrc.agentclientrelationships.services

import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.ExistingMainAgent
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentKey => LocalEnrolmentKey}
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.UserId
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.model.identifiers.EnrolmentKey.enrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCCBCORG
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCMTDIT
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCPIR
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Enrolment
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import play.api.mvc.RequestHeader

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class CheckRelationshipsService @Inject() (
  es: EnrolmentStoreProxyConnector,
  ap: AgentPermissionsConnector,
  agentAssuranceService: AgentAssuranceService,
  hipConnector: HipConnector,
  groupSearch: UsersGroupsSearchConnector,
  partialAuthRepository: PartialAuthRepository,
  agentFiRelationshipConnector: AgentFiRelationshipConnector
)(implicit executionContext: ExecutionContext)
extends RequestAwareLogging {

  def checkForRelationship(
    arn: Arn,
    userId: Option[UserId],
    enrolmentKey: LocalEnrolmentKey
  )(implicit request: RequestHeader): Future[Boolean] =
    userId match {
      case None => checkForRelationshipAgencyLevel(arn, enrolmentKey).map(_._1)
      case Some(userId) =>
        checkForRelationshipUserLevel(
          arn,
          userId,
          enrolmentKey
        )
    }

  def checkForRelationshipAgencyLevel(
    arn: Arn,
    enrolmentKey: LocalEnrolmentKey
  )(implicit request: RequestHeader): Future[(Boolean, String)] =
    for {
      groupId <- es.getPrincipalGroupIdFor(arn)
      allocatedGroupIds <- es.getDelegatedGroupIdsFor(enrolmentKey)
      groupHasAssignedEnrolment = allocatedGroupIds.contains(groupId)
    } yield (groupHasAssignedEnrolment, groupId)

  def checkForRelationshipUserLevel(
    arn: Arn,
    userId: UserId,
    enrolmentKey: LocalEnrolmentKey
  )(implicit request: RequestHeader): Future[Boolean] =
    // 1. Check that the agency with the given Arn has a relationship with the client.
    checkForRelationshipAgencyLevel(arn, enrolmentKey).flatMap {
      case (false, _) =>
        logger.info(s"checkForRelationship: Agency-level relationship does not exist between $arn and $enrolmentKey")
        Future.successful(false)
      case (true, groupId) =>
        // 2. Check that the user belongs to the Arn's group.
        groupSearch
          .getGroupUsers(groupId)
          .flatMap { groupUsers =>
            val userBelongsToGroup = groupUsers.exists(_.userId.contains(userId.value))
            if (!userBelongsToGroup) {
              logger.info(s"User ${userId.value} does not belong to group of Arn $arn (groupId $groupId)")
              Future.successful(false)
            }
            else {
              // 3. Check that the client is assigned to the agent user.
              val serviceId = enrolmentKey.service
              for {
                // if the client is unassigned (not yet put into any access groups), behave as if granular permissions were disabled for that client
                isClientUnassigned <- ap.isClientUnassigned(arn, enrolmentKey)
                isEnrolmentAssignedToUser <- es
                  .getEnrolmentsAssignedToUser(userId.value, Some(serviceId))
                  .map { usersAssignedEnrolments =>
                    usersAssignedEnrolments.exists(enrolment =>
                      enrolmentKeys(enrolment).contains(enrolmentKey.tag)
                    )
                  }
              } yield isClientUnassigned || isEnrolmentAssignedToUser
            }
          }
    }

  private def enrolmentKeys(enrolment: Enrolment): Seq[String] = enrolment.identifiers
    .map(identifier => enrolmentKey(enrolment.service, identifier.value))

  private def getArnForDelegatedEnrolmentKey(
    enrolKey: LocalEnrolmentKey
  )(implicit request: RequestHeader): Future[Option[Arn]] =
    for {
      maybeGroupId <- es.getDelegatedGroupIdsFor(enrolKey)
      maybeArn <-
        maybeGroupId.headOption match {
          case Some(groupId) => es.getAgentReferenceNumberFor(groupId)
          case None => Future.successful(None)
        }
    } yield maybeArn

  private def findMainAgentForNino(
    invitation: Invitation
  )(implicit request: RequestHeader): Future[Option[ExistingMainAgent]] = partialAuthRepository
    .findMainAgent(invitation.clientId)
    .flatMap {
      case Some(p) =>
        agentAssuranceService
          .getAgentRecord(Arn(p.arn))
          .map(agent =>
            Some(ExistingMainAgent(agencyName = agent.agencyDetails.agencyName, sameAgent = p.arn == invitation.arn))
          )
      case None =>
        hipConnector
          .getMtdIdFor(NinoWithoutSuffix(invitation.clientId))
          .flatMap {
            case Some(mtdItId) =>
              getArnForDelegatedEnrolmentKey(LocalEnrolmentKey(enrolmentKey(HMRCMTDIT, mtdItId.value))).flatMap {
                case Some(a) => returnExistingMainAgentFromArn(a.value, a.value == invitation.arn)
                case None => Future.successful(None)
              }
            case _ => Future.successful(None)
          }
    }

  private def returnExistingMainAgentFromArn(
    arn: String,
    sameAgent: Boolean
  )(implicit request: RequestHeader): Future[Some[ExistingMainAgent]] = agentAssuranceService
    .getAgentRecord(Arn(arn))
    .map(agent => Some(ExistingMainAgent(agencyName = agent.agencyDetails.agencyName, sameAgent = sameAgent)))

  def findCurrentMainAgent(
    invitation: Invitation,
    enrolment: Option[LocalEnrolmentKey]
  )(implicit request: RequestHeader): Future[Option[ExistingMainAgent]] =
    invitation.service match {
      case HMRCMTDIT | HMRCMTDITSUPP if NinoWithoutSuffix.isValid(invitation.clientId) => findMainAgentForNino(invitation)
      case HMRCPIR =>
        agentFiRelationshipConnector
          .findIrvActiveRelationshipForClient(invitation.clientId)
          .flatMap {
            case Right(r :: _) => returnExistingMainAgentFromArn(r.arn.value, invitation.arn == r.arn.value)
            case _ => Future.successful(None)
          }
      case HMRCCBCORG if enrolment.isDefined =>
        getArnForDelegatedEnrolmentKey(enrolment.get).flatMap {
          case Some(a) => returnExistingMainAgentFromArn(a.value, a.value == invitation.arn)
          case None => Future.successful(None)
        }
      case _ =>
        getArnForDelegatedEnrolmentKey(
          LocalEnrolmentKey(
            enrolmentKey(
              if (invitation.service == HMRCMTDITSUPP)
                HMRCMTDIT
              else
                invitation.service,
              invitation.clientId
            )
          )
        ).flatMap {
          case Some(a) => returnExistingMainAgentFromArn(a.value, a.value == invitation.arn)
          case None => Future.successful(None)
        }
    }

}
