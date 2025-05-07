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
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthRelationship
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.Nino

import java.time.Instant
import scala.concurrent.Future

trait MockPartialAuthRepository {
  this: ResettingMockitoSugar =>

  val mockPartialAuthRepository: PartialAuthRepository = resettingMock[PartialAuthRepository]

  def mockFindMainAgent(
    nino: String
  )(response: Future[Option[PartialAuthRelationship]]): OngoingStubbing[Future[Option[PartialAuthRelationship]]] = when(
    mockPartialAuthRepository.findMainAgent(eqs(nino))
  ).thenReturn(response)

  def mockDeauthorisePartialAuth(service: String, nino: Nino, arn: Arn)(
    response: Future[Boolean]
  ): OngoingStubbing[Future[Boolean]] = when(
    mockPartialAuthRepository.deauthorise(eqs(service), eqs(nino), eqs(arn), any[Instant])
  ).thenReturn(response)

  def mockCreatePartialAuth(arn: Arn, service: String, nino: Nino)(
    response: Future[Unit] = Future.unit
  ): OngoingStubbing[Future[Unit]] = when(
    mockPartialAuthRepository.create(any[Instant], eqs(arn), eqs(service), eqs(nino))
  ).thenReturn(response)
}
