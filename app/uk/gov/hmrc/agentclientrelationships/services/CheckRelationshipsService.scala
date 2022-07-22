/*
 * Copyright 2022 HM Revenue & Customs
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
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.support.{Monitoring, TaxIdentifierSupport}
import uk.gov.hmrc.agentmtdidentifiers.model.EnrolmentKey
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CheckRelationshipsService @Inject()(
  es: EnrolmentStoreProxyConnector,
  ap: AgentPermissionsConnector,
  appConfig: AppConfig,
  val metrics: Metrics)
    extends Monitoring
    with TaxIdentifierSupport {

  def checkForRelationship(taxIdentifier: TaxIdentifier, agentUser: AgentUser)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier): Future[Boolean] =
    es.getDelegatedGroupIdsFor(taxIdentifier).flatMap { allocatedGroupIds =>
      val userBelongsToGroup = allocatedGroupIds.contains(agentUser.groupId)
      if (!userBelongsToGroup) Future.successful(false)
      else {
        val enrolmentKey = enrolmentKeyPrefixFor(taxIdentifier) + "~" + taxIdentifier.value
        val (serviceId, _) = EnrolmentKey.deconstruct(enrolmentKey)
        for {
          // if Granular Permissions are disabled or opted-out then the user can act for the client as long as an agent/client relationship exists
          granPermsEnabled <- if (!appConfig.enableGranularPermissions) Future.successful(false)
                             else ap.granularPermissionsOptinRecordExists(agentUser.arn)
          // if Granular Permissions are enabled and opted-in then we must check if the client is assigned to the user
          mGroupsSummaries <- if (!granPermsEnabled) Future.successful(None) else ap.getGroupsSummaries(agentUser.arn)
          // if the client is unassigned (not yet put into any access groups), behave as if granular permissions were disabled for that client
          isClientUnassigned = mGroupsSummaries.exists(_.unassignedClients.exists(_.enrolmentKey == enrolmentKey))
          isEnrolmentAssignedToUser <- if (!granPermsEnabled || isClientUnassigned) Future.successful(false)
                                      else
                                        es.getEnrolmentsAssignedToUser(agentUser.userId, Some(serviceId)).map {
                                          usersAssignedEnrolments =>
                                            usersAssignedEnrolments.exists(enrolment =>
                                              EnrolmentKey.enrolmentKeys(enrolment).contains(enrolmentKey))
                                        }
        } yield {
          (!granPermsEnabled) || isClientUnassigned || isEnrolmentAssignedToUser
        }
      }
    }
}
