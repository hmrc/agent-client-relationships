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

package uk.gov.hmrc.agentclientrelationships.services

import org.mockito.ArgumentMatchers.{eq => eqs, _}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.mockito.MockitoSugar
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.agentclientrelationships.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.agentclientrelationships.model.{EnrolmentKey, Invitation}
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{MtdIt, MtdItSupp, PersonalIncomeRecord}
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, Future}

class FriendlyNameServiceSpec extends UnitSpec with MockitoSugar with BeforeAndAfterEach {
  val mockEsp: EnrolmentStoreProxyConnector = mock[EnrolmentStoreProxyConnector]

  object testService extends FriendlyNameService(mockEsp)

  implicit val hc: HeaderCarrier = HeaderCarrier()
  implicit val ec: ExecutionContext = ExecutionContext.Implicits.global

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    reset(mockEsp)
  }

  val testInvitation: Invitation = Invitation.createNew(
    arn = "testArn",
    service = MtdIt,
    clientId = MtdItId("ABCDEF123456789"),
    suppliedClientId = Nino("AB123456A"),
    clientName = "test Name",
    agencyName = "AgentName",
    agencyEmail = "agent@email.com",
    expiryDate = LocalDate.now(),
    clientType = Some("personal")
  )
  val testEnrolment: EnrolmentKey = EnrolmentKey(
    MtdIt,
    MtdItId("ABCDEF123456789")
  )
  val testGroupId = "testGroupId"
  val encodedName = "test+Name"

  "updateFriendlyName" should {
    "succeed when there group id is found and ES19 call succeeds" in {
      when(mockEsp.getPrincipalGroupIdFor(any[Arn])(any[HeaderCarrier]))
        .thenReturn(Future.successful(testGroupId))
      when(mockEsp.updateEnrolmentFriendlyName(any[String], any[String], any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(()))

      await(testService.updateFriendlyName(testInvitation, testEnrolment)) shouldBe ()

      verify(mockEsp, times(1))
        .updateEnrolmentFriendlyName(eqs(testGroupId), eqs(testEnrolment.toString), eqs(encodedName))(
          any[HeaderCarrier]
        )
    }
    "not fail in case of an ES19 error" in {
      when(mockEsp.getPrincipalGroupIdFor(any[Arn])(any[HeaderCarrier]))
        .thenReturn(Future.successful(testGroupId))
      when(mockEsp.updateEnrolmentFriendlyName(any[String], any[String], any[String])(any[HeaderCarrier]))
        .thenReturn(Future.failed(UpstreamErrorResponse("error", 503)))

      await(testService.updateFriendlyName(testInvitation, testEnrolment)) shouldBe ()

      verify(mockEsp, times(1))
        .updateEnrolmentFriendlyName(eqs(testGroupId), eqs(testEnrolment.toString), eqs(encodedName))(
          any[HeaderCarrier]
        )
    }
    "not fail if the agent's group id cannot be retrieved" in {
      when(mockEsp.getPrincipalGroupIdFor(any[Arn])(any[HeaderCarrier]))
        .thenReturn(Future.failed(UpstreamErrorResponse("error", 503)))

      await(testService.updateFriendlyName(testInvitation, testEnrolment)) shouldBe ()

      verify(mockEsp, times(0))
        .updateEnrolmentFriendlyName(eqs(testGroupId), eqs(testEnrolment.toString), eqs(encodedName))(
          any[HeaderCarrier]
        )
    }
    "ignore PIR clients" in {
      await(
        testService.updateFriendlyName(testInvitation.copy(service = PersonalIncomeRecord.id), testEnrolment)
      ) shouldBe ()
      verify(mockEsp, times(0))
        .updateEnrolmentFriendlyName(eqs(testGroupId), eqs(testEnrolment.toString), eqs(encodedName))(
          any[HeaderCarrier]
        )
    }
    "ignore Alt ITSA clients" in {
      await(
        testService.updateFriendlyName(testInvitation.copy(clientId = testInvitation.suppliedClientId), testEnrolment)
      ) shouldBe ()
      verify(mockEsp, times(0))
        .updateEnrolmentFriendlyName(eqs(testGroupId), eqs(testEnrolment.toString), eqs(encodedName))(
          any[HeaderCarrier]
        )
    }
    "ignore Alt ITSA SUPP clients" in {
      await(
        testService.updateFriendlyName(
          testInvitation.copy(service = MtdItSupp.id, clientId = testInvitation.suppliedClientId),
          testEnrolment
        )
      ) shouldBe ()
      verify(mockEsp, times(0))
        .updateEnrolmentFriendlyName(eqs(testGroupId), eqs(testEnrolment.toString), eqs(encodedName))(
          any[HeaderCarrier]
        )
    }
  }
}
