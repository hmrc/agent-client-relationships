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
import uk.gov.hmrc.agentclientrelationships.controllers.ErrorResults.{NoPermissionOnAgencyOrClient, NoPermissionOnClient}
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.auth.core.retrieve.{Retrieval, ~}
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.play.microservice.controller.BaseController
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.Future

class AuthActionsSpec extends UnitSpec with ResettingMockitoSugar with Results with OneAppPerSuite {

  lazy val mockAuthConnector = mock[AuthConnector]

  private lazy val arn = "TARN0000001"
  private lazy val mtdItId = "ABCDEFGH"
  private lazy val vrn = "101747641"

  private val agentEnrolment = Enrolment("HMRC-AS-AGENT", Seq(EnrolmentIdentifier("AgentReferenceNumber", arn)),
    state = "", delegatedAuthRule = None)

  private val mtdItIdEnrolment = Enrolment("HMRC-MTD-IT", Seq(EnrolmentIdentifier("MTDITID", mtdItId)),
    state = "", delegatedAuthRule = None)

  private val mtdVatIdEnrolment = Enrolment("HMRC-MTD-VAT", Seq(EnrolmentIdentifier("VRN", vrn)),
    state = "", delegatedAuthRule = None)

  class TestAuth() extends AuthActions with BaseController {
    def testAuthActions(arn: Arn, identifier: TaxIdentifier) = AuthorisedAgentOrClient(arn, identifier) {
      implicit request =>
        Future.successful(Ok)
    }

    def testAuthorisedAsClient = AuthorisedAsItsaClient { implicit request =>clientId =>
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

  "AuthorisedAgentOrClient" when {
    "affinity group is Agent" should {
      "return Ok if Agent has matching Arn in its enrolments" in {
        mockAgentAuth(enrolment = Set(agentEnrolment))
        val result: Future[Result] = testAuthImpl.testAuthActions(Arn(arn), MtdItId(mtdItId)).apply(fakeRequest)
        await(result) shouldBe Ok
      }

      "return NoPermissionOnAgencyOrClient if Agent doesn't have the correct enrolment" in {
        mockAgentAuth(enrolment = Set(agentEnrolment.copy(key = "NOT_CORRECT_ENROLMENT")))
        val result: Future[Result] = testAuthImpl.testAuthActions(Arn(arn), MtdItId(mtdItId)).apply(fakeRequest)
        await(result) shouldBe NoPermissionOnAgencyOrClient
      }

      "return NoPermissionOnAgencyOrClient if Agent has a different Arn and different client identifier" in {
        mockAgentAuth(enrolment = Set(agentEnrolment))
        val result: Future[Result] = testAuthImpl.testAuthActions(Arn("NON_MATCHING"), MtdItId("NON_MATCHING")).apply(fakeRequest)
        await(result) shouldBe NoPermissionOnAgencyOrClient
      }

      "return NoPermissionOnAgencyOrClient if Agent has a different Arn but a matching client identifier" in {
        mockAgentAuth(enrolment = Set(agentEnrolment, mtdItIdEnrolment))
        val result: Future[Result] = testAuthImpl.testAuthActions(Arn("NON_MATCHING"), MtdItId(mtdItId)).apply(fakeRequest)
        await(result) shouldBe NoPermissionOnAgencyOrClient
      }

      "return NoPermissionOnAgencyOrClient if Agent has only a matching client identifier" in {
        mockAgentAuth(AffinityGroup.Agent, enrolment = Set(mtdItIdEnrolment))
        val result: Future[Result] = testAuthImpl.testAuthActions(Arn(arn), MtdItId(mtdItId)).apply(fakeRequest)
        await(result) shouldBe NoPermissionOnAgencyOrClient
      }

      "return NoPermissionOnAgencyOrClient if Agent has only an enrolment with a different identifier type" in {
        mockAgentAuth(AffinityGroup.Agent, enrolment = Set(mtdVatIdEnrolment))
        val result: Future[Result] = testAuthImpl.testAuthActions(Arn(arn), MtdItId(mtdItId)).apply(fakeRequest)
        await(result) shouldBe NoPermissionOnAgencyOrClient
      }
    }

    "affinity group is not Agent" should {
      "return Ok if Client has matching MtdItId in their enrolments" in {
        mockClientAuth(enrolment = Set(mtdItIdEnrolment))
        val result: Future[Result] = testAuthImpl.testAuthActions(Arn(arn), MtdItId(mtdItId)).apply(fakeRequest)
        await(result) shouldBe Ok
      }

      "return Ok if Client has matching Vrn in their enrolments" in {
        mockClientAuth(enrolment = Set(mtdVatIdEnrolment))
        val result: Future[Result] = testAuthImpl.testAuthActions(Arn(arn), Vrn(vrn)).apply(fakeRequest)
        await(result) shouldBe Ok
      }

      "return NoPermissionOnAgencyOrClient if Client has a non-matching MtdItId in their enrolments" in {
        mockClientAuth(enrolment = Set(mtdItIdEnrolment))
        val result: Future[Result] = testAuthImpl.testAuthActions(Arn(arn), MtdItId("NON_MATCHING")).apply(fakeRequest)
        await(result) shouldBe NoPermissionOnAgencyOrClient
      }

      "return NoPermissionOnAgencyOrClient if Client has a non-matching Vrn in their enrolments" in {
        mockClientAuth(enrolment = Set(mtdVatIdEnrolment))
        val result: Future[Result] = testAuthImpl.testAuthActions(Arn(arn), Vrn("NON_MATCHING")).apply(fakeRequest)
        await(result) shouldBe NoPermissionOnAgencyOrClient
      }

      "return NoPermissionOnAgencyOrClient if Client has only an enrolment with a different identifier type" in {
        mockClientAuth(enrolment = Set(mtdVatIdEnrolment))
        val result: Future[Result] = testAuthImpl.testAuthActions(Arn(arn), MtdItId(mtdItId)).apply(fakeRequest)
        await(result) shouldBe NoPermissionOnAgencyOrClient
      }
    }
  }

  "AuthorisedAgentOrClient" should {
    "return Ok if client has HMRC-MTD-IT enrolment" in {
      mockClientAuth(enrolment = Set(mtdItIdEnrolment))
      val result = testAuthImpl.testAuthorisedAsClient.apply(fakeRequest)
      await(result) shouldBe Ok
    }

    "return Forbidden if client has other enrolment" in {
      mockClientAuth(enrolment = Set(mtdVatIdEnrolment))
      val result = testAuthImpl.testAuthorisedAsClient.apply(fakeRequest)
      await(result) shouldBe NoPermissionOnClient
    }
  }
}
