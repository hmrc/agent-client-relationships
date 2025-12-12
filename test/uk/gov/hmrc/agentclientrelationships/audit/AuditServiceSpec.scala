/*
 * Copyright 2025 HM Revenue & Customs
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

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.verify
import org.scalatest.concurrent.Eventually
import org.scalatest.time.Millis
import org.scalatest.time.Span
import org.scalatestplus.mockito.MockitoSugar
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HeaderNames
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.DataEvent

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global

class AuditServiceSpec
extends UnitSpec
with MockitoSugar
with Eventually {

  implicit val patience: PatienceConfig = PatienceConfig(
    timeout = scaled(Span(500, Millis)),
    interval = scaled(Span(200, Millis))
  )

  val request: RequestHeader = FakeRequest("GET", "/path").withHeaders(
    HeaderNames.xSessionId -> "dummy session id",
    HeaderNames.xRequestId -> "dummy request id",
    HeaderNames.authorisation -> "dummy auth"
  )

  "auditEvent" should {
    "send an CreateRelationship event with the correct fields" in {
      val mockConnector = mock[AuditConnector]
      val service = new AuditService(mockConnector)

      val auditData = new AuditData()
      auditData.set("agentReferenceNumber", Arn("1234").value)
      auditData.set("credId", "0000001234567890")
      auditData.set("agentCode", AgentCode("ES1234567890").value)
      auditData.set("saAgentRef", "12313")
      auditData.set("service", "mtd-it")
      auditData.set("clientId", "XX1234")
      auditData.set("clientIdType", "ni")
      auditData.set("nino", NinoWithoutSuffix("KS969148D").value)
      auditData.set("cesaRelationship", true)
      auditData.set("etmpRelationshipCreated", true)
      auditData.set("enrolmentDelegated", true)
      auditData.set("howRelationshipCreated", "CopyExistingCESARelationship")

      await(service.sendCreateRelationshipAuditEvent()(request, auditData))

      eventually {
        val captor = ArgumentCaptor.forClass(classOf[DataEvent])
        verify(mockConnector).sendEvent(captor.capture())(any[HeaderCarrier], any[ExecutionContext])
        captor.getValue.asInstanceOf[DataEvent] shouldBe an[DataEvent]
        val sentEvent = captor.getValue.asInstanceOf[DataEvent]

        sentEvent.auditType shouldBe "CreateRelationship"
        sentEvent.auditSource shouldBe "agent-client-relationships"
        sentEvent.detail("agentReferenceNumber") shouldBe "1234"
        sentEvent.detail("agentCode") shouldBe "ES1234567890"
        sentEvent.detail("saAgentRef") shouldBe "12313"
        sentEvent.detail("credId") shouldBe "0000001234567890"
        sentEvent.detail("service") shouldBe "mtd-it"
        sentEvent.detail("clientId") shouldBe "XX1234"
        sentEvent.detail("clientIdType") shouldBe "ni"
        sentEvent.detail("nino") shouldBe "KS969148"
        sentEvent.detail("cesaRelationship") shouldBe "true"
        sentEvent.detail("etmpRelationshipCreated") shouldBe "true"
        sentEvent.detail("enrolmentDelegated") shouldBe "true"
        sentEvent.detail("howRelationshipCreated") shouldBe "CopyExistingCESARelationship"

        sentEvent.tags.contains("Authorization") shouldBe false

        sentEvent.tags("transactionName") shouldBe "create-relationship"
        sentEvent.tags("path") shouldBe "/path"
        sentEvent.tags("X-Session-ID") shouldBe "dummy session id"
        sentEvent.tags("X-Request-ID") shouldBe "dummy request id"
      }
    }

    "send an CheckCESA event with the correct fields" in {
      val mockConnector = mock[AuditConnector]
      val service = new AuditService(mockConnector)

      val auditData = new AuditData()
      auditData.set("agentReferenceNumber", Arn("1234").value)
      auditData.set("credId", "0000001234567890")
      auditData.set("agentCode", AgentCode("ES1234567890").value)
      auditData.set("saAgentRef", "12313")
      auditData.set("nino", NinoWithoutSuffix("KS969148D").value)
      println(NinoWithoutSuffix("KS969148").value)
      auditData.set("cesaRelationship", true)

      await(service.sendCheckCesaAndPartialAuthAuditEvent()(request, auditData))

      eventually {
        val captor = ArgumentCaptor.forClass(classOf[DataEvent])
        verify(mockConnector).sendEvent(captor.capture())(any[HeaderCarrier], any[ExecutionContext])
        captor.getValue.asInstanceOf[DataEvent] shouldBe an[DataEvent]
        val sentEvent = captor.getValue.asInstanceOf[DataEvent]

        sentEvent.auditType shouldBe "CheckCESA"
        sentEvent.auditSource shouldBe "agent-client-relationships"
        sentEvent.detail("agentReferenceNumber") shouldBe "1234"
        sentEvent.detail("agentCode") shouldBe "ES1234567890"
        sentEvent.detail("saAgentRef") shouldBe "12313"
        sentEvent.detail("credId") shouldBe "0000001234567890"
        sentEvent.detail("nino") shouldBe "KS969148"
        sentEvent.detail("cesaRelationship") shouldBe "true"

        sentEvent.tags.contains("Authorization") shouldBe false

        sentEvent.tags("transactionName") shouldBe "check-cesa"
        sentEvent.tags("path") shouldBe "/path"
        sentEvent.tags("X-Session-ID") shouldBe "dummy session id"
        sentEvent.tags("X-Request-ID") shouldBe "dummy request id"
      }
    }
  }

}
