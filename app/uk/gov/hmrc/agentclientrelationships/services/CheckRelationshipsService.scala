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

import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.ExistingMainAgent
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentKey => LocalEnrolmentKey, Invitation, UserId}
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.support.Monitoring
import uk.gov.hmrc.agentmtdidentifiers.model.EnrolmentKey.enrolmentKey
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCCBCORG, HMRCMTDIT, HMRCMTDITSUPP, HMRCPIR}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Enrolment}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CheckRelationshipsService @Inject() (
  es: EnrolmentStoreProxyConnector,
  ap: AgentPermissionsConnector,
  agentAssuranceConnector: AgentAssuranceConnector,
  ifConnector: IFConnector,
  groupSearch: UsersGroupsSearchConnector,
  partialAuthRepository: PartialAuthRepository,
  agentFiRelationshipConnector: AgentFiRelationshipConnector,
  val metrics: Metrics
) extends Monitoring
    with Logging {

  def checkForRelationship(arn: Arn, userId: Option[UserId], enrolmentKey: LocalEnrolmentKey)(implicit
    ec: ExecutionContext,
    hc: HeaderCarrier
  ): Future[Boolean] = userId match {
    case None         => checkForRelationshipAgencyLevel(arn, enrolmentKey).map(_._1)
    case Some(userId) => checkForRelationshipUserLevel(arn, userId, enrolmentKey)
  }

  def checkForRelationshipAgencyLevel(arn: Arn, enrolmentKey: LocalEnrolmentKey)(implicit
    ec: ExecutionContext,
    hc: HeaderCarrier
  ): Future[(Boolean, String)] =
    for {
      groupId           <- es.getPrincipalGroupIdFor(arn)
      allocatedGroupIds <- es.getDelegatedGroupIdsFor(enrolmentKey)
      groupHasAssignedEnrolment = allocatedGroupIds.contains(groupId)
    } yield (groupHasAssignedEnrolment, groupId)

  def checkForRelationshipUserLevel(arn: Arn, userId: UserId, enrolmentKey: LocalEnrolmentKey)(implicit
    ec: ExecutionContext,
    hc: HeaderCarrier
  ): Future[Boolean] =
    // 1. Check that the agency with the given Arn has a relationship with the client.
    checkForRelationshipAgencyLevel(arn, enrolmentKey).flatMap {
      case (false, _) =>
        logger.info(s"checkForRelationship: Agency-level relationship does not exist between $arn and $enrolmentKey")
        Future.successful(false)
      case (true, groupId) =>
        // 2. Check that the user belongs to the Arn's group.
        groupSearch.getGroupUsers(groupId).flatMap { groupUsers =>
          val userBelongsToGroup = groupUsers.exists(_.userId.contains(userId.value))
          if (!userBelongsToGroup) {
            logger.info(s"User ${userId.value} does not belong to group of Arn $arn (groupId $groupId)")
            Future.successful(false)
          } else {
            // 3. Check that the client is assigned to the agent user.
            val serviceId = enrolmentKey.service
            for {
              // if the client is unassigned (not yet put into any access groups), behave as if granular permissions were disabled for that client
              isClientUnassigned <- ap.clientIsUnassigned(arn, enrolmentKey)
              isEnrolmentAssignedToUser <-
                es.getEnrolmentsAssignedToUser(userId.value, Some(serviceId)).map { usersAssignedEnrolments =>
                  usersAssignedEnrolments.exists(enrolment =>
                    enrolmentKeys(enrolment)
                      .contains(enrolmentKey.tag)
                  )
                }
            } yield isClientUnassigned || isEnrolmentAssignedToUser
          }
        }
    }

  private def enrolmentKeys(enrolment: Enrolment): Seq[String] =
    enrolment.identifiers.map(identifier => enrolmentKey(enrolment.service, identifier.value))

  private def getArnForDelegatedEnrolmentKey(
    enrolKey: LocalEnrolmentKey
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[Arn]] =
    for {
      maybeGroupId <- es.getDelegatedGroupIdsFor(enrolKey)
      maybeArn <- maybeGroupId.headOption match {
                    case Some(groupId) => es.getAgentReferenceNumberFor(groupId)
                    case None          => Future.successful(None)
                  }
    } yield maybeArn

  private def findMainAgentForNino(
    invitation: Invitation
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[ExistingMainAgent]] =
    partialAuthRepository.findMainAgent(invitation.clientId).flatMap {
      case Some(p) =>
        agentAssuranceConnector
          .getAgentRecordWithChecks(Arn(p.arn))
          .map(agent =>
            Some(
              ExistingMainAgent(
                agencyName = agent.agencyDetails.agencyName,
                sameAgent = p.arn == invitation.arn
              )
            )
          )
      case None =>
        ifConnector.getMtdIdFor(Nino(invitation.clientId)).flatMap {
          case Some(mtdItId) =>
            getArnForDelegatedEnrolmentKey(LocalEnrolmentKey(enrolmentKey(HMRCMTDIT, mtdItId.value))).flatMap {
              case Some(a) => returnExistingMainAgentFromArn(a.value, a.value == invitation.arn)
              case None    => Future.successful(None)
            }
          case _ => Future.successful(None)
        }
    }

  private def returnExistingMainAgentFromArn(
    arn: String,
    sameAgent: Boolean
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Some[ExistingMainAgent]] =
    agentAssuranceConnector
      .getAgentRecordWithChecks(Arn(arn))
      .map(agent =>
        Some(
          ExistingMainAgent(
            agencyName = agent.agencyDetails.agencyName,
            sameAgent = sameAgent
          )
        )
      )

  def findCurrentMainAgent(
    invitation: Invitation,
    enrolment: Option[LocalEnrolmentKey]
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Option[ExistingMainAgent]] =
    invitation.service match {
      case HMRCMTDIT | HMRCMTDITSUPP if Nino.isValid(invitation.clientId) => findMainAgentForNino(invitation)
      case HMRCPIR =>
        agentFiRelationshipConnector.findIrvRelationshipForClient(invitation.clientId).flatMap {
          case Some(r) => returnExistingMainAgentFromArn(r.arn.value, invitation.arn == r.arn.value)
          case None    => Future.successful(None)
        }
      case HMRCCBCORG if enrolment.isDefined =>
        getArnForDelegatedEnrolmentKey(enrolment.get).flatMap {
          case Some(a) => returnExistingMainAgentFromArn(a.value, a.value == invitation.arn)
          case None    => Future.successful(None)
        }
      case _ =>
        getArnForDelegatedEnrolmentKey(
          LocalEnrolmentKey(
            enrolmentKey(
              if (invitation.service == HMRCMTDITSUPP) HMRCMTDIT else invitation.service,
              invitation.clientId
            )
          )
        ).flatMap {
          case Some(a) => returnExistingMainAgentFromArn(a.value, a.value == invitation.arn)
          case None    => Future.successful(None)
        }
    }
}
