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

import cats.data.EitherT
import cats.implicits._
import org.apache.commons.lang3.RandomStringUtils
import uk.gov.hmrc.agentclientrelationships.util.RequestAwareLogging
import uk.gov.hmrc.agentclientrelationships.model.invitationLink._
import uk.gov.hmrc.agentclientrelationships.repository.AgentReferenceRepository
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import play.api.mvc.RequestHeader

import javax.inject.Inject
import javax.inject.Singleton
import scala.collection.Seq
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class InvitationLinkService @Inject() (
  agentReferenceRepository: AgentReferenceRepository,
  agentAssuranceService: AgentAssuranceService
)(implicit ec: ExecutionContext)
extends RequestAwareLogging {

  private val codetable = "ABCDEFGHJKLMNOPRSTUWXYZ123456789"

  def validateLink(
    uid: String,
    normalizedAgentName: String
  )(implicit request: RequestHeader): Future[Either[InvitationLinkFailureResponse, ValidateLinkResponse]] = {

    val agencyNameT =
      for {
        agentReferenceRecord <- EitherT(getAgentReferenceRecord(uid))
        _ <- EitherT.fromEither[Future](
          validateNormalizedAgentName(agentReferenceRecord.normalisedAgentNames, normalizedAgentName)
        )
        agentDetailsResponse <- getNonSuspendedAgentRecord(agentReferenceRecord.arn)
        agencyName <- EitherT(getAgencyName(agentDetailsResponse))
      } yield ValidateLinkResponse(agentReferenceRecord.arn, agencyName)

    agencyNameT.value

  }

  def createLink(arn: Arn)(implicit request: RequestHeader): Future[CreateLinkResponse] =
    for {
      agentDetailsResponse <- agentAssuranceService.getAgentRecord(arn)
      newNormaliseAgentName = normaliseAgentName(agentDetailsResponse.agencyDetails.agencyName)

      agentReferenceRecord <- getAgentReferenceRecordByArn(arn, newNormaliseAgentName)

      _ <-
        if (agentReferenceRecord.normalisedAgentNames.contains(newNormaliseAgentName))
          Future.successful(())
        else
          updateAgentReferenceRecord(agentReferenceRecord.uid, newNormaliseAgentName)

    } yield CreateLinkResponse(agentReferenceRecord.uid, newNormaliseAgentName)

  def validateInvitationRequest(
    uid: String
  )(implicit request: RequestHeader): Future[Either[InvitationLinkFailureResponse, ValidateLinkResponse]] = {
    val responseT =
      for {
        agentReferenceRecord <- EitherT(getAgentReferenceRecord(uid))
        agentDetailsResponse <- getNonSuspendedAgentRecord(agentReferenceRecord.arn)
        agencyName <- EitherT(getAgencyName(agentDetailsResponse))
      } yield ValidateLinkResponse(agentReferenceRecord.arn, agencyName)

    responseT.value
  }

  private def getAgentReferenceRecord(
    uid: String
  ): Future[Either[InvitationLinkFailureResponse, AgentReferenceRecord]] = agentReferenceRepository
    .findBy(uid)
    .map(_.toRight(InvitationLinkFailureResponse.AgentReferenceDataNotFound))

  def getAgentReferenceRecordByArn(
    arn: Arn,
    newNormaliseAgentName: String
  ): Future[AgentReferenceRecord] = agentReferenceRepository
    .findByArn(arn)
    .flatMap {
      case Some(value) => Future.successful(value)
      case None => createAgentReferenceRecord(arn, newNormaliseAgentName)
    }

  private def updateAgentReferenceRecord(
    uid: String,
    normalisedAgentNames: String
  ): Future[Unit] = agentReferenceRepository.updateAgentName(uid, normalisedAgentNames)

  def createAgentReferenceRecord(
    arn: Arn,
    normalisedAgentNames: String
  ): Future[AgentReferenceRecord] = {
    val agentReferenceRecord = AgentReferenceRecord(
      uid = RandomStringUtils.secure().next(8, codetable),
      arn = arn,
      normalisedAgentNames = Seq(normalisedAgentNames)
    )
    agentReferenceRepository.create(agentReferenceRecord).map(_ => agentReferenceRecord)
  }

  def normaliseAgentName(agentName: String) = agentName
    .toLowerCase()
    .replaceAll("\\s+", "-")
    .replaceAll("[^A-Za-z0-9-]", "")

  private def validateNormalizedAgentName(
    normalisedAgentNames: Seq[String],
    normalizedAgentName: String
  ): Either[InvitationLinkFailureResponse, Boolean] =
    if (normalisedAgentNames.contains(normalizedAgentName))
      Right(true)
    else
      Left(InvitationLinkFailureResponse.NormalizedAgentNameNotMatched)

  private def getNonSuspendedAgentRecord(
    arn: Arn
  )(implicit
    request: RequestHeader
  ): EitherT[
    Future,
    InvitationLinkFailureResponse,
    AgentDetailsDesResponse
  ] = EitherT.fromOptionF(agentAssuranceService.getNonSuspendedAgentRecord(arn), InvitationLinkFailureResponse.AgentSuspended)

  private def getAgencyName(
    agentDetailsDesResponse: AgentDetailsDesResponse
  ): Future[Either[InvitationLinkFailureResponse, String]] = Future.successful(
    Right(agentDetailsDesResponse.agencyDetails.agencyName)
  )

}
