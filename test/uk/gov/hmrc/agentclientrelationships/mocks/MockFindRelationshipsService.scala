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
import uk.gov.hmrc.agentclientrelationships.model.ActiveRelationship
import uk.gov.hmrc.agentclientrelationships.services.FindRelationshipsService
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

trait MockFindRelationshipsService {
  this: ResettingMockitoSugar =>

  val mockFindRelationshipService: FindRelationshipsService = resettingMock[FindRelationshipsService]

  def mockGetItsaRelationshipsForClient(nino: Nino, service: Service)(
    response: Future[Option[ActiveRelationship]]
  ): OngoingStubbing[Future[Option[ActiveRelationship]]] =
    when(
      mockFindRelationshipService.getItsaRelationshipForClient(eqs(nino), eqs(service))(
        any[HeaderCarrier],
        any[ExecutionContext]
      )
    ).thenReturn(response)

  def mockGetActiveRelationshipsForClient(taxId: TaxIdentifier, service: Service)(
    response: Future[Option[ActiveRelationship]]
  ): OngoingStubbing[Future[Option[ActiveRelationship]]] =
    when(
      mockFindRelationshipService.getActiveRelationshipsForClient(eqs(taxId), eqs(service))(
        any[HeaderCarrier],
        any[ExecutionContext]
      )
    ).thenReturn(response)
}
