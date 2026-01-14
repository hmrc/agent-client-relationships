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

import play.api.mvc.RequestHeader
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys.agentRoleChange
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys.howPartialAuthTerminatedKey
import uk.gov.hmrc.agentclientrelationships.audit.AuditKeys.howRelationshipTerminatedKey
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.audit.AuditService
import uk.gov.hmrc.agentclientrelationships.auth.CurrentUser
import uk.gov.hmrc.agentclientrelationships.model.Accepted
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.PartialAuth
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.agentclientrelationships.model.identifiers.MtdItId
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCMTDIT
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository.endedByClient
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// scalastyle:off method.length
@Singleton
class ItsaDeauthAndCleanupService @Inject() (
  partialAuthRepository: PartialAuthRepository,
  checkRelationshipsService: CheckRelationshipsService,
  deleteRelationshipsService: DeleteRelationshipsService,
  invitationsRepository: InvitationsRepository,
  auditService: AuditService
)(implicit ec: ExecutionContext) {

  def deleteSameAgentRelationship(
    service: String,
    arn: String,
    optMtdItId: Option[String],
    nino: String,
    timestamp: Instant = Instant.now()
  )(implicit
    request: RequestHeader,
    currentUser: CurrentUser
  ): Future[Boolean] =
    service match {
      case `HMRCMTDIT` | `HMRCMTDITSUPP` =>
        val serviceToCheck = Service.forId(
          if (service == HMRCMTDIT)
            HMRCMTDITSUPP
          else
            HMRCMTDIT
        )
        for {
          // Attempt to remove existing alt itsa partial auth
          altItsa <- partialAuthRepository.deauthorise(
            serviceToCheck.id,
            NinoWithoutSuffix(nino),
            Arn(arn),
            timestamp
          )
          _ =
            if (altItsa) {
              implicit val auditData: AuditData = new AuditData()
              auditData.set(howPartialAuthTerminatedKey, agentRoleChange)
              auditService.sendTerminatePartialAuthAuditEvent(
                arn,
                serviceToCheck.id,
                nino
              )
            }
          // Attempt to remove existing itsa relationship
          itsa <-
            optMtdItId.fold(Future.successful(false)) { mtdItId =>
              checkRelationshipsService
                .checkForRelationshipAgencyLevel(Arn(arn), EnrolmentKey(serviceToCheck, MtdItId(mtdItId)))
                .flatMap {
                  case (true, _) =>
                    implicit val auditData: AuditData = new AuditData()
                    auditData.set(howRelationshipTerminatedKey, agentRoleChange)
                    deleteRelationshipsService
                      .deleteRelationship(
                        Arn(arn),
                        EnrolmentKey(serviceToCheck, MtdItId(mtdItId)),
                        currentUser.affinityGroup
                      )
                      .map(_ => true)
                  case _ => Future.successful(false)
                }
            }
          // Clean up accepted invitations
          _ <- Future.successful(
            invitationsRepository.deauthOldInvitations(
              service = serviceToCheck.id,
              optArn = Some(arn),
              clientId = nino,
              invitationIdToIgnore = None,
              relationshipEndedBy = endedByClient,
              timestamp = timestamp
            )
          )
        } yield altItsa || itsa
      case _ => Future.successful(false)
    }

}
