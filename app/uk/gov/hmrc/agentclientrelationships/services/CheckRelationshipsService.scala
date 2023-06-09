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

import com.kenshoo.play.metrics.Metrics
import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentKey, UserId}
import uk.gov.hmrc.agentclientrelationships.support.Monitoring
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CheckRelationshipsService @Inject()(
  es: EnrolmentStoreProxyConnector,
  ap: AgentPermissionsConnector,
  groupSearch: UsersGroupsSearchConnector,
  val metrics: Metrics)
    extends Monitoring
    with Logging {

  def checkForRelationship(arn: Arn, userId: Option[UserId], enrolmentKey: EnrolmentKey)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier): Future[Boolean] = userId match {
    case None         => checkForRelationshipAgencyLevel(arn, enrolmentKey).map(_._1)
    case Some(userId) => checkForRelationshipUserLevel(arn, userId, enrolmentKey)
  }

  def checkForRelationshipAgencyLevel(arn: Arn, enrolmentKey: EnrolmentKey)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier): Future[(Boolean, String)] =
    for {
      groupId           <- es.getPrincipalGroupIdFor(arn)
      allocatedGroupIds <- es.getDelegatedGroupIdsFor(enrolmentKey)
      groupHasAssignedEnrolment = allocatedGroupIds.contains(groupId)
    } yield {
      (groupHasAssignedEnrolment, groupId)
    }

  def checkForRelationshipUserLevel(arn: Arn, userId: UserId, enrolmentKey: EnrolmentKey)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier): Future[Boolean] =
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
              isEnrolmentAssignedToUser <- es.getEnrolmentsAssignedToUser(userId.value, Some(serviceId)).map {
                                            usersAssignedEnrolments =>
                                              usersAssignedEnrolments.exists(
                                                enrolment =>
                                                  uk.gov.hmrc.agentmtdidentifiers.model.EnrolmentKey
                                                    .enrolmentKeys(enrolment)
                                                    .contains(enrolmentKey.tag))
                                          }
            } yield {
              isClientUnassigned || isEnrolmentAssignedToUser
            }
          }
        }
    }

}
