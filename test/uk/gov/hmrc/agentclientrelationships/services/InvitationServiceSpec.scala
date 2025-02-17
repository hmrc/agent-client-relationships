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

package uk.gov.hmrc.agentclientrelationships.services

import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.mocks._
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.{AgencyDetails, AgentDetailsDesResponse}
import uk.gov.hmrc.agentclientrelationships.support.{ResettingMockitoSugar, UnitSpec}
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDVAT, Vat}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, SuspensionDetails, Vrn}
import uk.gov.hmrc.http.HeaderCarrier

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, Future}

class InvitationServiceSpec
    extends UnitSpec
    with ResettingMockitoSugar
    with MockInvitationsRepository
    with MockPartialAuthRepository
    with MockIFConnector
    with MockAgentAssuranceConnector
    with MockEmailService {

  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val hc: HeaderCarrier = HeaderCarrier()
  val mockAppConfig: AppConfig = mock[AppConfig]

  object TestService
      extends InvitationService(
        mockInvitationsRepository,
        mockPartialAuthRepository,
        mockIFConnector,
        mockAgentAssuranceConnector,
        mockEmailService,
        mockAppConfig
      )

  val testVrn: Vrn = Vrn("1234567890")
  val testArn1 = "ARN1234567891"
  val testArn2 = "ARN1234567892"
  val testArn3 = "ARN1234567893"
  val invitation1: Invitation =
    Invitation.createNew(testArn1, Vat, testVrn, testVrn, "", "", "", LocalDate.now(), Some("personal"))
  val invitation2: Invitation =
    Invitation.createNew(testArn2, Vat, testVrn, testVrn, "", "", "", LocalDate.now(), Some("personal"))
  val invitation3: Invitation =
    Invitation.createNew(testArn3, Vat, testVrn, testVrn, "", "", "", LocalDate.now(), Some("personal"))
  val testAgentDetailsDesResponse: AgentDetailsDesResponse =
    AgentDetailsDesResponse(
      agencyDetails = AgencyDetails("ABC Ltd", ""),
      suspensionDetails = None
    )
  val testSuspendedAgentDetailsDesResponse: AgentDetailsDesResponse =
    AgentDetailsDesResponse(
      agencyDetails = AgencyDetails("ABC Ltd", ""),
      suspensionDetails = Some(SuspensionDetails(suspensionStatus = true, None))
    )

  "findNonSuspendedClientInvitations" should {
    "retrieve invitations from the repository and return ones from non suspended agents" in {
      mockFindAllBy(None, Seq(HMRCMTDVAT), Seq(testVrn.value), None)(
        Future.successful(Seq(invitation1, invitation2, invitation3))
      )
      mockGetAgentRecordWithChecks(Arn(testArn1))(testSuspendedAgentDetailsDesResponse)
      mockGetAgentRecordWithChecks(Arn(testArn2))(testAgentDetailsDesResponse)
      mockGetAgentRecordWithChecks(Arn(testArn3))(testSuspendedAgentDetailsDesResponse)

      val result = await(TestService.findNonSuspendedClientInvitations(Seq(HMRCMTDVAT), Seq(testVrn.value)))

      result shouldBe Seq(invitation2)
    }
  }
}
