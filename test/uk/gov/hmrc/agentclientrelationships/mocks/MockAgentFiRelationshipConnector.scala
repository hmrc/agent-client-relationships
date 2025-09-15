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

import org.apache.pekko.Done
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.{eq => eqs}
import org.mockito.Mockito.when
import org.mockito.stubbing.OngoingStubbing
import uk.gov.hmrc.agentclientrelationships.connectors.AgentFiRelationshipConnector
import uk.gov.hmrc.agentclientrelationships.model.stride.ClientRelationship
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.model.RelationshipFailureResponse

import java.time.LocalDateTime
import scala.concurrent.Future

trait MockAgentFiRelationshipConnector {
  this: ResettingMockitoSugar =>

  val mockAgentFiRelationshipConnector: AgentFiRelationshipConnector = resettingMock[AgentFiRelationshipConnector]

  def mockCreateFiRelationship(
    arn: Arn,
    service: String,
    clientId: String
  ): OngoingStubbing[Future[Done]] = when(
    mockAgentFiRelationshipConnector.createRelationship(
      eqs(arn),
      eqs(service),
      eqs(clientId),
      any[LocalDateTime]
    )(any[RequestHeader])
  ).thenReturn(Future.successful(Done))

  def mockFindRelationshipForClient(clientId: String)(response: Either[
    RelationshipFailureResponse,
    Seq[ClientRelationship]
  ]): OngoingStubbing[Future[Either[RelationshipFailureResponse, Seq[ClientRelationship]]]] = when(
    mockAgentFiRelationshipConnector.findIrvActiveRelationshipForClient(eqs(clientId))(any[RequestHeader])
  ).thenReturn(Future.successful(response))

}
