/*
 * Copyright 2017 HM Revenue & Customs
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

import javax.inject.Inject

import com.google.inject.Singleton
import play.api.mvc.Request
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.connectors.AuthConnector
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.{AgentCode, Nino, SaAgentReference}
import uk.gov.hmrc.play.audit.AuditExtensions.auditHeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.DataEvent
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._

import scala.concurrent.Future
import scala.util.Try

object AgentClientRelationshipEvent extends Enumeration {
  val CopyRelationship = Value
  type AgentClientRelationshipEvent = Value
}

@Singleton
class AuditService @Inject()(val auditConnector: AuditConnector, val authConnector: AuthConnector) {

  def sendCopyRelationshipAuditEvent(arn: Arn,
                                     credentialIdentifier: String,
                                     agentCode: AgentCode,
                                     saAgentRef: Option[SaAgentReference],
                                     regime: String,
                                     regimeId: String,
                                     nino: Nino,
                                     CESARelationship: Boolean,
                                     etmpRelationshipCreated: Boolean,
                                     enrolmentDelegated: Boolean
                                    )
                                    (implicit hc: HeaderCarrier, request: Request[Any]): Unit = {
    auditEvent(AgentClientRelationshipEvent.CopyRelationship, "copy-relationship",
      Seq(
        "agentCode" -> agentCode.value,
        "credId" -> credentialIdentifier,
        "arn" -> arn.value,
        "saAgentRef" -> saAgentRef.map(_.value).getOrElse(""),
        "regime" -> regime,
        "regimeId" -> regimeId,
        "CESARelationship" -> CESARelationship,
        "etmpRelationshipCreated" -> etmpRelationshipCreated,
        "enrolmentDelegated" -> enrolmentDelegated,
        "nino" -> nino.value
      ))
  }

  private[audit] def auditEvent(event: AgentClientRelationshipEvent, transactionName: String, details: Seq[(String, Any)] = Seq.empty)
                               (implicit hc: HeaderCarrier, request: Request[Any]): Future[Unit] = {
    authConnector.currentAuthDetails() flatMap {
      case authDetailsOpt =>
        send(createEvent(event, transactionName, authDetailsOpt.flatMap(_.ggCredentialId), details: _*))
    }
  }

  private def createEvent(event: AgentClientRelationshipEvent, transactionName: String, authCredId: Option[String], details: (String, Any)*)
                         (implicit hc: HeaderCarrier, request: Request[Any]): DataEvent = {

    val detail = hc.toAuditDetails(details.map(pair => pair._1 -> pair._2.toString): _*) ++ authCredId.map(id => Map("authProviderId" -> id)).getOrElse(Map.empty)
    val tags = hc.toAuditTags(transactionName, request.path)
    DataEvent(auditSource = "agent-client-relationships",
      auditType = event.toString,
      tags = tags,
      detail = detail
    )
  }

  private def send(events: DataEvent*)(implicit hc: HeaderCarrier): Future[Unit] = {
    Future {
      events.foreach { event =>
        Try(auditConnector.sendEvent(event))
      }
    }
  }

}
