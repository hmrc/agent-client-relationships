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

import org.mockito.ArgumentMatchers.{any, eq => eqs}
import org.mockito.Mockito.when
import org.mockito.stubbing.OngoingStubbing
import uk.gov.hmrc.agentclientrelationships.connectors.AgentFiRelationshipConnector
import uk.gov.hmrc.agentclientrelationships.model.stride.ClientRelationship
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.http.HeaderCarrier

import java.time.LocalDateTime
import scala.concurrent.Future

trait MockAgentFiRelationshipConnector {
  this: ResettingMockitoSugar =>

  val mockAgentFiRelationshipConnector: AgentFiRelationshipConnector = resettingMock[AgentFiRelationshipConnector]

  def mockCreateFiRelationship(arn: Arn, service: String, clientId: String)(
    response: Future[Boolean]
  ): OngoingStubbing[Future[Boolean]] =
    when(
      mockAgentFiRelationshipConnector
        .createRelationship(eqs(arn), eqs(service), eqs(clientId), any[LocalDateTime])(any[HeaderCarrier])
    )
      .thenReturn(response)

  def mockFindRelationshipForClient(
    clientId: String
  )(response: Option[ClientRelationship]): OngoingStubbing[Future[Option[ClientRelationship]]] =
    when(
      mockAgentFiRelationshipConnector
        .findIrvRelationshipForClient(eqs(clientId))(any[HeaderCarrier])
    ).thenReturn(Future.successful(response))

}
