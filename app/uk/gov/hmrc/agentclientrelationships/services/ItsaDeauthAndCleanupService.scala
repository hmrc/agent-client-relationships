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

import play.api.mvc.Request
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.model.{Accepted, EnrolmentKey, Invitation, PartialAuth}
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository.endedByClient
import uk.gov.hmrc.agentclientrelationships.repository.{InvitationsRepository, PartialAuthRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Service}
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{HMRCMTDIT, HMRCMTDITSUPP}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HeaderCarrier

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ItsaDeauthAndCleanupService @Inject() (
  partialAuthRepository: PartialAuthRepository,
  checkRelationshipsService: CheckRelationshipsService,
  deleteRelationshipsServiceWithAcr: DeleteRelationshipsServiceWithAcr,
  invitationsRepository: InvitationsRepository
)(implicit ec: ExecutionContext) {

  def deleteSameAgentRelationship(
    service: String,
    arn: String,
    optMtdItId: Option[String],
    nino: String,
    timestamp: Instant = Instant.now()
  )(implicit
    hc: HeaderCarrier,
    currentUser: CurrentUser,
    request: Request[_]
  ): Future[Boolean] =
    service match {
      case `HMRCMTDIT` | `HMRCMTDITSUPP` =>
        val serviceToCheck = Service.forId(if (service == HMRCMTDIT) HMRCMTDITSUPP else HMRCMTDIT)
        for {
          // Attempt to remove existing alt itsa partial auth
          altItsa <- partialAuthRepository.deauthorise(serviceToCheck.id, Nino(nino), Arn(arn), timestamp)
          // Attempt to remove existing itsa relationship
          itsa <- optMtdItId.fold(Future.successful(false)) { mtdItId =>
                    checkRelationshipsService
                      .checkForRelationshipAgencyLevel(
                        Arn(arn),
                        EnrolmentKey(
                          serviceToCheck,
                          MtdItId(mtdItId)
                        )
                      )
                      .flatMap {
                        case (true, _) =>
                          deleteRelationshipsServiceWithAcr
                            .deleteRelationship(
                              Arn(arn),
                              EnrolmentKey(
                                serviceToCheck,
                                MtdItId(mtdItId)
                              ),
                              currentUser.affinityGroup
                            )
                            .map(_ => true)
                        case _ => Future.successful(false)
                      }
                  }
          // Clean up accepted invitations
          _ <- Future.successful(
                 deauthAcceptedInvitations(serviceToCheck.id, Some(arn), nino, None, isAltItsa = true, timestamp)
               )
          _ <- Future.successful(optMtdItId.fold(Future.unit) { mtdItId =>
                 deauthAcceptedInvitations(serviceToCheck.id, Some(arn), mtdItId, None, isAltItsa = false, timestamp)
               })
        } yield altItsa || itsa
      case _ => Future.successful(false)
    }

  private def deauthAcceptedInvitations(
    service: String,
    optArn: Option[String],
    clientId: String,
    optInvitationId: Option[String],
    isAltItsa: Boolean,
    timestamp: Instant
  ) = {
    val acceptedStatus = if (isAltItsa) PartialAuth else Accepted
    invitationsRepository
      .findAllBy(
        arn = optArn,
        services = Seq(service),
        clientId = Some(clientId),
        status = Some(acceptedStatus)
      )
      .fallbackTo(Future.successful(Nil))
      .map { acceptedInvitations: Seq[Invitation] =>
        acceptedInvitations
          .filterNot(invitation => optInvitationId.contains(invitation.invitationId))
          .foreach(acceptedInvitation =>
            invitationsRepository.deauthInvitation(acceptedInvitation.invitationId, endedByClient, Some(timestamp))
          )
      }
  }

}
