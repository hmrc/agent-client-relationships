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

package uk.gov.hmrc.agentclientrelationships.audit

import com.google.inject.Singleton
import play.api.Logging
import play.api.mvc.Request
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys._
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.auth.core.AffinityGroup
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.AuditExtensions.auditHeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.DataEvent

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.MapHasAsScala
import scala.util.Try

object AgentClientRelationshipEvent extends Enumeration {
  val CreateRelationship, CreatePartialAuthorisation, CheckCESA, CheckES, TerminateRelationship,
    TerminatePartialAuthorisation, RecoveryOfDeleteRelationshipHasBeenAbandoned =
    Value
  type AgentClientRelationshipEvent = Value
}

class AuditData {

  private val details = new ConcurrentHashMap[String, Any]

  def set(key: String, value: Any): Unit = {
    details.put(key, value)
    ()
  }

  def getDetails: Map[String, Any] =
    details.asScala.toMap

}

@Singleton
class AuditService @Inject() (auditConnector: AuditConnector)(implicit ec: ExecutionContext) extends Logging {

  private def collectDetails(data: Map[String, Any], fields: Seq[String]): Seq[(String, Any)] =
    for {
      field <- fields
      value <- data.get(field)
    } yield (field, value)

  private val createRelationshipDetailsFields: Seq[String] = Seq(
    agentCodeKey,
    credIdKey,
    arnKey,
    saAgentRefKey,
    serviceKey,
    clientIdKey,
    clientIdTypeKey,
    cesaRelationshipKey,
    etmpRelationshipCreatedKey,
    enrolmentDelegatedKey,
    ninoKey,
    howRelationshipCreatedKey,
    invitationIdKey
  )

  private val createPartialAuthDetailsFields: Seq[String] = Seq(
    arnKey,
    serviceKey,
    clientIdKey,
    clientIdTypeKey,
    howPartialAuthCreatedKey,
    invitationIdKey
  )

  // TODO Needs removing when we get the green light to remove legacy VAT code
  private val createRelationshipDetailsFieldsForMtdVat: Seq[String] = Seq(
    agentCodeKey,
    credIdKey,
    arnKey,
    serviceKey,
    "vrn",
    "oldAgentCodes",
    "ESRelationship",
    etmpRelationshipCreatedKey,
    enrolmentDelegatedKey,
    howRelationshipCreatedKey,
    "vrnExistsInEtmp"
  )

  private val checkCesaDetailsAndPartialAuthFields: Seq[String] = Seq(
    agentCodeKey,
    credIdKey,
    arnKey,
    saAgentRefKey,
    cesaRelationshipKey,
    ninoKey,
    "partialAuth"
  )

  private val checkEsDetailsFields: Seq[String] = Seq(
    agentCodeKey,
    credIdKey,
    "oldAgentCodes",
    "vrn",
    arnKey,
    "ESRelationship"
  )

  private val terminateRelationshipFields: Seq[String] = Seq(
    agentCodeKey,
    credIdKey,
    arnKey,
    serviceKey, // this can hold a number of different values for different services
    clientIdKey,
    clientIdTypeKey,
    etmpRelationshipRemovedKey, // true or false
    enrolmentDeallocatedKey, // true or false
    howRelationshipTerminatedKey
  )

  private val terminatePartialAuthFields: Seq[String] = Seq(
    arnKey,
    serviceKey,
    clientIdKey,
    clientIdTypeKey,
    howPartialAuthTerminatedKey
  )

  private val recoveryOfDeleteRelationshipDetailsFields: Seq[String] = Seq(
    "agentReferenceNumber",
    clientIdKey,
    clientIdTypeKey,
    serviceKey,
    "currentUserAffinityGroup",
    "authProviderId",
    "authProviderIdType",
    "initialDeleteDateTime",
    "numberOfAttempts",
    "abandonmentReason"
  )

  def sendCreateRelationshipAuditEvent()(implicit
    hc: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData,
    ec: ExecutionContext
  ): Future[Unit] =
    auditEvent(
      AgentClientRelationshipEvent.CreateRelationship,
      "create-relationship",
      collectDetails(auditData.getDetails, createRelationshipDetailsFields)
    )

  def sendCreatePartialAuthAuditEvent()(implicit
    hc: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData,
    ec: ExecutionContext
  ): Future[Unit] =
    auditEvent(
      AgentClientRelationshipEvent.CreatePartialAuthorisation,
      "create-partial-auth",
      collectDetails(auditData.getDetails, createPartialAuthDetailsFields)
    )

  // TODO Needs removing when we get the green light to remove legacy VAT code
  def sendCreateRelationshipAuditEventForMtdVat()(implicit
    hc: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData,
    ec: ExecutionContext
  ): Future[Unit] =
    auditEvent(
      AgentClientRelationshipEvent.CreateRelationship,
      "create-relationship",
      collectDetails(auditData.getDetails, createRelationshipDetailsFieldsForMtdVat)
    )

  def sendCheckCesaAndPartialAuthAuditEvent()(implicit
    hc: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData,
    ec: ExecutionContext
  ): Future[Unit] =
    auditEvent(
      AgentClientRelationshipEvent.CheckCESA,
      "check-cesa",
      collectDetails(auditData.getDetails, checkCesaDetailsAndPartialAuthFields)
    )

  def sendCheckEsAuditEvent()(implicit
    hc: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData,
    ec: ExecutionContext
  ): Future[Unit] =
    auditEvent(
      AgentClientRelationshipEvent.CheckES,
      "check-es",
      collectDetails(auditData.getDetails, checkEsDetailsFields)
    )

  def sendTerminateRelationshipAuditEvent()(implicit
    hc: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData,
    ec: ExecutionContext
  ): Future[Unit] =
    auditEvent(
      AgentClientRelationshipEvent.TerminateRelationship,
      "terminate-relationship",
      collectDetails(auditData.getDetails, terminateRelationshipFields)
    )

  def setAuditDataForTermination(arn: Arn, enrolmentKey: EnrolmentKey)(implicit
    auditData: AuditData,
    currentUser: CurrentUser
  ): AuditData = {
    if (!auditData.getDetails.contains(howRelationshipTerminatedKey)) {
      auditData.set(
        howRelationshipTerminatedKey,
        currentUser.affinityGroup match {
          case _ if currentUser.isStride                                         => hmrcLedTermination
          case Some(AffinityGroup.Individual) | Some(AffinityGroup.Organisation) => clientLedTermination
          case Some(AffinityGroup.Agent)                                         => agentLedTermination
          case _                                                                 => "unknown"
        }
      )
    }
    auditData.set(arnKey, arn.value)
    auditData.set(clientIdKey, enrolmentKey.oneIdentifier().value)
    auditData.set(clientIdTypeKey, enrolmentKey.oneIdentifier().key)
    auditData.set(serviceKey, enrolmentKey.service)
    auditData.set(enrolmentDeallocatedKey, false)
    auditData.set(etmpRelationshipRemovedKey, false)

    // Seems to be used by the RecoveryOfDeleteRelationshipHasBeenAbandoned audit
    auditData.set("currentUserAffinityGroup", currentUser.affinityGroup.map(_.toString).getOrElse("unknown"))
    auditData.set("authProviderId", currentUser.credentials.map(_.providerId).getOrElse("unknown"))
    auditData.set("authProviderIdType", currentUser.credentials.map(_.providerType).getOrElse("unknown"))
    auditData
  }

  def auditForPirTermination(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  )(implicit currentUser: CurrentUser, hc: HeaderCarrier, request: Request[_]): Future[Unit] = {
    implicit val auditData: AuditData = new AuditData()

    setAuditDataForTermination(arn, enrolmentKey)

    sendTerminateRelationshipAuditEvent()
  }

  def auditForAgentReplacement(
    arn: Arn,
    enrolmentKey: EnrolmentKey
  )(implicit hc: HeaderCarrier, request: Request[_]): Future[Unit] = {
    implicit val auditData: AuditData = new AuditData()
    implicit val currentUser: CurrentUser = CurrentUser(None, None)

    auditData.set(howRelationshipTerminatedKey, agentReplacement)
    setAuditDataForTermination(arn, enrolmentKey)
    auditData.set(enrolmentDeallocatedKey, true)
    auditData.set(etmpRelationshipRemovedKey, true)

    sendTerminateRelationshipAuditEvent()
  }

  def sendTerminatePartialAuthAuditEvent(
    arn: String,
    service: String,
    nino: String
  )(implicit
    // defaulted as it only needs to be predefined for special cases (agent replacement/role change)
    currentUser: CurrentUser = CurrentUser(None, None),
    hc: HeaderCarrier,
    request: Request[Any],
    // defaulted as it only needs to be predefined for special cases (agent replacement/role change)
    auditData: AuditData = new AuditData(),
    ec: ExecutionContext
  ): Future[Unit] = {
    if (!auditData.getDetails.contains(howPartialAuthTerminatedKey)) {
      auditData.set(
        howPartialAuthTerminatedKey,
        currentUser.affinityGroup match {
          case _ if currentUser.isStride                                         => hmrcLedTermination
          case Some(AffinityGroup.Individual) | Some(AffinityGroup.Organisation) => clientLedTermination
          case Some(AffinityGroup.Agent)                                         => agentLedTermination
          case _                                                                 => "unknown"
        }
      )
    }
    auditData.set(arnKey, arn)
    auditData.set(clientIdKey, nino)
    auditData.set(clientIdTypeKey, "nino")
    auditData.set(serviceKey, service)

    auditEvent(
      AgentClientRelationshipEvent.TerminatePartialAuthorisation,
      "terminate-partial-auth",
      collectDetails(auditData.getDetails, terminatePartialAuthFields)
    )
  }

  def sendRecoveryOfDeleteRelationshipHasBeenAbandonedAuditEvent()(implicit
    hc: HeaderCarrier,
    request: Request[Any],
    auditData: AuditData,
    ec: ExecutionContext
  ): Future[Unit] =
    auditEvent(
      AgentClientRelationshipEvent.RecoveryOfDeleteRelationshipHasBeenAbandoned,
      "recovery-of-delete-relationship-abandoned",
      collectDetails(auditData.getDetails, recoveryOfDeleteRelationshipDetailsFields)
    )

  private[audit] def auditEvent(
    event: AgentClientRelationshipEvent,
    transactionName: String,
    details: Seq[(String, Any)] = Seq.empty
  )(implicit hc: HeaderCarrier, request: Request[Any], ec: ExecutionContext): Future[Unit] =
    send(createEvent(event, transactionName, details: _*))

  private def createEvent(
    event: AgentClientRelationshipEvent,
    transactionName: String,
    details: (String, Any)*
  )(implicit hc: HeaderCarrier, request: Request[Any]): DataEvent = {

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

object AuditKeys {
  // Keys
  val agentCodeKey: String = "agentCode"
  val credIdKey: String = "credId"
  val arnKey: String = "agentReferenceNumber"
  val saAgentRefKey: String = "saAgentRef"
  val serviceKey: String = "service"
  val clientIdKey: String = "clientId"
  val suppliedClientIdKey: String = "suppliedClientId"
  val clientIdTypeKey: String = "clientIdType"
  val cesaRelationshipKey: String = "cesaRelationship"
  val etmpRelationshipCreatedKey: String = "etmpRelationshipCreated"
  val etmpRelationshipRemovedKey: String = "etmpRelationshipRemoved"
  val enrolmentDelegatedKey: String = "enrolmentDelegated"
  val enrolmentDeallocatedKey: String = "enrolmentDeallocated"
  val ninoKey: String = "nino"
  val howRelationshipCreatedKey: String = "howRelationshipCreated"
  val howRelationshipTerminatedKey: String = "howRelationshipTerminated"
  val howPartialAuthTerminatedKey: String = "howPartialAuthorisationTerminated"
  val howPartialAuthCreatedKey: String = "howPartialAuthorisationCreated"

  val invitationIdKey: String = "invitationId"

  // Create Relationship
  val copyExistingCesa: String = "CopyExistingCESARelationship"
  val clientAcceptedInvitation: String = "ClientAcceptedInvitation"
  val hmrcAcceptedInvitation: String = "HMRCAcceptedInvitation"
  val partialAuth: String = "PartialAuth"

  // Terminate Relationship
  val clientLedTermination: String = "ClientLedTermination"
  val hmrcLedTermination: String = "HMRCLedTermination"
  val agentLedTermination: String = "AgentLedTermination"
  val agentReplacement: String = "AgentReplacement"
  val agentRoleChange: String = "AgentRoleChanged"
}
