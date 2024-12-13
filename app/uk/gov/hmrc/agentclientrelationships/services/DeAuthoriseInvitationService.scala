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
import uk.gov.hmrc.agentclientrelationships.connectors.AgentClientAuthorisationConnector
import uk.gov.hmrc.agentclientrelationships.model._
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

abstract class DeAuthoriseInvitationService {
  def deAuthoriseInvitation(arn: Arn, enrolmentKey: EnrolmentKey, endedBy: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Boolean]
}

@Singleton
class AcaDeAuthoriseInvitationService @Inject() (aca: AgentClientAuthorisationConnector)
    extends DeAuthoriseInvitationService {
  override def deAuthoriseInvitation(arn: Arn, enrolmentKey: EnrolmentKey, endedBy: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Boolean] =
    aca.setRelationshipEnded(arn, enrolmentKey, endedBy)
}

@Singleton
class AcrDeAuthoriseInvitationService @Inject() (invitationsRepository: InvitationsRepository)
    extends DeAuthoriseInvitationService {
  override def deAuthoriseInvitation(arn: Arn, enrolmentKey: EnrolmentKey, endedBy: String)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Boolean] =
    invitationsRepository
      .deauthorise(arn.value, enrolmentKey.oneIdentifier().value, enrolmentKey.service, endedBy)
      .map(_.fold(false)(_ => true))

}
