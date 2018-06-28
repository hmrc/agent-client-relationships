/*
 * Copyright 2018 HM Revenue & Customs
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

import play.api.mvc.Request
import uk.gov.hmrc.agentclientrelationships.audit.{AuditData, AuditService}
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.controllers.fluentSyntax.{raiseError, returnValue}
import uk.gov.hmrc.agentclientrelationships.model.TypeOfEnrolment
import uk.gov.hmrc.agentclientrelationships.support.RelationshipNotFound
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.{AgentCode, TaxIdentifier}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

case class AgentUser(userId: String, groupId: String, agentCode: AgentCode)

trait ServiceHelper {

  val auditService: AuditService
  val es: EnrolmentStoreProxyConnector
  val ugs: UsersGroupsSearchConnector

  def setAuditDataForUser(currentUser: CurrentUser, arn: Arn, taxIdentifier: TaxIdentifier): AuditData = {
    val auditData = new AuditData()
    if (currentUser.credentials.providerType == "GovernmentGateway") {
      auditData.set("agentReferenceNumber", arn.value)
      auditData.set("clientId", taxIdentifier.value)
      auditData.set("clientIdType", taxIdentifier.getClass.getSimpleName)
      auditData.set("service", TypeOfEnrolment(taxIdentifier).enrolmentKey)
      auditData.set("currentUserAffinityGroup", currentUser.affinityGroup.map(_.toString).getOrElse("unknown"))
      auditData.set("authProviderId", currentUser.credentials.providerId)
      auditData.set("authProviderIdType", currentUser.credentials.providerType)
      auditData
    } else if (currentUser.credentials.providerType == "PrivilegedApplication") {
      auditData.set("authProviderId", currentUser.credentials.providerId)
      auditData.set("authProviderIdType", currentUser.credentials.providerType)
      auditData.set("agentReferenceNumber", arn.value)
      auditData.set("clientId", taxIdentifier.value)
      auditData.set("service", TypeOfEnrolment(taxIdentifier).enrolmentKey)
      auditData
    } else throw new IllegalStateException("No providerType found")
  }

  def sendAuditEventForUser(
    currentUser: CurrentUser)(implicit headerCarrier: HeaderCarrier, request: Request[Any], auditData: AuditData) =
    if (currentUser.credentials.providerType == "GovernmentGateway")
      auditService.sendDeleteRelationshipAuditEvent
    else if (currentUser.credentials.providerType == "PrivilegedApplication")
      auditService.sendHmrcLedDeleteRelationshipAuditEvent
    else throw new IllegalStateException("No Client Provider Type Found")

  def getAgentUserFor(
    arn: Arn)(implicit ec: ExecutionContext, hc: HeaderCarrier, auditData: AuditData): Future[AgentUser] =
    for {
      agentGroupId <- es.getPrincipalGroupIdFor(arn)
      agentUserId  <- es.getPrincipalUserIdFor(arn)
      _ = auditData.set("credId", agentUserId)
      groupInfo <- ugs.getGroupInfo(agentGroupId)
      agentCode = groupInfo.agentCode.getOrElse(throw new Exception(s"Missing AgentCode for $arn"))
      _ = auditData.set("agentCode", agentCode)
    } yield AgentUser(agentUserId, agentGroupId, agentCode)

  def checkForRelationship(identifier: TaxIdentifier, agentUser: AgentUser)(
    implicit ec: ExecutionContext,
    hc: HeaderCarrier,
    auditData: AuditData): Future[Either[String, Boolean]] =
    for {
      allocatedGroupIds <- es.getDelegatedGroupIdsFor(identifier)
      result <- if (allocatedGroupIds.contains(agentUser.groupId)) returnValue(Right(true))
               else raiseError(RelationshipNotFound("RELATIONSHIP_NOT_FOUND"))
    } yield result
}
