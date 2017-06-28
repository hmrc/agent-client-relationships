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

import org.mockito.ArgumentCaptor
import org.mockito.Matchers._
import org.mockito.Mockito.verify
import org.scalatest.concurrent.Eventually
import org.scalatest.mock.MockitoSugar
import org.scalatest.time.{Millis, Span}
import play.api.test.FakeRequest
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.{AgentCode, Nino}
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.{AuditEvent, DataEvent}
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.http.logging.{Authorization, RequestId, SessionId}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext

class AuditServiceSpec extends UnitSpec with MockitoSugar with Eventually {
  "auditEvent" should {
    "send an CreateRelationship event with the correct fields" in {
      val mockConnector = mock[AuditConnector]
      val service = new AuditService(mockConnector)


      val hc = HeaderCarrier(
        authorization = Some(Authorization("dummy bearer token")),
        sessionId = Some(SessionId("dummy session id")),
        requestId = Some(RequestId("dummy request id"))
      )

      val auditData = new AuditData()
      auditData.set("arn",Arn("1234").value)
      auditData.set("credId", "0000001234567890")
      auditData.set("agentCode",AgentCode("GG1234567890").value)
      auditData.set("saAgentRef", "12313")
      auditData.set("regime","mtd-it")
      auditData.set("regimeId","XX1234")
      auditData.set("nino",Nino("KS969148D").value)
      auditData.set("CESARelationship", true)
      auditData.set("etmpRelationshipCreated",true)
      auditData.set("enrolmentDelegated",true)
      auditData.set("Journey", "CopyExistingCESARelationship")
      auditData.set("AgentDBRecord",true)

      await(service.sendCreateRelationshipAuditEvent(
        hc,
        FakeRequest("GET", "/path"),
        auditData)
      )

      eventually {
        val captor = ArgumentCaptor.forClass(classOf[AuditEvent])
        verify(mockConnector).sendEvent(captor.capture())(any[HeaderCarrier], any[ExecutionContext])
        captor.getValue shouldBe an[DataEvent]
        val sentEvent = captor.getValue.asInstanceOf[DataEvent]

        sentEvent.auditType shouldBe "CreateRelationship"
        sentEvent.auditSource shouldBe "agent-client-relationships"
        sentEvent.detail("arn") shouldBe "1234"
        sentEvent.detail("agentCode") shouldBe "GG1234567890"
        sentEvent.detail("saAgentRef") shouldBe "12313"
        sentEvent.detail("credId") shouldBe "0000001234567890"
        sentEvent.detail("nino") shouldBe "KS969148D"
        sentEvent.detail("CESARelationship") shouldBe "true"
        sentEvent.detail("etmpRelationshipCreated") shouldBe "true"
        sentEvent.detail("enrolmentDelegated") shouldBe "true"
        sentEvent.detail("Journey") shouldBe "CopyExistingCESARelationship"
        sentEvent.detail("AgentDBRecord") shouldBe "true"

        sentEvent.tags.contains("Authorization") shouldBe false
        sentEvent.detail("Authorization") shouldBe "dummy bearer token"

        sentEvent.tags("transactionName") shouldBe "create-relationship"
        sentEvent.tags("path") shouldBe "/path"
        sentEvent.tags("X-Session-ID") shouldBe "dummy session id"
        sentEvent.tags("X-Request-ID") shouldBe "dummy request id"
      }(PatienceConfig(
        timeout = scaled(Span(500, Millis)),
        interval = scaled(Span(200, Millis))))
    }

    "send an CheckCESA event with the correct fields" in {
      val mockConnector = mock[AuditConnector]
      val service = new AuditService(mockConnector)


      val hc = HeaderCarrier(
        authorization = Some(Authorization("dummy bearer token")),
        sessionId = Some(SessionId("dummy session id")),
        requestId = Some(RequestId("dummy request id"))
      )

      val auditData = new AuditData()
      auditData.set("arn",Arn("1234").value)
      auditData.set("credId", "0000001234567890")
      auditData.set("agentCode",AgentCode("GG1234567890").value)
      auditData.set("saAgentRef", "12313")
      auditData.set("nino",Nino("KS969148D").value)
      auditData.set("CESARelationship", true)


      await(service.sendCheckCESAAuditEvent(
        hc,
        FakeRequest("GET", "/path"),
        auditData)
      )

      eventually {
        val captor = ArgumentCaptor.forClass(classOf[AuditEvent])
        verify(mockConnector).sendEvent(captor.capture())(any[HeaderCarrier], any[ExecutionContext])
        captor.getValue shouldBe an[DataEvent]
        val sentEvent = captor.getValue.asInstanceOf[DataEvent]

        sentEvent.auditType shouldBe "CheckCESA"
        sentEvent.auditSource shouldBe "agent-client-relationships"
        sentEvent.detail("arn") shouldBe "1234"
        sentEvent.detail("agentCode") shouldBe "GG1234567890"
        sentEvent.detail("saAgentRef") shouldBe "12313"
        sentEvent.detail("credId") shouldBe "0000001234567890"
        sentEvent.detail("nino") shouldBe "KS969148D"
        sentEvent.detail("CESARelationship") shouldBe "true"

        sentEvent.tags.contains("Authorization") shouldBe false
        sentEvent.detail("Authorization") shouldBe "dummy bearer token"

        sentEvent.tags("transactionName") shouldBe "check-cesa"
        sentEvent.tags("path") shouldBe "/path"
        sentEvent.tags("X-Session-ID") shouldBe "dummy session id"
        sentEvent.tags("X-Request-ID") shouldBe "dummy request id"
      }(PatienceConfig(
        timeout = scaled(Span(500, Millis)),
        interval = scaled(Span(200, Millis))))
    }
  }

}
