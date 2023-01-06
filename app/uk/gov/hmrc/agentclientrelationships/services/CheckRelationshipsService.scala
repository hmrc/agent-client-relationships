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
import uk.gov.hmrc.agentclientrelationships.model.UserId
import uk.gov.hmrc.agentclientrelationships.support.{Monitoring, TaxIdentifierSupport}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, EnrolmentKey}
import uk.gov.hmrc.domain.TaxIdentifier
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
    with TaxIdentifierSupport
    with Logging {

  def checkForRelationship(arn: Arn, userId: Option[UserId], taxIdentifier: TaxIdentifier)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier): Future[Boolean] = userId match {
    case None         => checkForRelationshipAgencyLevel(arn, taxIdentifier).map(_._1)
    case Some(userId) => checkForRelationshipUserLevel(arn, userId, taxIdentifier)
  }

  def checkForRelationshipAgencyLevel(arn: Arn, taxIdentifier: TaxIdentifier)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier): Future[(Boolean, String)] =
    for {
      groupId           <- es.getPrincipalGroupIdFor(arn)
      allocatedGroupIds <- es.getDelegatedGroupIdsFor(taxIdentifier)
      groupHasAssignedEnrolment = allocatedGroupIds.contains(groupId)
    } yield {
      (groupHasAssignedEnrolment, groupId)
    }

  def checkForRelationshipUserLevel(arn: Arn, userId: UserId, taxIdentifier: TaxIdentifier)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier): Future[Boolean] =
    // 1. Check that the agency with the given Arn has a relationship with the client.
    checkForRelationshipAgencyLevel(arn, taxIdentifier).flatMap {
      case (false, _) =>
        logger.info(s"checkForRelationship: Agency-level relationship does not exist between $arn and $taxIdentifier")
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
            val enrolmentKey = enrolmentKeyPrefixFor(taxIdentifier) + "~" + taxIdentifier.value
            val (serviceId, _) = EnrolmentKey.deconstruct(enrolmentKey)
            for {
              mGroupsSummaries <- ap.getGroupsSummaries(arn)
              // if the client is unassigned (not yet put into any access groups), behave as if granular permissions were disabled for that client
              isClientUnassigned = mGroupsSummaries.exists(_.unassignedClients.exists(_.enrolmentKey == enrolmentKey))
              isEnrolmentAssignedToUser <- es.getEnrolmentsAssignedToUser(userId.value, Some(serviceId)).map {
                                            usersAssignedEnrolments =>
                                              usersAssignedEnrolments.exists(enrolment =>
                                                EnrolmentKey.enrolmentKeys(enrolment).contains(enrolmentKey))
                                          }
            } yield {
              isClientUnassigned || isEnrolmentAssignedToUser
            }
          }
        }
    }

}
