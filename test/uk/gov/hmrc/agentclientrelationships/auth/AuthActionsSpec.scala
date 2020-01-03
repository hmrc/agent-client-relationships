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

package uk.gov.hmrc.agentclientrelationships.auth

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.mockito.stubbing.OngoingStubbing
import play.api.mvc.{Result, Results}
import play.api.test.FakeRequest
import uk.gov.hmrc.agentclientrelationships.controllers.ErrorResults.NoPermissionToPerformOperation
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Utr, Vrn}
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.auth.core.authorise.Predicate
import uk.gov.hmrc.auth.core.retrieve.{Credentials, Retrieval, ~}
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.controller.BaseController
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.{ExecutionContext, Future}

class AuthActionsSpec extends UnitSpec with ResettingMockitoSugar with Results {

  lazy val mockAuthConnector = mock[AuthConnector]
  implicit val ec: ExecutionContext = ExecutionContext.Implicits.global

  private val arn = "TARN0000001"
  private val mtdItId = "ABCDEFGH"
  private val vrn = "101747641"
  val utr = "3087612352"

  private val oldRequiredStrideRole = "REQUIRED STRIDE ROLE"
  private val newRequiredStrideRole = "REQUIRED_STRIDE_ROLE"

  private val strideRoles = Seq(oldRequiredStrideRole, newRequiredStrideRole)

  private val agentEnrolment = Enrolment(
    "HMRC-AS-AGENT",
    Seq(EnrolmentIdentifier("AgentReferenceNumber", arn)),
    state = "",
    delegatedAuthRule = None)

  private val mtdItIdEnrolment =
    Enrolment("HMRC-MTD-IT", Seq(EnrolmentIdentifier("MTDITID", mtdItId)), state = "", delegatedAuthRule = None)

  private val mtdVatIdEnrolment =
    Enrolment("HMRC-MTD-VAT", Seq(EnrolmentIdentifier("VRN", vrn)), state = "", delegatedAuthRule = None)

  private val trustEnrolment =
    Enrolment("HMRC-TERS-ORG", Seq(EnrolmentIdentifier("SAUTR", utr)), state = "", delegatedAuthRule = None)

  class TestAuth() extends AuthActions with BaseController {
    def testAuthActions(arn: Arn, identifier: TaxIdentifier, strideRoles: Seq[String]) =
      authorisedClientOrStrideUser(identifier, strideRoles) { implicit request => _ =>
        Future.successful(Ok)
      }

    def testAuthorisedAsClient(service: String) = AuthorisedAsClient(service) { implicit request => _ =>
      Future.successful(Ok)
    }

    override def authConnector: AuthConnector = mockAuthConnector
  }

  def mockAgentAuth(
    affinityGroup: AffinityGroup = AffinityGroup.Agent,
    enrolment: Set[Enrolment],
    credentials: Credentials = Credentials("12345-GGUserId", "GovernmentGateway")): OngoingStubbing[Future[Enrolments ~
    Option[AffinityGroup] ~
    Option[Credentials]]] =
    when(
      mockAuthConnector
        .authorise(any[Predicate](), any[Retrieval[Enrolments ~ Option[AffinityGroup] ~ Option[Credentials]]]())(
          any[HeaderCarrier](),
          any[ExecutionContext]()))
      .thenReturn(Future successful new ~(new ~(Enrolments(enrolment), Some(affinityGroup)), Some(credentials)))

  def mockClientAuth(
    affinityGroup: AffinityGroup = AffinityGroup.Individual,
    enrolment: Set[Enrolment],
    credentials: Credentials = Credentials("12345-GGUserId", "GovernmentGateway")): OngoingStubbing[Future[Enrolments ~
    Option[AffinityGroup] ~
    Option[Credentials]]] =
    when(
      mockAuthConnector
        .authorise(any[Predicate](), any[Retrieval[Enrolments ~ Option[AffinityGroup] ~ Option[Credentials]]]())(
          any[HeaderCarrier](),
          any[ExecutionContext]()))
      .thenReturn(Future successful new ~(new ~(Enrolments(enrolment), Some(affinityGroup)), Some(credentials)))

  def mockStrideAuth(
    strideRole: String,
    credentials: Credentials = Credentials("someStrideUser", "PrivilegedApplication"))
    : OngoingStubbing[Future[Enrolments ~
      Option[AffinityGroup] ~
      Option[Credentials]]] =
    when(
      mockAuthConnector
        .authorise(any[Predicate](), any[Retrieval[Enrolments ~ Option[AffinityGroup] ~ Option[Credentials]]]())(
          any[HeaderCarrier](),
          any[ExecutionContext]()))
      .thenReturn(
        Future successful new ~(
          new ~(Enrolments(Set(Enrolment(strideRole, Seq.empty, "Activated"))), None),
          Some(credentials)))

  def mockClientAuthWithoutCredRetrieval(
    affinityGroup: AffinityGroup = AffinityGroup.Individual,
    enrolment: Set[Enrolment]): OngoingStubbing[Future[Enrolments ~ Option[AffinityGroup]]] =
    when(
      mockAuthConnector.authorise(any[Predicate](), any[Retrieval[Enrolments ~ Option[AffinityGroup]]]())(
        any[HeaderCarrier](),
        any[ExecutionContext]()))
      .thenReturn(Future successful new ~(Enrolments(enrolment), Some(affinityGroup)))

  val fakeRequest = FakeRequest("GET", "/path")

  val testAuthImpl = new TestAuth

  "AuthorisedClientOrStrideUser" when {

    "auth provider is GovernmentGateway and if the user is a client (not stride)" should {
      "return Ok if Client has matching MtdItId in their enrolments" in {
        mockClientAuth(enrolment = Set(mtdItIdEnrolment))
        val result: Future[Result] =
          testAuthImpl.testAuthActions(Arn(arn), MtdItId(mtdItId), strideRoles).apply(fakeRequest)
        await(result) shouldBe Ok
      }

      "return Ok if Client has matching Vrn in their enrolments" in {
        mockClientAuth(enrolment = Set(mtdVatIdEnrolment))
        val result: Future[Result] =
          testAuthImpl.testAuthActions(Arn(arn), Vrn(vrn), strideRoles).apply(fakeRequest)
        await(result) shouldBe Ok
      }

      "return NoPermissionToPerformOperation if Client has a non-matching MtdItId in their enrolments" in {
        mockClientAuth(enrolment = Set(mtdItIdEnrolment))
        val result: Future[Result] =
          testAuthImpl.testAuthActions(Arn(arn), MtdItId("NON_MATCHING"), strideRoles).apply(fakeRequest)
        await(result) shouldBe NoPermissionToPerformOperation
      }

      "return NoPermissionToPerformOperation if Client has a non-matching Vrn in their enrolments" in {
        mockClientAuth(enrolment = Set(mtdVatIdEnrolment))
        val result: Future[Result] =
          testAuthImpl.testAuthActions(Arn(arn), Vrn("NON_MATCHING"), strideRoles).apply(fakeRequest)
        await(result) shouldBe NoPermissionToPerformOperation
      }

      "return NoPermissionToPerformOperation if Client has only an enrolment with a different identifier type" in {
        mockClientAuth(enrolment = Set(mtdVatIdEnrolment))
        val result: Future[Result] =
          testAuthImpl.testAuthActions(Arn(arn), MtdItId(mtdItId), strideRoles).apply(fakeRequest)
        await(result) shouldBe NoPermissionToPerformOperation
      }

      "return Ok if Client has matching Utr in their enrolments" in {
        mockClientAuth(enrolment = Set(trustEnrolment))
        val result: Future[Result] = testAuthImpl.testAuthActions(Arn(arn), Utr(utr), strideRoles).apply(fakeRequest)
        await(result) shouldBe Ok
      }
    }

    "auth provider is PrivilegedApplication" should {
      "return Ok if Stride user has required role" in {
        mockStrideAuth(oldRequiredStrideRole)
        val result: Future[Result] =
          testAuthImpl.testAuthActions(Arn(arn), MtdItId(mtdItId), strideRoles).apply(fakeRequest)
        await(result) shouldBe Ok
      }

      "return Ok if Stride user has new required role" in {
        mockStrideAuth(newRequiredStrideRole)
        val result: Future[Result] =
          testAuthImpl.testAuthActions(Arn(arn), MtdItId(mtdItId), strideRoles).apply(fakeRequest)
        await(result) shouldBe Ok
      }

      "return NoPermissionToPerformOperation if Stride user has unsupported role" in {
        mockStrideAuth("foo")
        val result: Future[Result] =
          testAuthImpl.testAuthActions(Arn(arn), MtdItId(mtdItId), strideRoles).apply(fakeRequest)
        await(result) shouldBe NoPermissionToPerformOperation
      }
    }
  }

  "AuthorisedAsClient" should {
    "return Ok if client has HMRC-MTD-IT enrolment" in {
      mockClientAuthWithoutCredRetrieval(enrolment = Set(mtdItIdEnrolment))
      val result = testAuthImpl.testAuthorisedAsClient("HMRC-MTD-IT").apply(fakeRequest)
      await(result) shouldBe Ok
    }

    "return Forbidden if client has other enrolment" in {
      mockClientAuthWithoutCredRetrieval(enrolment = Set(mtdVatIdEnrolment))
      val result = testAuthImpl.testAuthorisedAsClient("HMRC-AGENT-AGENT").apply(fakeRequest)
      await(result) shouldBe NoPermissionToPerformOperation
    }
  }

  "hasRequiredStrideRole" should {
    "return true if enrolments contains required stride role" in {
      testAuthImpl
        .hasRequiredStrideRole(Enrolments(Set(new Enrolment("FOO", Seq.empty, "", None))), Seq("FOO", "BAR")) shouldBe true
      testAuthImpl.hasRequiredStrideRole(
        Enrolments(
          Set(
            new Enrolment("BOO", Seq.empty, "", None),
            new Enrolment("FOO", Seq.empty, "", None),
            new Enrolment("WOO", Seq.empty, "", None))),
        Seq("FOO", "BAR")) shouldBe true
    }

    "return false if enrolments does not contain required stride role" in {
      testAuthImpl.hasRequiredStrideRole(
        Enrolments(Set(new Enrolment("woo", Seq.empty, "", None), new Enrolment("boo", Seq.empty, "", None))),
        Seq("foo", "bar")) shouldBe false
      testAuthImpl.hasRequiredStrideRole(Enrolments(Set.empty), Seq("foo", "bar")) shouldBe false
    }

  }
}
