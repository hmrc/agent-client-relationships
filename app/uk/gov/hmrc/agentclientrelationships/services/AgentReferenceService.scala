/*
 * Copyright 2024 HM Revenue & Customs
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

import play.api.Logging
import uk.gov.hmrc.agentclientrelationships.connectors.AgentAssuranceConnector
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.{AgentDetailsDesResponse, AgentReferenceRecord, ValidateLinkFailureResponse, ValidateLinkResponse}
import uk.gov.hmrc.agentclientrelationships.repository.AgentReferenceRepository
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.collection.Seq
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AgentReferenceService @Inject() (
  agentReferenceRepository: AgentReferenceRepository,
  agentAssuranceConnector: AgentAssuranceConnector
)(implicit ec: ExecutionContext)
    extends Logging {

  def validateLink(uid: String, normalizedAgentName: String)(implicit
    hc: HeaderCarrier
  ): Future[Either[ValidateLinkFailureResponse, ValidateLinkResponse]] = {
    import cats.data.EitherT
    import cats.implicits._
    val agencyNameT = for {
      agentReferenceRecord <- EitherT(getAgentReferenceRecord(uid))
      _ <- EitherT.fromEither[Future](
             validateNormalizedAgentName(agentReferenceRecord.normalisedAgentNames, normalizedAgentName)
           )
      agentDetailsResponse <- EitherT.right(getAgentDetails)
      _                    <- EitherT.fromEither[Future](checkSuspensionDetails(agentDetailsResponse))
      agencyName           <- EitherT(getAgencyName(agentDetailsResponse))
    } yield ValidateLinkResponse(agentReferenceRecord.arn, agencyName)

    agencyNameT.value

  }

  private def getAgentReferenceRecord(uid: String): Future[Either[ValidateLinkFailureResponse, AgentReferenceRecord]] =
    agentReferenceRepository
      .findBy(uid)
      .map(_.toRight(ValidateLinkFailureResponse.AgentReferenceDataNotFound))

  private def validateNormalizedAgentName(
    normalisedAgentNames: Seq[String],
    normalizedAgentName: String
  ): Either[ValidateLinkFailureResponse, Boolean] =
    if (normalisedAgentNames.contains(normalizedAgentName)) Right(true)
    else Left(ValidateLinkFailureResponse.NormalizedAgentNameNotMatched)

  private def getAgentDetails(implicit hc: HeaderCarrier): Future[AgentDetailsDesResponse] =
    agentAssuranceConnector.getAgentRecordWithChecks

  private def checkSuspensionDetails(
    agentDetailsDesResponse: AgentDetailsDesResponse
  ): Either[ValidateLinkFailureResponse, Boolean] =
    if (agentDetailsDesResponse.suspensionDetails.exists(_.suspensionStatus))
      Left(ValidateLinkFailureResponse.AgentSuspended)
    else Right(false)

  private def getAgencyName(
    agentDetailsDesResponse: AgentDetailsDesResponse
  ): Future[Either[ValidateLinkFailureResponse, String]] =
    Future.successful(
      agentDetailsDesResponse.agencyDetails
        .map(_.agencyName)
        .toRight(ValidateLinkFailureResponse.AgentNameMissing)
    )

}
