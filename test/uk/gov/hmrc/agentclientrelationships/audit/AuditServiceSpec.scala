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
import org.mockito.Mockito.{verify, _}
import org.scalatest.concurrent.Eventually
import org.scalatest.mock.MockitoSugar
import org.scalatest.time.{Millis, Span}
import play.api.test.FakeRequest
import uk.gov.hmrc.agentclientrelationships.connectors.{AuthConnector, AuthDetails}
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.{AgentCode, Nino, SaAgentReference}
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.{AuditEvent, DataEvent}
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.http.logging.{Authorization, RequestId, SessionId}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.{ExecutionContext, Future}

class AuditServiceSpec extends UnitSpec with MockitoSugar with Eventually {
  "auditEvent" should {
    "send an event with the correct fields" in {
      val mockConnector = mock[AuditConnector]
      val mockAuthConnector = mock[AuthConnector]
      val service = new AuditService(mockConnector, mockAuthConnector)

      when(mockAuthConnector.currentAuthDetails()(any(), any())).thenReturn(Future.successful(Some(AuthDetails(Some("testCredId")))))

      val hc = HeaderCarrier(
        authorization = Some(Authorization("dummy bearer token")),
        sessionId = Some(SessionId("dummy session id")),
        requestId = Some(RequestId("dummy request id"))
      )

      await(service.sendCopyRelationshipAuditEvent(
        arn = Arn("1234"),
        credentialIdentifier = "0000001234567890",
        agentCode = AgentCode("GG1234567890"),
        saAgentRef = Some(SaAgentReference("12313")),
        regime = "mtd-it",
        regimeId = "XX1234",
        nino = Nino("KS969148D"),
        CESARelationship = true,
        etmpRelationshipCreated = true,
        enrolmentDelegated = true
      )(
        hc,
        FakeRequest("GET", "/path"))
      )

      eventually {
        val captor = ArgumentCaptor.forClass(classOf[AuditEvent])
        verify(mockConnector).sendEvent(captor.capture())(any[HeaderCarrier], any[ExecutionContext])
        captor.getValue shouldBe an[DataEvent]
        val sentEvent = captor.getValue.asInstanceOf[DataEvent]

        sentEvent.auditType shouldBe "CopyRelationship"
        sentEvent.auditSource shouldBe "agent-client-relationships"
        sentEvent.detail("arn") shouldBe "1234"
        sentEvent.detail("agentCode") shouldBe "GG1234567890"
        sentEvent.detail("saAgentRef") shouldBe "12313"
        sentEvent.detail("credId") shouldBe "0000001234567890"
        sentEvent.detail("nino") shouldBe "KS969148D"
        sentEvent.detail("CESARelationship") shouldBe "true"
        sentEvent.detail("etmpRelationshipCreated") shouldBe "true"
        sentEvent.detail("enrolmentDelegated") shouldBe "true"

        sentEvent.tags.contains("Authorization") shouldBe false
        sentEvent.detail("Authorization") shouldBe "dummy bearer token"

        sentEvent.tags("transactionName") shouldBe "copy-relationship"
        sentEvent.tags("path") shouldBe "/path"
        sentEvent.tags("X-Session-ID") shouldBe "dummy session id"
        sentEvent.tags("X-Request-ID") shouldBe "dummy request id"
      }(PatienceConfig(
        timeout = scaled(Span(500, Millis)),
        interval = scaled(Span(200, Millis))))
    }
  }

}
