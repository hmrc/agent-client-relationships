/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.audit

import java.util.concurrent.ConcurrentHashMap

import com.google.inject.Singleton
import javax.inject.Inject
import play.api.mvc.Request
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent.AgentClientRelationshipEvent
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.AuditExtensions.auditHeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.DataEvent

import scala.collection.JavaConversions
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object AgentClientRelationshipEvent extends Enumeration {
  val CreateRelationship, CheckCESA, CheckES, ClientTerminatedAgentServiceAuthorisation,
  RecoveryOfDeleteRelationshipHasBeenAbandoned, HmrcRemovedAgentServiceAuthorisation = Value
  type AgentClientRelationshipEvent = Value
}

class AuditData {

  private val details = new ConcurrentHashMap[String, Any]

  def set(key: String, value: Any): Unit = {
    details.put(key, value)
    ()
  }

  def getDetails: Map[String, Any] =
    JavaConversions.mapAsScalaMap(details).toMap

}

@Singleton
class AuditService @Inject()(val auditConnector: AuditConnector) {

  private def collectDetails(data: Map[String, Any], fields: Seq[String]): Seq[(String, Any)] = fields.map { f =>
    (f, data.getOrElse(f, ""))
  }

  val createRelationshipDetailsFields: Seq[String] = Seq(
    "agentCode",
    "credId",
    "arn",
    "saAgentRef",
    "service",
    "clientId",
    "clientIdType",
    "CESARelationship",
    "etmpRelationshipCreated",
    "enrolmentDelegated",
    "nino",
    "AgentDBRecord",
    "Journey"
  )

  val createRelationshipDetailsFieldsForMtdVat: Seq[String] = Seq(
    "agentCode",
    "credId",
    "arn",
    "service",
    "vrn",
    "oldAgentCodes",
    "ESRelationship",
    "etmpRelationshipCreated",
    "enrolmentDelegated",
    "AgentDBRecord",
    "Journey")

  val CheckCESADetailsFields: Seq[String] = Seq("agentCode", "credId", "arn", "saAgentRef", "CESARelationship", "nino")

  val CheckESDetailsFields: Seq[String] = Seq("agentCode", "credId", "oldAgentCodes", "vrn", "arn", "ESRelationship")

  val deleteRelationshipDetailsFields: Seq[String] =
    Seq(
      "agentReferenceNumber",
      "clientId",
      "clientIdType",
      "service",
      "currentUserAffinityGroup",
      "authProviderId",
      "authProviderIdType",
      "deleteStatus")

  val hmrcDeleteRelationshipDetailsFields: Seq[String] =
    Seq(
      "authProviderId",
      "authProviderIdType",
      "agentReferenceNumber",
      "clientId",
      "service",
      "deleteStatus"
    )

  val recoveryOfDeleteRelationshipDetailsFields: Seq[String] = Seq(
    "agentReferenceNumber",
    "clientId",
    "clientIdType",
    "service",
    "currentUserAffinityGroup",
    "authProviderId",
    "authProviderIdType",
    "initialDeleteDateTime",
    "numberOfAttempts",
    "abandonmentReason"
  )

  def sendCreateRelationshipAuditEvent(
    implicit hc: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData,
    ec: ExecutionContext): Future[Unit] =
    auditEvent(
      AgentClientRelationshipEvent.CreateRelationship,
      "create-relationship",
      collectDetails(auditData.getDetails, createRelationshipDetailsFields))

  def sendCreateRelationshipAuditEventForMtdVat(
    implicit hc: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData,
    ec: ExecutionContext): Future[Unit] =
    auditEvent(
      AgentClientRelationshipEvent.CreateRelationship,
      "create-relationship",
      collectDetails(auditData.getDetails, createRelationshipDetailsFieldsForMtdVat))

  def sendCheckCESAAuditEvent(
    implicit hc: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData,
    ec: ExecutionContext): Future[Unit] =
    auditEvent(
      AgentClientRelationshipEvent.CheckCESA,
      "check-cesa",
      collectDetails(auditData.getDetails, CheckCESADetailsFields))

  def sendCheckESAuditEvent(
    implicit hc: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData,
    ec: ExecutionContext): Future[Unit] =
    auditEvent(
      AgentClientRelationshipEvent.CheckES,
      "check-es",
      collectDetails(auditData.getDetails, CheckESDetailsFields))

  def sendDeleteRelationshipAuditEvent(
    implicit hc: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData,
    ec: ExecutionContext): Future[Unit] =
    auditEvent(
      AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation,
      "client terminated agent:service authorisation",
      collectDetails(auditData.getDetails, deleteRelationshipDetailsFields)
    )

  def sendRecoveryOfDeleteRelationshipHasBeenAbandonedAuditEvent(
    implicit hc: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData,
    ec: ExecutionContext): Future[Unit] =
    auditEvent(
      AgentClientRelationshipEvent.RecoveryOfDeleteRelationshipHasBeenAbandoned,
      "recovery-of-delete-relationship-abandoned",
      collectDetails(auditData.getDetails, recoveryOfDeleteRelationshipDetailsFields)
    )

  def sendHmrcLedDeleteRelationshipAuditEvent(
    implicit headerCarrier: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData,
    ec: ExecutionContext): Future[Unit] =
    auditEvent(
      AgentClientRelationshipEvent.HmrcRemovedAgentServiceAuthorisation,
      "hmrc remove agent:service authorisation",
      collectDetails(auditData.getDetails, hmrcDeleteRelationshipDetailsFields)
    )

  private[audit] def auditEvent(
    event: AgentClientRelationshipEvent,
    transactionName: String,
    details: Seq[(String, Any)] = Seq.empty)(
    implicit hc: HeaderCarrier,
    request: Request[Any],
    ec: ExecutionContext): Future[Unit] =
    send(createEvent(event, transactionName, details: _*))

  private def createEvent(event: AgentClientRelationshipEvent, transactionName: String, details: (String, Any)*)(
    implicit hc: HeaderCarrier,
    request: Request[Any],
    ec: ExecutionContext): DataEvent = {

    def toString(x: Any): String = x match {
      case t: TaxIdentifier => t.value
      case _                => x.toString
    }

    val detail = hc.toAuditDetails(details.map(pair => pair._1 -> toString(pair._2)): _*)
    val tags = hc.toAuditTags(transactionName, request.path)
    DataEvent(auditSource = "agent-client-relationships", auditType = event.toString, tags = tags, detail = detail)
  }

  private def send(events: DataEvent*)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Unit] =
    Future {
      events.foreach { event =>
        Try(auditConnector.sendEvent(event))
      }
    }

}
