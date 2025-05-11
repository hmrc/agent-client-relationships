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
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys.agentCodeKey
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys.credIdKey
import uk.gov.hmrc.agentclientrelationships.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.agentclientrelationships.connectors.UsersGroupsSearchConnector
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.AgentCode
import play.api.mvc.RequestHeader

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

case class AgentUser(
  userId: String,
  groupId: String,
  agentCode: AgentCode,
  arn: Arn
)

@Singleton
class AgentUserService @Inject() (
  es: EnrolmentStoreProxyConnector,
  ugs: UsersGroupsSearchConnector,
  agentCacheProvider: AgentCacheProvider
)(implicit val executionContext: ExecutionContext)
    extends Logging {

  val principalGroupIdCache = agentCacheProvider.esPrincipalGroupIdCache
  val firstGroupAdminCache = agentCacheProvider.ugsFirstGroupAdminCache
  val groupInfoCache = agentCacheProvider.ugsGroupInfoCache

  def getAgentAdminAndSetAuditData(arn: Arn)(implicit
    request: RequestHeader,
    auditData: AuditData
  ): Future[Either[String, AgentUser]] =
    for {
      agentGroupId   <- principalGroupIdCache(arn.value)(es.getPrincipalGroupIdFor(arn))
      firstAdminUser <- firstGroupAdminCache(agentGroupId)(ugs.getFirstGroupAdminUser(agentGroupId))
      adminUserId = firstAdminUser.flatMap(_.userId)
      _ = adminUserId.foreach(auditData.set(credIdKey, _))
      groupInfo <- groupInfoCache(agentGroupId)(ugs.getGroupInfo(agentGroupId))
      agentCode = groupInfo.flatMap(_.agentCode)
      _ = agentCode.foreach(auditData.set(agentCodeKey, _))
    } yield (adminUserId, groupInfo, agentCode) match {
      case (Some(userId), Some(_), Some(code)) =>
        Right(
          AgentUser(
            userId,
            agentGroupId,
            code,
            arn
          )
        )
      case (None, _, _) =>
        logger.warn(s"Admin user had no userId for Arn: $arn")
        Left("NO_ADMIN_USER")
      case (Some(userId), None, _) =>
        logger.warn(s"Missing Group for Arn: $arn and admin user: $userId")
        Left("MISSING_GROUP")
      case (_, Some(groupInfo), None) =>
        logger.warn(s"Missing AgentCode for Arn: $arn and group: ${groupInfo.groupId}")
        Left("NO_AGENT_CODE")
    }

}
