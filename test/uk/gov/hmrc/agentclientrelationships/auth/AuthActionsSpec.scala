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

package uk.gov.hmrc.agentclientrelationships.auth

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatestplus.play.OneAppPerSuite
import play.api.mvc.{Result, Results}
import play.api.test.FakeRequest
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.play.microservice.controller.BaseController
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.agentclientrelationships.controllers.ErrorResults.{NoAgentOrClient, NoPermissionOnAgencyOrClient}

import scala.concurrent.Future


class AuthActionsSpec extends UnitSpec with ResettingMockitoSugar with Results with OneAppPerSuite {

  lazy val mockAuthConnector = mock[PlayAuthConnector]

  lazy val arn = "TARN0000001"
  lazy val invalidArn = "Not Valid"

  lazy val mtdItId = "ABCDEFGH"
  lazy val invalidMtdItId = "Not Valid"

  val agentEnrolment = Enrolment("HMRC-AS-AGENT", Seq(EnrolmentIdentifier("AgentReferenceNumber", arn)), confidenceLevel = ConfidenceLevel.L200,
    state = "", delegatedAuthRule = None)

  val mtdItIdEnrolment = Enrolment("HMRC-MTD-IT", Seq(EnrolmentIdentifier("AgentReferenceNumber", mtdItId)), confidenceLevel = ConfidenceLevel.L200,
    state = "", delegatedAuthRule = None)

  val agentWithEnrolmentSet = Set(
    agentEnrolment, mtdItIdEnrolment
  )


  class TestAuth() extends AuthActions with BaseController {
    def testAuthActions() = AuthorisedAgent {
      implicit request =>
        implicit agent =>
          Future.successful(Ok)
    }
    override def authConnector: AuthConnector = mockAuthConnector
  }

  def mockAuth(affinityGroup: AffinityGroup = AffinityGroup.Agent, enrolment: Set[Enrolment]) = when(mockAuthConnector.authorise(any(), any[Retrieval[~[Enrolments, Option[AffinityGroup]]]]())(any()))
    .thenReturn(Future successful new ~[Enrolments, Option[AffinityGroup]](Enrolments(enrolment), Some(affinityGroup)))

  val fakeRequest = FakeRequest("GET", "/path")

  val testAuthImpl = new TestAuth

  "AuthorisedAgent" should {
    "should return Ok if Agent has valid Arn and MtdItId " in {
      mockAuth(enrolment = Set(
        agentEnrolment, mtdItIdEnrolment
      ))
      val result: Future[Result] = testAuthImpl.testAuthActions().apply(fakeRequest)
      await(result) shouldBe Ok
    }
    "should return NoAgentOrClient if AffinityGroup is an Individual" in {
      mockAuth(AffinityGroup.Individual, enrolment = Set(
        agentEnrolment, mtdItIdEnrolment
      ))
      val result: Future[Result] = testAuthImpl.testAuthActions().apply(fakeRequest)
      await(result) shouldBe NoAgentOrClient
    }
    "should return NoPermissionOnAgencyOrClient if it doesn't have the correct enrolment" in {
      mockAuth(enrolment = Set(
        agentEnrolment.copy(key = "In valid").copy(key = "Invalid")
      ))
      val result: Future[Result] = testAuthImpl.testAuthActions().apply(fakeRequest)
      await(result) shouldBe NoPermissionOnAgencyOrClient
    }
    "should return NoAgentOrClient if does not contain have both enrolment " in {
      mockAuth(AffinityGroup.Individual, enrolment = Set(
        agentEnrolment
      ))
      val result: Future[Result] = testAuthImpl.testAuthActions().apply(fakeRequest)
      await(result) shouldBe NoAgentOrClient
    }
    "should return NotFound if the arn is invalid " in {
      mockAuth(enrolment = Set(
        agentEnrolment.copy(identifiers = Seq(EnrolmentIdentifier("AgentReferenceNumber", invalidArn))), mtdItIdEnrolment
      ))
      val result: Future[Result] = testAuthImpl.testAuthActions().apply(fakeRequest)
      await(result) shouldBe NotFound
    }
    "should return NotFound if the mtdit is invalid " in {
      mockAuth(enrolment = Set(
        agentEnrolment, mtdItIdEnrolment.copy(identifiers = Seq(EnrolmentIdentifier("AgentReferenceNumber", invalidMtdItId)))
      ))
      val result: Future[Result] = testAuthImpl.testAuthActions().apply(fakeRequest)
      await(result) shouldBe NotFound
    }

    "should return NoAgentOrClient if the mtdit is invalid and the arn is invalid" in {
      mockAuth(enrolment = Set(
        agentEnrolment.copy(identifiers = Seq(EnrolmentIdentifier("AgentReferenceNumber", invalidArn))),
        mtdItIdEnrolment.copy(identifiers = Seq(EnrolmentIdentifier("AgentReferenceNumber", invalidMtdItId)))
      ))
      val result: Future[Result] = testAuthImpl.testAuthActions().apply(fakeRequest)
      await(result) shouldBe NoAgentOrClient
    }
  }
}
