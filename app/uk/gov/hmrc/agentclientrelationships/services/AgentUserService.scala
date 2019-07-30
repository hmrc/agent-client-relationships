/*
 * Copyright 2019 HM Revenue & Customs
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

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.connectors.{EnrolmentStoreProxyConnector, UsersGroupsSearchConnector}
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

case class AgentUser(userId: String, groupId: String, agentCode: AgentCode, arn: Arn)

@Singleton
class AgentUserService @Inject()(
  es: EnrolmentStoreProxyConnector,
  ugs: UsersGroupsSearchConnector
) {

  def getAgentAdminUserFor(
    arn: Arn)(implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[AgentUser] =
    for {
      agentGroupId     <- es.getPrincipalGroupIdFor(arn)
      agentUserIds     <- es.getPrincipalUserIdsFor(arn)
      adminAgentUserId <- ugs.getAdminUserId(agentUserIds)
      _ = auditData.set("credId", adminAgentUserId)
      groupInfo <- ugs.getGroupInfo(agentGroupId)
      agentCode = groupInfo.agentCode.getOrElse(throw new Exception(s"Missing AgentCode for $arn"))
      _ = auditData.set("agentCode", agentCode)
    } yield AgentUser(adminAgentUserId, agentGroupId, agentCode, arn)

}
