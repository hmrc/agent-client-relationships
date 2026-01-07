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

import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.mocks.MockPartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.ClientIdentifier
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoType
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdIt
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class RemoveAuthorisationServiceSpec
extends UnitSpec
with ResettingMockitoSugar
with MockPartialAuthRepository {

  private val mockDeleteRelationshipsService: DeleteRelationshipsService = resettingMock[DeleteRelationshipsService]
  private val mockInvitationsRepository: InvitationsRepository = resettingMock[InvitationsRepository]
  private val mockHipConnector: uk.gov.hmrc.agentclientrelationships.connectors.HipConnector =
    resettingMock[uk.gov.hmrc.agentclientrelationships.connectors.HipConnector]

  private val service =
    new RemoveAuthorisationService(
      deleteService = mockDeleteRelationshipsService,
      partialAuthRepository = mockPartialAuthRepository,
      invitationsRepository = mockInvitationsRepository,
      hipConnector = mockHipConnector
    )

  private val arn: Arn = Arn("AARN0000002")
  private val nino: NinoWithoutSuffix = NinoWithoutSuffix("AB123456C")
  private val clientId: ClientIdentifier.ClientId =
    ClientIdentifier(nino.value, NinoType.id)

  implicit private val request: FakeRequest[?] = FakeRequest()

  "deauthPartialAuth" should {
    "return PartialAuthDeauthorised when a record is updated" in {
      mockDeauthorisePartialAuth(MtdIt.id, nino, arn)(Future.successful(true))

      val resultF =
        service.deauthPartialAuth(
          arn = arn,
          clientId = clientId,
          service = MtdIt
        )

      await(resultF) shouldBe service.PartialAuthDeauthorised
    }

    "return PartialAuthNotFound when no record is updated" in {
      mockDeauthorisePartialAuth(MtdIt.id, nino, arn)(Future.successful(false))

      val resultF =
        service.deauthPartialAuth(
          arn = arn,
          clientId = clientId,
          service = MtdIt
        )

      await(resultF) shouldBe service.PartialAuthNotFound
    }

    "fail the Future when the repository fails" in {
      val failure = new RuntimeException("mongo failed")
      mockDeauthorisePartialAuth(MtdIt.id, nino, arn)(Future.failed(failure))

      val resultF =
        service.deauthPartialAuth(
          arn = arn,
          clientId = clientId,
          service = MtdIt
        )

      val thrown = intercept[RuntimeException](await(resultF))
      thrown shouldBe failure
    }
  }
}
