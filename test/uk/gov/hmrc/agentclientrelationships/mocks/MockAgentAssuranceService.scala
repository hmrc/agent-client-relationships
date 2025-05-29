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

package uk.gov.hmrc.agentclientrelationships.mocks

import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.{eq => eqs}
import org.mockito.Mockito.when
import org.mockito.stubbing.OngoingStubbing
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.agentclientrelationships.services.AgentAssuranceService
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentmtdidentifiers.model.Arn

import scala.concurrent.Future

trait MockAgentAssuranceService {
  this: ResettingMockitoSugar =>

  val mockAgentAssuranceService: AgentAssuranceService = resettingMock[AgentAssuranceService]

  def mockGetAgentRecord(
    arn: Arn
  )(response: AgentDetailsDesResponse): OngoingStubbing[Future[AgentDetailsDesResponse]] = when(
    mockAgentAssuranceService.getAgentRecord(eqs(arn))(any[RequestHeader])
  ).thenReturn(Future.successful(response))

  def mockGetNonSuspendedAgentRecord(
    arn: Arn
  )(response: Option[AgentDetailsDesResponse]): OngoingStubbing[Future[Option[AgentDetailsDesResponse]]] = when(
    mockAgentAssuranceService.getNonSuspendedAgentRecord(eqs(arn))(any[RequestHeader])
  ).thenReturn(Future.successful(response))

  def mockFailedGetAgentRecord(arn: Arn): OngoingStubbing[Future[AgentDetailsDesResponse]] = when(
    mockAgentAssuranceService.getAgentRecord(eqs(arn))(any[RequestHeader])
  ).thenReturn(Future.failed(new Exception("something went wrong")))

}
