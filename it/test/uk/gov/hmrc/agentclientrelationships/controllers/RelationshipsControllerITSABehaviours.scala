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

package uk.gov.hmrc.agentclientrelationships.controllers

import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.Invitation
import uk.gov.hmrc.agentclientrelationships.model.PartialAuth
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthRelationship
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipReference.SaRef
import uk.gov.hmrc.agentclientrelationships.repository._
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentmtdidentifiers.model.Identifier
import uk.gov.hmrc.agentmtdidentifiers.model.MtdItId
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDIT
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDITSUPP
import uk.gov.hmrc.domain.SaAgentReference

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

// TODO. All of the following tests should be rewritten directly against a RelationshipsController instance (with appropriate mocks/stubs)
// rather than instantiating a whole app and sending a real HTTP request. It makes test setup and debug very difficult.

trait RelationshipsControllerITSABehaviours {
  this: RelationshipsBaseControllerISpec
    with HipStub =>

  // noinspection ScalaStyle
  def relationshipControllerITSASpecificBehaviours(): Unit = {
    val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT/client/MTDITID/${mtdItId.value}"

    val relationshipCopiedSuccessfully = RelationshipCopyRecord(
      arn.value,
      Some(mtdItEnrolmentKey),
      syncToETMPStatus = Some(SyncStatus.Success),
      syncToESStatus = Some(SyncStatus.Success)
    )

    val invitationRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
    val partialAuthRepository: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]

    def doRequest = doGetRequest(requestPath)

    "GET /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:mtdItId" should {
      // CESA CHECK UNHAPPY PATHS

      "return 404 when agent not allocated to client in es nor identifier not found in des" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistFor(mtdItEnrolmentKey)
        givenNinoIsUnknownFor(mtdItId)
        givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        val result = doRequest
        result.status shouldBe 404
        (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
      }

      "return 404 when agent not allocated to client in es nor cesa and no alt-itsa" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistFor(mtdItEnrolmentKey)
        givenNinoIsKnownFor(mtdItId, nino)
        givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        val result = doRequest
        result.status shouldBe 404
        (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
      }

      "return 404 when agent not allocated to client in es, cesa mapping not found and no alt-itsa" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistFor(mtdItEnrolmentKey)
        givenNinoIsKnownFor(mtdItId, nino)
        givenClientHasRelationshipWithAgentInCESA(nino, "foo")
        givenArnIsUnknownFor(arn)
        givenAdminUser("foo", "any")

        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        val result = doRequest
        result.status shouldBe 404
        (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
      }

      // HAPPY PATHS WHEN CHECKING CESA

      "return 200 when agent not allocated to client in es but relationship exists in cesa" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
        givenNinoIsKnownFor(mtdItId, nino)
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenArnIsKnownFor(arn, SaAgentReference("foo"))
        givenClientHasRelationshipWithAgentInCESA(nino, "foo")
        givenAgentCanBeAllocated(mtdItId, arn)
        givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )
        givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
        givenDelegatedGroupIdsExistFor(mtdItSuppEnrolmentKey, Set("foo"))
        givenEnrolmentDeallocationSucceeds("foo", mtdItSuppEnrolmentKey)
        givenAgentCanBeDeallocated(mtdItId, arn)
        givenCacheRefresh(arn)

        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe empty

        val result = doRequest
        result.status shouldBe 200

        await(repo.findBy(arn, mtdItEnrolmentKey)).get should have(
          Symbol("arn")(arn.value),
          Symbol("enrolmentKey")(Some(mtdItEnrolmentKey)),
          Symbol("references")(Some(Set(SaRef(SaAgentReference("foo"))))),
          Symbol("syncToETMPStatus")(Some(SyncStatus.Success)),
          Symbol("syncToESStatus")(Some(SyncStatus.Success))
        )

        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.CreateRelationship,
          detail = Map(
            "agentReferenceNumber" -> arn.value,
            "credId" -> "any",
            "agentCode" -> "bar",
            "nino" -> nino.value,
            "saAgentRef" -> "foo",
            "service" -> "HMRC-MTD-IT",
            "clientId" -> mtdItId.value,
            "clientIdType" -> "mtditid",
            "cesaRelationship" -> "true",
            "etmpRelationshipCreated" -> "true",
            "enrolmentDelegated" -> "true",
            "howRelationshipCreated" -> "CopyExistingCESARelationship"
          ),
          tags = Map("transactionName" -> "create-relationship", "path" -> requestPath)
        )

        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.CheckCESA,
          detail = Map(
            "agentReferenceNumber" -> arn.value,
            "credId" -> "any",
            "agentCode" -> "bar",
            "nino" -> nino.value,
            "saAgentRef" -> "foo",
            "cesaRelationship" -> "true"
          ),
          tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
        )
      }

      // HAPPY PATH FOR ALTERNATIVE-ITSA

      "return 200 when no relationship in CESA but there is an alt-itsa MAIN invitation for client" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
        givenNinoIsKnownFor(mtdItId, nino)
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )
        givenAgentCanBeAllocated(mtdItId, arn)
        givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
        givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
        givenDelegatedGroupIdsExistFor(mtdItSuppEnrolmentKey, Set("foo"))
        givenEnrolmentDeallocationSucceeds("foo", mtdItSuppEnrolmentKey)
        givenAgentCanBeDeallocated(mtdItId, arn)
        givenCacheRefresh(arn)

        val now = Instant.now()

        await(
          invitationRepo.collection
            .insertOne(
              Invitation(
                "abc1",
                arn.value,
                service = HMRCMTDIT,
                nino.value,
                "ni",
                suppliedClientId = nino.value,
                suppliedClientIdType = "ni",
                "clientname",
                "testAgentName",
                "agent@email.com",
                warningEmailSent = false,
                expiredEmailSent = false,
                status = PartialAuth,
                None,
                None,
                LocalDate.now().plusDays(21),
                created = now,
                lastUpdated = now
              )
            )
            .toFuture()
        )

        await(
          partialAuthRepository.collection
            .insertOne(
              PartialAuthRelationship(
                now,
                arn.value,
                HMRCMTDIT,
                nino.value,
                active = true,
                now
              )
            )
            .toFuture()
        )

        await(
          partialAuthRepository.findActive(
            HMRCMTDIT,
            nino,
            arn
          )
        ).isDefined shouldBe true

        val result = doRequest
        result.status shouldBe 200
        await(
          partialAuthRepository.findActive(
            HMRCMTDIT,
            nino,
            arn
          )
        ).isEmpty shouldBe true
      }

      "return 200 when no relationship in CESA but there is an alt-itsa SUPP invitation for client" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdItIdSupp(mtdItId)
        givenNinoIsKnownFor(mtdItId, nino)
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )
        givenAgentCanBeAllocated(mtdItId, arn)
        givenMTDITSUPPEnrolmentAllocationSucceeds(mtdItId, "bar")
        givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
        givenDelegatedGroupIdsExistFor(mtdItEnrolmentKey, Set("foo"))
        givenEnrolmentDeallocationSucceeds("foo", mtdItEnrolmentKey)
        givenAgentCanBeDeallocated(mtdItId, arn)
        givenCacheRefresh(arn)

        val now = Instant.now()

        await(
          invitationRepo.collection
            .insertOne(
              Invitation(
                "abc1",
                arn.value,
                service = HMRCMTDITSUPP,
                nino.value,
                "ni",
                suppliedClientId = nino.value,
                suppliedClientIdType = "ni",
                "clientname",
                "testAgentName",
                "agent@email.com",
                warningEmailSent = false,
                expiredEmailSent = false,
                status = PartialAuth,
                None,
                None,
                LocalDate.now().plusDays(21),
                created = now,
                lastUpdated = now
              )
            )
            .toFuture()
        )

        await(
          partialAuthRepository.collection
            .insertOne(
              PartialAuthRelationship(
                now,
                arn.value,
                HMRCMTDITSUPP,
                nino.value,
                active = true,
                now
              )
            )
            .toFuture()
        )

        await(
          partialAuthRepository.findActive(
            HMRCMTDITSUPP,
            nino,
            arn
          )
        ).isDefined shouldBe true

        val result = doGetRequest(
          s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT-SUPP/client/MTDITID/${mtdItId.value}"
        )
        result.status shouldBe 200
        await(
          partialAuthRepository.findActive(
            HMRCMTDITSUPP,
            nino,
            arn
          )
        ).isEmpty shouldBe true
      }

      // UNHAPPY PATH FOR ALTERNATIVE-ITSA

      "return 404 when no relationship in CESA and no alt-itsa invitation for client" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
        givenNinoIsKnownFor(mtdItId, nino)
        givenArnIsUnknownFor(arn)
        givenClientHasNoRelationshipWithAnyAgentInCESA(nino)
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        val result = doRequest
        result.status shouldBe 404
      }

      // HAPPY PATHS WHEN RELATIONSHIP COPY ATTEMPT FAILS

      "return 200 when relationship exists only in cesa and relationship copy attempt fails because of etmp" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
        givenNinoIsKnownFor(mtdItId, nino)
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenArnIsKnownFor(arn, SaAgentReference("foo"))
        givenClientHasRelationshipWithAgentInCESA(nino, "foo")
        givenAgentCanNotBeAllocated(status = 404)
        givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe empty

        val result = doRequest
        result.status shouldBe 200

        await(repo.findBy(arn, mtdItEnrolmentKey)).get should have(
          Symbol("arn")(arn.value),
          Symbol("enrolmentKey")(Some(mtdItEnrolmentKey)),
          Symbol("references")(Some(Set(SaRef(SaAgentReference("foo"))))),
          Symbol("syncToETMPStatus")(Some(SyncStatus.Failed)),
          Symbol("syncToESStatus")(None)
        )

      }

      "return 200 when relationship exists only in cesa and relationship copy attempt fails because of es" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
        givenNinoIsKnownFor(mtdItId, nino)
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenArnIsKnownFor(arn, SaAgentReference("foo"))
        givenClientHasRelationshipWithAgentInCESA(nino, "foo")
        givenAgentCanBeAllocated(mtdItId, arn)
        givenEnrolmentAllocationFailsWith(404)(
          "foo",
          "any",
          mtdItEnrolmentKey,
          "bar"
        )
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )
        givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
        givenDelegatedGroupIdsExistFor(mtdItSuppEnrolmentKey, Set("foo"))
        givenEnrolmentDeallocationSucceeds("foo", mtdItSuppEnrolmentKey)
        givenAgentCanBeDeallocated(mtdItId, arn)
        givenCacheRefresh(arn)

        await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None

        val result = doRequest
        result.status shouldBe 200

        await(repo.findBy(arn, mtdItEnrolmentKey)).get should have(
          Symbol("arn")(arn.value),
          Symbol("enrolmentKey")(Some(mtdItEnrolmentKey)),
          Symbol("references")(Some(Set(SaRef(SaAgentReference("foo"))))),
          Symbol("syncToETMPStatus")(Some(SyncStatus.Success)),
          Symbol("syncToESStatus")(Some(SyncStatus.Failed))
        )

        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.CreateRelationship,
          detail = Map(
            "agentReferenceNumber" -> arn.value,
            "credId" -> "any",
            "agentCode" -> "bar",
            "nino" -> nino.value,
            "saAgentRef" -> "foo",
            "service" -> "HMRC-MTD-IT",
            "clientId" -> mtdItId.value,
            "clientIdType" -> "mtditid",
            "cesaRelationship" -> "true",
            "etmpRelationshipCreated" -> "true",
            "enrolmentDelegated" -> "false",
            "howRelationshipCreated" -> "CopyExistingCESARelationship"
          ),
          tags = Map("transactionName" -> "create-relationship", "path" -> requestPath)
        )

        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.CheckCESA,
          detail = Map(
            "agentReferenceNumber" -> arn.value,
            "credId" -> "any",
            "agentCode" -> "bar",
            "nino" -> nino.value,
            "saAgentRef" -> "foo",
            "cesaRelationship" -> "true"
          ),
          tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
        )
      }

      "return 404 when relationship is not found in es but relationship copy was made before" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenNinoIsKnownFor(mtdItId, nino)
        givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        await(repo.collection.insertOne(relationshipCopiedSuccessfully).toFuture())
        val result = doRequest
        result.status shouldBe 404
        (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
      }

      "return 404 when relationship was previously copied from CESA to ETMP & ES but has since been deleted from ETMP & ES " +
        "(even though the relationship upon which the copy was based still exists in CESA)" in {
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
          givenAdminUser("foo", "any")

          givenNinoIsKnownFor(mtdItId, nino)
          givenMtdItIdIsKnownFor(nino, mtdItId)
          givenArnIsKnownFor(arn, SaAgentReference("foo"))
          givenClientHasRelationshipWithAgentInCESA(nino, "foo")

          givenAgentCanBeAllocated(mtdItId, arn)
          givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")

          givenUserIsSubscribedAgent(
            arn,
            withThisGroupId = "foo",
            withThisGgUserId = "any",
            withThisAgentCode = "bar"
          )

          await(repo.collection.insertOne(relationshipCopiedSuccessfully).toFuture())
          val result = doRequest
          result.status shouldBe 404
          (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
        }

      "return 404 when mapping service is unavailable" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
        givenNinoIsKnownFor(mtdItId, nino)
        givenClientHasRelationshipWithAgentInCESA(nino, "foo")
        givenServiceReturnsServiceUnavailable()
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        val result = doRequest
        result.status shouldBe 404
      }
    }

    "GET /agent/:arn/service/HMRC-MTD-IT/client/NI/:nino" should {
      val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT/client/NI/${nino.value}"

      def doRequest = doGetRequest(requestPath)

      // HAPPY PATH :-)

      "return 200 when relationship exists in es" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(mtdItEnrolmentKey, "bar")
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        val result = doRequest
        result.status shouldBe 200
      }

      "return 404 when relationship does not exists in es" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenAdminUser("foo", "any")
        givenNinoIsKnownFor(mtdItId, nino)
        givenClientRelationshipWithAgentCeasedInCESA(nino, "baz")
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        val result = doRequest
        result.status shouldBe 404
      }
    }

    "GET /agent/:arn/service/IR-SA/client/ni/:identifierValue" should {

      val requestPath = s"/agent-client-relationships/agent/${arn.value}/service/IR-SA/client/ni/${nino.value}"

      def doRequest = doGetRequest(requestPath)

      // CESA CHECK UNHAPPY PATHS

      "return 404 when agent not allocated to client in es nor identifier not found in des" in {
        givenAgentRecordFound(arn, agentRecordResponse)
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForNino(nino)
        givenNinoIsUnknownFor(mtdItId)
        givenClientHasNoActiveRelationshipWithAgentInCESA(nino)

        val result = doRequest
        result.status shouldBe 404
        (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
      }

      "return 404 when agent not allocated to client in es nor cesa" in {
        givenAgentRecordFound(arn, agentRecordResponse)
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForNino(nino)
        givenNinoIsKnownFor(mtdItId, nino)
        givenClientHasNoActiveRelationshipWithAgentInCESA(nino)

        val result = doRequest
        result.status shouldBe 404
        (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
      }

      "return 404 when mapping is unavailable" in {
        givenAgentRecordFound(arn, agentRecordResponse)
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForNino(nino)
        givenNinoIsKnownFor(mtdItId, nino)
        givenClientHasRelationshipWithAgentInCESA(nino, "foo")
        givenServiceReturnsServiceUnavailable()

        val result = doRequest
        result.status shouldBe 404
      }

      "return 404 when agent not allocated to client in es and also cesa mapping not found" in {
        givenAgentRecordFound(arn, agentRecordResponse)
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForNino(nino)
        givenNinoIsKnownFor(mtdItId, nino)
        givenClientHasRelationshipWithAgentInCESA(nino, "foo")
        givenArnIsUnknownFor(arn)

        val result = doRequest
        result.status shouldBe 404
        (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
      }

      "return 400 when agent record is suspended" in {
        givenAgentRecordFound(arn, suspendedAgentRecordResponse)

        val result = doRequest
        result.status shouldBe 400
      }

      "return 200 when agent credentials unknown but relationship exists in cesa" in {
        givenAgentRecordFound(arn, agentRecordResponse)
        givenPrincipalGroupIdNotExistsFor(agentEnrolmentKey(arn))
        givenArnIsKnownFor(arn, SaAgentReference("foo"))
        givenClientHasRelationshipWithAgentInCESA(nino, "foo")

        val result = doRequest
        result.status shouldBe 200

        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.CheckCESA,
          detail = Map(
            "agentReferenceNumber" -> arn.value,
            "nino" -> nino.value,
            "saAgentRef" -> "foo",
            "cesaRelationship" -> "true"
          ),
          tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
        )
      }

      "return 200 when credentials are not found but relationship exists in cesa and no copy attempt is made" in {
        givenAgentRecordFound(arn, agentRecordResponse)
        givenPrincipalGroupIdNotExistsFor(agentEnrolmentKey(arn))
        givenArnIsKnownFor(arn, SaAgentReference("foo"))
        givenClientHasRelationshipWithAgentInCESA(nino, "foo")
        givenMtdItIdIsUnKnownFor(nino)

        val enrolmentKey = EnrolmentKey("IR-SA", Seq(Identifier("NINO", nino.value)))

        await(repo.findBy(arn, enrolmentKey)) shouldBe None
        val result = doRequest
        result.status shouldBe 200
        await(repo.findBy(arn, enrolmentKey)) shouldBe None

        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.CheckCESA,
          detail = Map(
            "agentReferenceNumber" -> arn.value,
            "nino" -> nino.value,
            "saAgentRef" -> "foo",
            "cesaRelationship" -> "true"
          ),
          tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
        )
      }
    }

    "DELETE /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:mtdItId" when {

      val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT/client/MTDITID/${mtdItId.value}"

      "the relationship exists and the Arn matches that of current Agent user" should {

        trait StubsForThisScenario {
          givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenAgentIsAllocatedAndAssignedToClient(mtdItEnrolmentKey, "bar")
          givenAgentCanBeDeallocated(mtdItId, arn)
          givenEnrolmentDeallocationSucceeds("foo", mtdItEnrolmentKey)
          givenAdminUser("foo", "any")
          givenCacheRefresh(arn)
        }

        "resume an ongoing de-auth if unfinished ES delete record found" in new StubsForThisScenario {
          await(
            deleteRecordRepository.create(
              DeleteRecord(
                arn.value,
                mtdItEnrolmentKey,
                dateTime = LocalDateTime.now.minusMinutes(1),
                syncToETMPStatus = Some(SyncStatus.Success),
                syncToESStatus = Some(SyncStatus.Failed)
              )
            )
          )
          doAgentDeleteRequest(requestPath).status shouldBe 204
          verifyDeleteRecordNotExists
        }

        "resume an ongoing de-auth if unfinished ETMP delete record found" in new StubsForThisScenario {
          await(
            deleteRecordRepository.create(
              DeleteRecord(
                arn.value,
                mtdItEnrolmentKey,
                dateTime = LocalDateTime.now.minusMinutes(1),
                syncToETMPStatus = Some(SyncStatus.Failed)
              )
            )
          )
          doAgentDeleteRequest(requestPath).status shouldBe 204
          verifyDeleteRecordNotExists
        }

        "resume an ongoing de-auth if some delete record found" in new StubsForThisScenario {
          await(
            deleteRecordRepository
              .create(
                DeleteRecord(
                  arn.value,
                  mtdItEnrolmentKey,
                  dateTime = LocalDateTime.now.minusMinutes(1)
                )
              )
          )
          doAgentDeleteRequest(requestPath).status shouldBe 204
          verifyDeleteRecordNotExists
        }
      }

      "es is unavailable" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedAgent(arn)
          givenEsIsUnavailable()
        }

        "return 500" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 500
        }

        "not send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }

        "try to resume unfinished de-auth and keep delete-record around" in new StubsForThisScenario {
          await(
            deleteRecordRepository
              .create(
                DeleteRecord(
                  arn.value,
                  mtdItEnrolmentKey,
                  dateTime = LocalDateTime.now.minusMinutes(1)
                )
              )
          )
          doAgentDeleteRequest(requestPath).status shouldBe 500
          verifyDeleteRecordHasStatuses(None, None)
        }
      }
    }

    "DELETE /agent/:arn/service/HMRC-MTD-IT/client/NI/:nino" when {

      val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT/client/NI/${nino.value}"

      "the relationship exists and the Arn matches that of current Agent user" should {

        trait StubsForThisScenario {
          givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenMtdItIdIsKnownFor(nino, mtdItId)
          givenAgentIsAllocatedAndAssignedToClient(mtdItEnrolmentKey, "bar")
          givenAgentCanBeDeallocated(mtdItId, arn)
          givenEnrolmentDeallocationSucceeds("foo", mtdItEnrolmentKey)
          givenAdminUser("foo", "any")
          givenCacheRefresh(arn)
        }

        "return 204" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 204
          verifyDeleteRecordNotExists
        }

        "send an audit event called TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath)
          verifyTerminateRelationshipAuditSent(
            requestPath,
            arn.value,
            mtdItId.value,
            "MTDITID",
            "HMRC-MTD-IT",
            "AgentLedTermination"
          )
        }
      }

      "the relationship exists and the MtdItId matches that of current Client user" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedClient(nino, withThisGgUserId = "ggUserId-client")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenMtdItIdIsKnownFor(nino, mtdItId)
          givenAgentIsAllocatedAndAssignedToClient(mtdItEnrolmentKey, "bar")
          givenAgentCanBeDeallocated(mtdItId, arn)
          givenEnrolmentDeallocationSucceeds("foo", mtdItEnrolmentKey)
          givenAdminUser("foo", "any")
          givenCacheRefresh(arn)
        }

        "return 204 and send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 204
          verifyTerminateRelationshipAuditSent(
            requestPath,
            arn.value,
            mtdItId.value,
            "MTDITID",
            "HMRC-MTD-IT",
            "ClientLedTermination"
          )
        }
      }

      "the relationship exists and the user is authenticated with Stride" should {
        trait StubsForThisScenario {
          givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-1234456")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenMtdItIdIsKnownFor(nino, mtdItId)
          givenAgentIsAllocatedAndAssignedToClient(mtdItEnrolmentKey, "bar")
          givenAgentCanBeDeallocated(mtdItId, arn)
          givenEnrolmentDeallocationSucceeds("foo", mtdItEnrolmentKey)
          givenAdminUser("foo", "any")
          givenCacheRefresh(arn)
        }

        "return 204 and send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 204
          verifyDeleteRecordNotExists
          verifyTerminateRelationshipAuditSent(
            requestPath,
            arn.value,
            mtdItId.value,
            "MTDITID",
            "HMRC-MTD-IT",
            "HMRCLedTermination"
          )
        }
      }

      "the relationship exists in ETMP and not exist in ES" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedClient(nino, withThisGgUserId = "ggUserId-client")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenMtdItIdIsKnownFor(nino, mtdItId)
          givenPrincipalGroupIdExistsFor(mtdItEnrolmentKey, "clientGroupId")
          givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
          givenAgentCanBeDeallocated(mtdItId, arn)
          givenAdminUser("foo", "any")
        }

        "return 204 and send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 204
          verifyTerminateRelationshipAuditSent(
            requestPath,
            arn.value,
            mtdItId.value,
            "MTDITID",
            "HMRC-MTD-IT",
            "ClientLedTermination",
            enrolmentDeallocated = false
          )
        }
      }

      "the relationship does not exist in either ETMP or in ES" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedClient(nino, withThisGgUserId = "ggUserId-client")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenMtdItIdIsKnownFor(nino, mtdItId)
          givenPrincipalGroupIdExistsFor(mtdItEnrolmentKey, "clientGroupId")
          givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
          givenAgentHasNoActiveRelationship(mtdItId, arn)
          givenAdminUser("foo", "any")
        }

        "return 500 and not send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 500
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }

      "the relationship does not exist in ETMP but does exist in ES" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedClient(nino, withThisGgUserId = "ggUserId-client")
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenMtdItIdIsKnownFor(nino, mtdItId)
          givenAgentIsAllocatedAndAssignedToClient(mtdItEnrolmentKey, "bar")
          givenAgentHasNoActiveRelationship(mtdItId, arn)
          givenEnrolmentDeallocationSucceeds("foo", mtdItEnrolmentKey)
          givenAdminUser("foo", "any")
          givenCacheRefresh(arn)
        }

        "return 204 and send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 204
          verifyTerminateRelationshipAuditSent(
            requestPath,
            arn.value,
            mtdItId.value,
            "MTDITID",
            "HMRC-MTD-IT",
            "ClientLedTermination",
            etmpRelationshipRemoved = false
          )
        }
      }

      /** Agent's Unhappy paths
        */
      "agent has a mismatched arn" should {
        "return 403" in {
          givenUserIsSubscribedAgent(Arn("unmatched"))
          doAgentDeleteRequest(requestPath).status shouldBe 403
          verifyDeleteRecordNotExists
        }

        "not send the audit event TerminateRelationship" in {
          givenUserIsSubscribedAgent(Arn("unmatched"))
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }

      "agent has no agent enrolments" should {
        "return 403" in {
          givenUserHasNoAgentEnrolments(arn)
          doAgentDeleteRequest(requestPath).status shouldBe 403
          verifyDeleteRecordNotExists
        }

        "not send the audit event TerminateRelationship" in {
          givenUserHasNoAgentEnrolments(arn)
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }

      "es is unavailable" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedAgent(arn)
          givenMtdItIdIsKnownFor(nino, mtdItId)
          givenEsIsUnavailable()
        }

        "return 500" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 500
        }

        "not send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }

      // TODO - fix me

      /*      "DES is unavailable" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedAgent(arn)
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenMtdItIdIsKnownFor(nino, mtdItId)
          givenPrincipalGroupIdExistsFor(mtdItEnrolmentKey, "clientGroupId")
          givenAgentIsAllocatedAndAssignedToClient(mtdItEnrolmentKey, "bar")
          givenDesReturnsServiceUnavailable()
          givenCacheRefresh(arn)
        }

        "return 204" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 204
          verifyDeleteRecordNotExists
        }

        "not send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }*/

      "DES responds with 404" should {
        trait StubsForThisScenario {
          givenUserIsSubscribedAgent(arn)
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenMtdItIdIsKnownFor(nino, mtdItId)
          givenPrincipalGroupIdExistsFor(mtdItEnrolmentKey, "clientGroupId")
          givenAgentIsAllocatedAndAssignedToClient(mtdItEnrolmentKey, "bar")
          givenEnrolmentDeallocationSucceeds("foo", mtdItEnrolmentKey)
          givenAgentCanNotBeDeallocated(status = 404)
          givenAdminUser("foo", "any")
          givenCacheRefresh(arn)
        }

        "return 500" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 500
          verifyDeleteRecordHasStatuses(Some(SyncStatus.Failed), Some(SyncStatus.Success))
        }

        "not send the audit event TerminateRelationship" in new StubsForThisScenario {
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }

      /** Client's Unhappy paths
        */
      "client has a mismatched MtdItId" should {

        "return 403" in {
          givenUserIsSubscribedClient(mtdItId)
          givenMtdItIdIsKnownFor(nino, MtdItId("unmatched"))
          doAgentDeleteRequest(requestPath).status shouldBe 403
          verifyDeleteRecordNotExists
        }

        "not send the audit event TerminateRelationship" in {
          givenUserIsSubscribedClient(mtdItId)
          givenMtdItIdIsKnownFor(nino, MtdItId("unmatched"))
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }

      "client has no client enrolments" should {
        "return 403" in {
          givenUserHasNoClientEnrolments
          doAgentDeleteRequest(requestPath).status shouldBe 403
          verifyDeleteRecordNotExists
        }

        "not send the audit event TerminateRelationship" in {
          givenUserHasNoClientEnrolments
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }

      "client has no groupId" should {
        trait StubsForScenario {
          givenUserIsSubscribedClient(nino)
          givenMtdItIdIsKnownFor(nino, mtdItId)
          givenAgentCanBeDeallocated(mtdItId, arn)
          givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), "foo")
          givenAdminUser("foo", "any")
          givenGroupInfo("foo", "bar")
          givenDelegatedGroupIdRequestFailsWith(404)
        }

        "return 204" in new StubsForScenario {
          doAgentDeleteRequest(requestPath).status shouldBe 204
          verifyDeleteRecordNotExists
        }

        "not send the audit event TerminateRelationship" in new StubsForScenario {
          doAgentDeleteRequest(requestPath)
          verifyAuditRequestNotSent(AgentClientRelationshipEvent.TerminateRelationship)
        }
      }
    }

    "GET /agent/:arn/client/:nino/legacy-mapped-relationship" should {
      val requestPath: String = s"/agent-client-relationships/agent/$arn/client/$nino/legacy-mapped-relationship"
      def doRequest = doGetRequest(requestPath)
      val req = FakeRequest()

      "find legacy mapped relationship" in {
        givenAuthorisedAsValidAgent(req, arn.value)
        givenClientHasRelationshipWithAgentInCESA(nino, arn.value)
        givenArnIsKnownFor(arn, SaAgentReference(arn.value))

        val result = doRequest
        result.status shouldBe 204
      }

      "find legacy relationship not mapped" in {
        givenAuthorisedAsValidAgent(req, arn.value)
        givenClientHasRelationshipWithAgentInCESA(nino, arn.value)
        givenArnIsUnknownFor(arn)

        val result = doRequest
        result.status shouldBe 200
      }

      "not find legacy relationship" in {
        givenAuthorisedAsValidAgent(req, arn.value)
        givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
        givenArnIsKnownFor(arn, SaAgentReference(arn.value))

        val result = doRequest
        result.status shouldBe 404
      }
    }
  }
}
