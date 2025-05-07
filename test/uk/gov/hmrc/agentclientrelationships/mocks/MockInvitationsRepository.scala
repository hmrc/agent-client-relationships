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
import uk.gov.hmrc.agentclientrelationships.model.{Invitation, InvitationStatus}
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar

import java.time.Instant
import scala.concurrent.Future

trait MockInvitationsRepository {
  this: ResettingMockitoSugar =>

  val mockInvitationsRepository: InvitationsRepository = resettingMock[InvitationsRepository]

  def mockUpdateStatus(invitationId: String, status: InvitationStatus)(
    response: Future[Invitation]
  ): OngoingStubbing[Future[Invitation]] =
    when(mockInvitationsRepository.updateStatus(eqs(invitationId), eqs(status), any[Option[Instant]]))
      .thenReturn(response)

  def mockFindAllBy(
    arn: Option[String],
    services: Seq[String],
    clientIds: Seq[String],
    status: Option[InvitationStatus]
  )(response: Future[Seq[Invitation]]): OngoingStubbing[Future[Seq[Invitation]]] =
    when(mockInvitationsRepository.findAllBy(eqs(arn), eqs(services), eqs(clientIds), eqs(status))).thenReturn(response)

  def mockDeauthInvitation(invitationId: String, endedBy: String)(
    response: Future[Option[Invitation]]
  ): OngoingStubbing[Future[Option[Invitation]]] =
    when(
      mockInvitationsRepository.deauthInvitation(eqs(invitationId), eqs("Client"), any[Option[Instant]])
    ).thenReturn(response)

  def mockFindAllPendingForClient(clientId: String, services: Seq[String])(
    response: Seq[Invitation]
  ): OngoingStubbing[Future[Seq[Invitation]]] =
    when(mockInvitationsRepository.findAllPendingForSuppliedClient(clientId, services))
      .thenReturn(Future.successful(response))
}
