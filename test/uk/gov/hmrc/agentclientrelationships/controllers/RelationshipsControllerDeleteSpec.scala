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

package uk.gov.hmrc.agentclientrelationships.controllers

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import play.api.libs.json.Json
import play.api.mvc.Results
import play.api.test.FakeRequest
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.connectors.RelationshipNotFound
import uk.gov.hmrc.agentclientrelationships.services.RelationshipsService
import uk.gov.hmrc.agentclientrelationships.support.{PermissiveAuthActions, ResettingMockitoSugar}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.auth.core.{AuthConnector, Predicate, Retrieval}
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.Future

class RelationshipsControllerDeleteSpec extends UnitSpec with ResettingMockitoSugar with Results {

  val arn = Arn("AARN0000002")
  val mtdItId = MtdItId("1234567890123456")

  val service = resettingMock[RelationshipsService]
  val authConnector = new AuthConnector {
    override def authorise[A](predicate: Predicate, retrieval: Retrieval[A])(implicit hc: HeaderCarrier) = ???
  }
  val controller = new Relationships(authConnector, service) with PermissiveAuthActions {
    override val taxIdentifier = arn
  }

  "delete" should {
    "return NotFound when the service throws a RelationshipNotFound" in {
      when(service.getAgentCodeFor(any[Arn])(any[HeaderCarrier], any[AuditData]))
        .thenReturn(Future failed new RelationshipNotFound("NOT_FOUND_CODE"))

      val result = await(controller.delete(arn, mtdItId)(FakeRequest()))
      result shouldBe NotFound(Json.obj("code" -> "NOT_FOUND_CODE"))
    }

    "propagate other exceptions" in {
      when(service.getAgentCodeFor(any[Arn])(any[HeaderCarrier], any[AuditData]))
        .thenReturn(Future failed new IllegalArgumentException("other exception"))

      intercept[IllegalArgumentException] {
        await(controller.delete(arn, mtdItId)(FakeRequest()))
      }.getMessage shouldBe "other exception"
    }
  }
}
