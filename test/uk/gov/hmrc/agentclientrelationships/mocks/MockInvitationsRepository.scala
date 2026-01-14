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
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.InvitationStatus
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar

import java.time.Instant
import scala.concurrent.Future

trait MockInvitationsRepository {
  this: ResettingMockitoSugar =>

  val mockInvitationsRepository: InvitationsRepository = resettingMock[InvitationsRepository]

  def mockUpdateStatus(
    invitationId: String,
    status: InvitationStatus
  )(response: Future[Invitation]): OngoingStubbing[Future[Invitation]] = when(
    mockInvitationsRepository.updateStatus(
      eqs(invitationId),
      eqs(status),
      any[Option[Instant]]
    )
  ).thenReturn(response)

  def mockFindAllBy(
    arn: Option[String],
    services: Seq[String],
    clientIds: Seq[String],
    status: Option[InvitationStatus],
    isSuppliedClientId: Boolean = false
  )(response: Future[Seq[Invitation]]): OngoingStubbing[Future[Seq[Invitation]]] = when(
    mockInvitationsRepository.findAllBy(
      eqs(arn),
      eqs(services),
      eqs(clientIds),
      eqs(status),
      eqs(isSuppliedClientId)
    )
  ).thenReturn(response)

  def mockDeauthAcceptedInvitations(
    service: String,
    arn: Option[String],
    clientId: String,
    invitationIdToIgnore: Option[String],
    relationshipEndedBy: String
  )(response: Future[Boolean]): OngoingStubbing[Future[Boolean]] = when(
    mockInvitationsRepository.deauthAcceptedInvitations(
      eqs(service),
      eqs(arn),
      eqs(clientId),
      eqs(invitationIdToIgnore),
      eqs(relationshipEndedBy),
      any[Instant]
    )
  ).thenReturn(response)

}
