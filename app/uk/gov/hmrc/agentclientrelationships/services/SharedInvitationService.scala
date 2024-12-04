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
import uk.gov.hmrc.agentclientrelationships.connectors.AgentClientAuthorisationConnector
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.model.invitation.InvitationFailureResponse
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

abstract class SharedInvitationService {
  def setRelationshipEndedShared(arn: Arn, enrolmentKey: EnrolmentKey, endedBy: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Boolean]
}

@Singleton
class AcaInvitationService @Inject() (
  aca: AgentClientAuthorisationConnector
) extends SharedInvitationService {
  override def setRelationshipEndedShared(arn: Arn, enrolmentKey: EnrolmentKey, endedBy: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Boolean] =
    aca.setRelationshipEnded(arn, enrolmentKey, endedBy)
}

@Singleton
class AcrInvitationService @Inject() (
  invitationService: InvitationService
) extends SharedInvitationService {
  override def setRelationshipEndedShared(arn: Arn, enrolmentKey: EnrolmentKey, endedBy: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Boolean] = {
    val resultT = for {
      validRequest <- EitherT.fromEither[Future](
                        invitationService
                          .validateRequest(enrolmentKey.service, enrolmentKey.oneIdentifier().value)
                      )
      invitation <-
        EitherT(invitationService.findLatestActiveInvitations(arn, validRequest.suppliedClientId, validRequest.service))

      updatedInvitation <- EitherT.liftF[Future, InvitationFailureResponse, Invitation](
                             invitationService
                               .setRelationshipEnded(invitation, endedBy)
                           )

    } yield updatedInvitation
    resultT.value.map(_.fold(_ => false, _ => true))

  }
}
