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

package uk.gov.hmrc.agentclientrelationships.auth

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatestplus.play.OneAppPerSuite
import play.api.mvc.{Result, Results}
import play.api.test.FakeRequest
import uk.gov.hmrc.agentclientrelationships.controllers.ErrorResults.NoPermissionOnAgencyOrClient
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.auth.core.retrieve.{Retrieval, ~}
import uk.gov.hmrc.play.microservice.controller.BaseController
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.Future

class AuthActionsSpec extends UnitSpec with ResettingMockitoSugar with Results with OneAppPerSuite {

  lazy val mockAuthConnector = mock[AuthConnector]

  private lazy val arn = "TARN0000001"
  private lazy val mtdItId = "ABCDEFGH"

  private val agentEnrolment = Enrolment("HMRC-AS-AGENT", Seq(EnrolmentIdentifier("AgentReferenceNumber", arn)),
    state = "", delegatedAuthRule = None)

  private val mtdItIdEnrolment = Enrolment("HMRC-MTD-IT", Seq(EnrolmentIdentifier("MTDITID", mtdItId)),
    state = "", delegatedAuthRule = None)

  class TestAuth() extends AuthActions with BaseController {
    def testAuthActions() = AuthorisedAgent {
      implicit request =>
        implicit agent =>
          Future.successful(Ok)
    }

    override def authConnector: AuthConnector = mockAuthConnector
  }

  def mockAgentAuth(affinityGroup: AffinityGroup = AffinityGroup.Agent, enrolment: Set[Enrolment]) =
    when(mockAuthConnector.authorise(any(), any[Retrieval[~[Enrolments, Option[AffinityGroup]]]]())(any(), any()))
      .thenReturn(Future successful new ~[Enrolments, Option[AffinityGroup]](Enrolments(enrolment), Some(affinityGroup)))

  def mockClientAuth(affinityGroup: AffinityGroup = AffinityGroup.Individual, enrolment: Set[Enrolment]) =
    when(mockAuthConnector.authorise(any(), any[Retrieval[~[Enrolments, Option[AffinityGroup]]]]())(any(), any()))
      .thenReturn(Future successful new ~[Enrolments, Option[AffinityGroup]](Enrolments(enrolment), Some(affinityGroup)))

  val fakeRequest = FakeRequest("GET", "/path")

  val testAuthImpl = new TestAuth

  "AuthorisedAgent" should {
    "should return Ok if Agent has Arn" in {
      mockAgentAuth(enrolment = Set(
        agentEnrolment
      ))
      val result: Future[Result] = testAuthImpl.testAuthActions().apply(fakeRequest)
      await(result) shouldBe Ok
    }

    "should return Ok if Client has MtdItId " in {
      mockClientAuth(enrolment = Set(
        mtdItIdEnrolment
      ))
      val result: Future[Result] = testAuthImpl.testAuthActions().apply(fakeRequest)
      await(result) shouldBe Ok
    }

    "should return NoPermissionOnAgencyOrClient if it doesn't have the correct enrolment" in {
      mockAgentAuth(enrolment = Set(
        agentEnrolment.copy(key = "In valid").copy(key = "Invalid")
      ))
      val result: Future[Result] = testAuthImpl.testAuthActions().apply(fakeRequest)
      await(result) shouldBe NoPermissionOnAgencyOrClient
    }

    "should return NoPermissionOnAgencyOrClient for client's Auth with Agent's enrolments" in {
      mockClientAuth(AffinityGroup.Individual, enrolment = Set(
        agentEnrolment
      ))
      val result: Future[Result] = testAuthImpl.testAuthActions().apply(fakeRequest)
      await(result) shouldBe NoPermissionOnAgencyOrClient
    }

    "should return NoPermissionOnAgencyOrClient for Agent's Auth with Client's enrolments" in {
      mockAgentAuth(AffinityGroup.Agent, enrolment = Set(
        mtdItIdEnrolment
      ))
      val result: Future[Result] = testAuthImpl.testAuthActions().apply(fakeRequest)
      await(result) shouldBe NoPermissionOnAgencyOrClient
    }
  }
}
