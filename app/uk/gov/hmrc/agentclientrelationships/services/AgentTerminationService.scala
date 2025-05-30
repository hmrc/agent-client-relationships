/*
 * Copyright 2023 HM Revenue & Customs
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

import cats.data._
import cats.implicits._
import javax.inject.Inject
import javax.inject.Singleton
import uk.gov.hmrc.agentclientrelationships.model.DeletionCount
import uk.gov.hmrc.agentclientrelationships.model.TerminationResponse
import uk.gov.hmrc.agentclientrelationships.repository.DeleteRecordRepository
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecordRepository
import uk.gov.hmrc.agentmtdidentifiers.model.Arn

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class AgentTerminationService @Inject() (
  deleteRecordRepository: DeleteRecordRepository,
  relationshipCopyRecordRepository: RelationshipCopyRecordRepository
)(implicit ec: ExecutionContext) {

  def terminateAgent(arn: Arn): EitherT[
    Future,
    String,
    TerminationResponse
  ] = {
    val drr = deleteRecordRepository.terminateAgent(arn)
    val rcrr = relationshipCopyRecordRepository.terminateAgent(arn)
    for {
      drrResult <- EitherT(drr)
      rcrrResult <- EitherT(rcrr)
      result <- EitherT.fromEither[Future](
        Right(
          TerminationResponse(
            Seq(
              DeletionCount(
                "agent-client-relationships",
                "delete-record",
                drrResult
              ),
              DeletionCount(
                "agent-client-relationships",
                "relationship-copy-record",
                rcrrResult
              )
            )
          )
        )
      )
    } yield result
  }

}
