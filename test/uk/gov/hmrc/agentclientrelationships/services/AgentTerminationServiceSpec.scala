/*
 * Copyright 2020 HM Revenue & Customs
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

import org.scalamock.scalatest.MockFactory
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.ScalaFutures
import uk.gov.hmrc.agentclientrelationships.model.AgentTerminationResponse
import uk.gov.hmrc.agentclientrelationships.repository.{DeleteRecordRepository, RelationshipCopyRecordRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class AgentTerminationServiceSpec extends FlatSpec with MockFactory with ScalaFutures with Matchers {
  val arn = Arn("AARN0000002")

  val drrMock = mock[DeleteRecordRepository]
  val rcrrMock = mock[RelationshipCopyRecordRepository]
  val hc = HeaderCarrier()
  val ec = implicitly[ExecutionContext]

  val service = new AgentTerminationService(drrMock, rcrrMock)

  "AgentTerminationService" should "return result as expected" in {

    (drrMock.terminateAgent(_: Arn)(_: ExecutionContext)).expects(arn, *).returning(Future.successful(Right(1)))
    (rcrrMock.terminateAgent(_: Arn)(_: ExecutionContext)).expects(arn, *).returning(Future.successful(Right(1)))

    service.terminateAgent(arn).value.futureValue shouldBe Right(AgentTerminationResponse(1, 1))
  }

  it should "handle error from DeleteRecordRepository" in {
    (drrMock
      .terminateAgent(_: Arn)(_: ExecutionContext))
      .expects(arn, *)
      .returning(Future.successful(Left("some error")))
    (rcrrMock.terminateAgent(_: Arn)(_: ExecutionContext)).expects(arn, *).returning(Future.successful(Right(1)))

    service.terminateAgent(arn).value.futureValue shouldBe Left("some error")
  }

  it should "handle error from RelationshipCopyRecordRepository" in {
    (drrMock.terminateAgent(_: Arn)(_: ExecutionContext)).expects(arn, *).returning(Future.successful(Right(1)))
    (rcrrMock
      .terminateAgent(_: Arn)(_: ExecutionContext))
      .expects(arn, *)
      .returning(Future.successful(Left("some error")))

    service.terminateAgent(arn).value.futureValue shouldBe Left("some error")
  }
}