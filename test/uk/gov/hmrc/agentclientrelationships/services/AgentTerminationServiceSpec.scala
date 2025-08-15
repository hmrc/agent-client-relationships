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

import org.mockito.ArgumentMatchers.{eq => eqs}
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.agentclientrelationships.model.DeletionCount
import uk.gov.hmrc.agentclientrelationships.model.TerminationResponse
import uk.gov.hmrc.agentclientrelationships.repository.DeleteRecordRepository
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecordRepository
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class AgentTerminationServiceSpec
extends AnyFlatSpec
with MockitoSugar
with ScalaFutures
with Matchers {

  val arn: Arn = Arn("AARN0000002")

  val drrMock: DeleteRecordRepository = mock[DeleteRecordRepository]
  val rcrrMock: RelationshipCopyRecordRepository = mock[RelationshipCopyRecordRepository]
  val ec: ExecutionContext = implicitly[ExecutionContext]

  val service = new AgentTerminationService(drrMock, rcrrMock)

  "AgentTerminationService" should "return result as expected" in {

    when(drrMock.terminateAgent(eqs(arn))).thenReturn(Future.successful(Right(1)))
    when(rcrrMock.terminateAgent(eqs(arn))).thenReturn(Future.successful(Right(1)))

    service.terminateAgent(arn).value.futureValue shouldBe
      Right(
        TerminationResponse(
          Seq(
            DeletionCount(
              "agent-client-relationships",
              "delete-record",
              1
            ),
            DeletionCount(
              "agent-client-relationships",
              "relationship-copy-record",
              1
            )
          )
        )
      )
  }

  it should "handle error from DeleteRecordRepository" in {
    when(drrMock.terminateAgent(eqs(arn))).thenReturn(Future.successful(Left("some error")))
    when(rcrrMock.terminateAgent(eqs(arn))).thenReturn(Future.successful(Right(1)))

    service.terminateAgent(arn).value.futureValue shouldBe Left("some error")
  }

  it should "handle error from RelationshipCopyRecordRepository" in {
    when(drrMock.terminateAgent(eqs(arn))).thenReturn(Future.successful(Right(1)))
    when(rcrrMock.terminateAgent(eqs(arn))).thenReturn(Future.successful(Left("some error")))

    service.terminateAgent(arn).value.futureValue shouldBe Left("some error")
  }

}
