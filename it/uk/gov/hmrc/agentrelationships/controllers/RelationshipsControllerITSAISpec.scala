/*
 * Copyright 2017 HM Revenue & Customs
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

package uk.gov.hmrc.agentrelationships.controllers

import org.joda.time.{DateTime, DateTimeZone, LocalDate}
import play.api.test.FakeRequest
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipReference.SaRef
import uk.gov.hmrc.agentclientrelationships.repository._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.domain.SaAgentReference

import scala.concurrent.ExecutionContext.Implicits.global

//noinspection ScalaStyle
class RelationshipsControllerITSAISpec extends RelationshipsBaseControllerISpec {

  val relationshipCopiedSuccessfully = RelationshipCopyRecord(
    arn.value,
    mtdItId.value,
    mtdItIdType,
    syncToETMPStatus = Some(SyncStatus.Success),
    syncToESStatus = Some(SyncStatus.Success))

  "GET /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:mtdItId" should {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT/client/MTDITID/${mtdItId.value}"

    def doRequest = doAgentGetRequest(requestPath)

    //HAPPY PATH :-)

    "return 200 when relationship exists in es" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
      givenUserIdIsAdmin("any")

      def query() = repo.find("arn" -> arn.value, "clientIdentifier" -> nino.value, "clientIdentifierType" -> "NINO")

      await(query()) shouldBe empty
      val result = await(doRequest)
      result.status shouldBe 200
      await(query()) shouldBe empty
    }

    //UNHAPPY PATHS

    "return 404 when credentials are not found in es" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
      givenNinoIsUnknownFor(mtdItId)
      givenClientIsUnknownInCESAFor(nino)

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "UNKNOWN_ARN"
    }

    "return 404 when agent code is not found in ugs" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoNotExists("foo")
      givenDelegatedGroupIdsExistFor(mtdItId, Set("foo"))
      givenNinoIsUnknownFor(mtdItId)
      givenClientIsUnknownInCESAFor(nino)
      givenUserIdIsAdmin("any")

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "UNKNOWN_AGENT_CODE"
    }

    "return 404 when delete is pending" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
      givenUserIdIsAdmin("any")

      await(
        deleteRecordRepository.create(
          DeleteRecord(
            arn.value,
            mtdItId.value,
            "MTDITID",
            DateTime.now(DateTimeZone.UTC),
            Some(SyncStatus.Success),
            Some(SyncStatus.Failed))))

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_DELETE_PENDING"

      await(deleteRecordRepository.remove(arn, mtdItId))
    }

    "return 404 when admin is not found" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenUserIdIsNotAdmin("any")

      await(
        deleteRecordRepository.create(
          DeleteRecord(
            arn.value,
            mtdItId.value,
            "MTDITID",
            DateTime.now(DateTimeZone.UTC),
            Some(SyncStatus.Success),
            Some(SyncStatus.Failed))))

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "NO_ADMIN_USER"

      await(deleteRecordRepository.remove(arn, mtdItId))
    }

    "return 404 when the user id is unknown" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenUserIdNotExistsFor("any")

      await(
        deleteRecordRepository.create(
          DeleteRecord(
            arn.value,
            mtdItId.value,
            "MTDITID",
            DateTime.now(DateTimeZone.UTC),
            Some(SyncStatus.Success),
            Some(SyncStatus.Failed))))

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "UNKNOWN_USER_ID"

      await(deleteRecordRepository.remove(arn, mtdItId))
    }

    //CESA CHECK UNHAPPY PATHS

    "return 404 when agent not allocated to client in es nor identifier not found in des" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistFor(mtdItId)
      givenNinoIsUnknownFor(mtdItId)
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
      givenUserIdIsAdmin("any")

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in es nor cesa" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistFor(mtdItId)
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
      givenUserIdIsAdmin("any")

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in es and also cesa mapping not found" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistFor(mtdItId)
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenArnIsUnknownFor(arn)
      givenUserIdIsAdmin("any")

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    //FAILURE CASES

    "return 502 when ES1/principal returns 5xx" in {
      givenPrincipalGroupIdRequestFailsWith(500)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")

      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 502 when UGS returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(500)
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")

      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 502 when ES1/delegated returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")

      givenDelegatedGroupIdRequestFailsWith(500)
      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 400 when ES1/principal returns 4xx" in {
      givenPrincipalGroupIdRequestFailsWith(400)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")

      val result = await(doRequest)
      result.status shouldBe 400
    }

    "return 400 when UGS returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(400)
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")

      val result = await(doRequest)
      result.status shouldBe 400
    }

    "return 400 when ES/delegated returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdRequestFailsWith(400)

      val result = await(doRequest)
      result.status shouldBe 400
    }

    //HAPPY PATHS WHEN CHECKING CESA

    "return 200 when agent not allocated to client in es but relationship exists in cesa" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenNinoIsKnownFor(mtdItId, nino)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenAgentCanBeAllocatedInDes(mtdItId, arn)
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
      givenUserIdIsAdmin("any")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (mtdItId.value),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaRef(SaAgentReference("foo"))))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToESStatus (Some(SyncStatus.Success))
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn"                     -> arn.value,
          "credId"                  -> "any",
          "agentCode"               -> "bar",
          "nino"                    -> nino.value,
          "saAgentRef"              -> "foo",
          "service"                 -> "mtd-it",
          "clientId"                -> mtdItId.value,
          "clientIdType"            -> "mtditid",
          "CESARelationship"        -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated"      -> "true",
          "AgentDBRecord"           -> "true",
          "Journey"                 -> "CopyExistingCESARelationship"
        ),
        tags = Map("transactionName" -> "create-relationship", "path" -> requestPath)
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn"                      -> arn.value,
          "credId"                   -> "any",
          "agentCode"                -> "bar",
          "nino"                     -> nino.value,
          "saAgentRef"               -> "foo",
          "CESARelationship"         -> "true"),
        tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
      )
    }

    "return 200 when agent credentials unknown but relationship exists in cesa" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenNinoIsKnownFor(mtdItId, nino)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenAgentCanBeAllocatedInDes(mtdItId, arn)

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (mtdItId.value),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaRef(SaAgentReference("foo"))))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToESStatus (Some(SyncStatus.IncompleteInputParams))
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn"                     -> arn.value,
          "credId"                  -> "",
          "agentCode"               -> "",
          "nino"                    -> nino.value,
          "saAgentRef"              -> "foo",
          "service"                 -> "mtd-it",
          "clientId"                -> mtdItId.value,
          "clientIdType"            -> "mtditid",
          "CESARelationship"        -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated"      -> "false",
          "AgentDBRecord"           -> "true",
          "Journey"                 -> "CopyExistingCESARelationship"
        ),
        tags = Map("transactionName" -> "create-relationship", "path" -> requestPath)
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn"                      -> arn.value,
          "credId"                   -> "",
          "agentCode"                -> "",
          "nino"                     -> nino.value,
          "saAgentRef"               -> "foo",
          "CESARelationship"         -> "true"),
        tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
      )
    }

    "return 200 when agent code unknown but relationship exists in cesa" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoNotExists("foo")
      givenNinoIsKnownFor(mtdItId, nino)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenAgentCanBeAllocatedInDes(mtdItId, arn)
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
      givenUserIdIsAdmin("any")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (mtdItId.value),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaRef(SaAgentReference("foo"))))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToESStatus (Some(SyncStatus.IncompleteInputParams))
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn"                     -> arn.value,
          "credId"                  -> "any",
          "agentCode"               -> "",
          "nino"                    -> nino.value,
          "saAgentRef"              -> "foo",
          "service"                 -> "mtd-it",
          "clientId"                -> mtdItId.value,
          "clientIdType"            -> "mtditid",
          "CESARelationship"        -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated"      -> "false",
          "AgentDBRecord"           -> "true",
          "Journey"                 -> "CopyExistingCESARelationship"
        ),
        tags = Map("transactionName" -> "create-relationship", "path" -> requestPath)
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn"                      -> arn.value,
          "credId"                   -> "any",
          "agentCode"                -> "",
          "nino"                     -> nino.value,
          "saAgentRef"               -> "foo",
          "CESARelationship"         -> "true"),
        tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
      )

    }

    //HAPPY PATHS WHEN RELATIONSHIP COPY ATTEMPT FAILS

    "return 200 when relationship exists only in cesa and relationship copy attempt fails because of etmp" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenNinoIsKnownFor(mtdItId, nino)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenAgentCanNotBeAllocatedInDes(status = 404)
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
      givenUserIdIsAdmin("any")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (mtdItId.value),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaRef(SaAgentReference("foo"))))),
        'syncToETMPStatus (Some(SyncStatus.Failed)),
        'syncToESStatus (None)
      )

    }

    "return 200 when relationship exists only in cesa and relationship copy attempt fails because of es" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenNinoIsKnownFor(mtdItId, nino)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenAgentCanBeAllocatedInDes(mtdItId, arn)
      givenEnrolmentAllocationFailsWith(404)("foo", "any", "HMRC-MTD-IT", "MTDITID", mtdItId.value, "bar")
      givenUserIdIsAdmin("any")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (mtdItId.value),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaRef(SaAgentReference("foo"))))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToESStatus (Some(SyncStatus.Failed))
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn"                     -> arn.value,
          "credId"                  -> "any",
          "agentCode"               -> "bar",
          "nino"                    -> nino.value,
          "saAgentRef"              -> "foo",
          "service"                 -> "mtd-it",
          "clientId"                -> mtdItId.value,
          "clientIdType"            -> "mtditid",
          "CESARelationship"        -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated"      -> "false",
          "AgentDBRecord"           -> "true",
          "Journey"                 -> "CopyExistingCESARelationship"
        ),
        tags = Map("transactionName" -> "create-relationship", "path" -> requestPath)
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn"                      -> arn.value,
          "credId"                   -> "any",
          "agentCode"                -> "bar",
          "nino"                     -> nino.value,
          "saAgentRef"               -> "foo",
          "CESARelationship"         -> "true"),
        tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
      )
    }

    "return 404 when relationship is not found in es but relationship copy was made before" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenUserIdIsAdmin("any")

      await(repo.insert(relationshipCopiedSuccessfully))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when relationship was previously copied from CESA to ETMP & ES but has since been deleted from ETMP & ES " +
      "(even though the relationship upon which the copy was based still exists in CESA)" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenUserIdIsAdmin("any")

      givenNinoIsKnownFor(mtdItId, nino)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")

      givenAgentCanBeAllocatedInDes(mtdItId, arn)
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")

      await(repo.insert(relationshipCopiedSuccessfully))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when credentials are not found but relationship copy was made before" in {
      givenPrincipalGroupIdNotExistsFor(arn)

      await(repo.insert(relationshipCopiedSuccessfully))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "UNKNOWN_ARN"
    }

    "return 502 when mapping service is unavailable" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenServiceReturnsServiceUnavailable()
      givenUserIdIsAdmin("any")

      val result = await(doRequest)
      result.status shouldBe 502
    }
  }

  "GET /agent/:arn/service/HMRC-MTD-IT/client/NI/:nino" should {
    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT/client/NI/${nino.value}"

    def doRequest = doAgentGetRequest(requestPath)

    //HAPPY PATH :-)

    "return 200 when relationship exists in es" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenUserIdIsAdmin("any")

      val result = await(doRequest)
      result.status shouldBe 200
    }

    "return 404 when relationship does not exists in es" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenUserIdIsAdmin("any")

      val result = await(doRequest)
      result.status shouldBe 404
    }
  }

  "GET /agent/:arn/service/IR-SA/client/ni/:identifierValue" should {

    val requestPath = s"/agent-client-relationships/agent/${arn.value}/service/IR-SA/client/ni/${nino.value}"

    def doRequest = doAgentGetRequest(requestPath)

    //CESA CHECK UNHAPPY PATHS

    "return 404 when agent not allocated to client in es nor identifier not found in des" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForNino(nino)
      givenNinoIsUnknownFor(mtdItId)
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in es nor cesa" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForNino(nino)
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 5xx mapping is unavailable" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForNino(nino)
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenServiceReturnsServiceUnavailable()

      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 404 when agent not allocated to client in es and also cesa mapping not found" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForNino(nino)
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenArnIsUnknownFor(arn)

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 200 when agent credentials unknown but relationship exists in cesa" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")

      val result = await(doRequest)
      result.status shouldBe 200

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn"                      -> arn.value,
          "credId"                   -> "",
          "agentCode"                -> "",
          "nino"                     -> nino.value,
          "saAgentRef"               -> "foo",
          "CESARelationship"         -> "true"),
        tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
      )
    }

    "return 200 when credentials are not found but relationship exists in cesa and no copy attempt is made" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenMtdItIdIsUnKnownFor(nino)

      def query() = repo.find("arn" -> arn.value, "clientIdentifier" -> nino.value, "clientIdentifierType" -> "NINO")

      await(query()) shouldBe empty
      val result = await(doRequest)
      result.status shouldBe 200
      await(query()) shouldBe empty

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn"                      -> arn.value,
          "credId"                   -> "",
          "agentCode"                -> "",
          "nino"                     -> nino.value,
          "saAgentRef"               -> "foo",
          "CESARelationship"         -> "true"),
        tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
      )
    }
  }

  "DELETE /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:mtdItId" when {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT/client/MTDITID/${mtdItId.value}"

    def verifyClientRemovedAgentServiceAuthorisationAuditSent(
      arn: String,
      clientId: String,
      clientIdType: String,
      service: String,
      currentUserAffinityGroup: String,
      authProviderId: String,
      authProviderIdType: String) =
      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation,
        detail = Map(
          "agentReferenceNumber"     -> arn,
          "clientId"                 -> clientId,
          "clientIdType"             -> clientIdType,
          "service"                  -> service,
          "currentUserAffinityGroup" -> currentUserAffinityGroup,
          "authProviderId"           -> authProviderId,
          "authProviderIdType"       -> authProviderIdType
        ),
        tags = Map("transactionName" -> "client terminated agent:service authorisation", "path" -> requestPath)
      )

    def verifyHmrcRemovedAgentServiceAuthorisation(
      arn: String,
      clientId: String,
      service: String,
      authProviderId: String,
      authProviderIdType: String) =
      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.HmrcRemovedAgentServiceAuthorisation,
        detail = Map(
          "authProviderId"       -> authProviderId,
          "authProviderIdType"   -> authProviderIdType,
          "agentReferenceNumber" -> arn,
          "clientId"             -> clientId,
          "service"              -> service
        ),
        tags = Map("transactionName" -> "hmrc remove agent:service authorisation", "path" -> requestPath)
      )

    "the relationship exists and the Arn matches that of current Agent user" should {

      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
        givenAgentCanBeDeallocatedInDes(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenUserIdIsAdmin("any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send an audit event called ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          mtdItId.value,
          "MtdItId",
          "HMRC-MTD-IT",
          "Agent",
          "ggUserId-agent",
          "GovernmentGateway")
      }

      "resume an ongoing de-auth if unfinished ES delete record found" in new StubsForThisScenario {
        await(
          deleteRecordRepository.create(
            DeleteRecord(
              arn.value,
              mtdItId.value,
              mtdItIdType,
              DateTime.now.minusMinutes(1),
              Some(SyncStatus.Success),
              Some(SyncStatus.Failed))))
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "resume an ongoing de-auth if unfinished ETMP delete record found" in new StubsForThisScenario {
        await(
          deleteRecordRepository.create(
            DeleteRecord(
              arn.value,
              mtdItId.value,
              mtdItIdType,
              DateTime.now.minusMinutes(1),
              Some(SyncStatus.Failed)
            )))
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "resume an ongoing de-auth if some delete record found" in new StubsForThisScenario {
        await(
          deleteRecordRepository.create(
            DeleteRecord(
              arn.value,
              mtdItId.value,
              mtdItIdType,
              DateTime.now.minusMinutes(1)
            )))
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
        verifyDeleteRecordNotExists
      }
    }

    "the relationship exists and the MtdItId matches that of current Client user" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(mtdItId, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
        givenAgentCanBeDeallocatedInDes(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenUserIdIsAdmin("any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          mtdItId.value,
          "MtdItId",
          "HMRC-MTD-IT",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship exists and the user is authenticated with Stride" should {
      trait StubsForThisScenario {
        givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-1234456")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
        givenAgentCanBeDeallocatedInDes(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenUserIdIsAdmin("any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send the audit event HmrcRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyHmrcRemovedAgentServiceAuthorisation(
          arn.value,
          mtdItId.value,
          "HMRC-MTD-IT",
          "strideId-1234456",
          "PrivilegedApplication")
      }
    }

    "the relationship exists in ETMP and not exist in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(mtdItId, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(mtdItId, "clientGroupId")
        givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
        givenAgentCanBeDeallocatedInDes(mtdItId, arn)
        givenUserIdIsAdmin("any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          mtdItId.value,
          "MtdItId",
          "HMRC-MTD-IT",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship does not exist in either ETMP or in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(mtdItId, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(mtdItId, "clientGroupId")
        givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
        givenAgentHasNoActiveRelationshipInDes(mtdItId, arn)
        givenUserIdIsAdmin("any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          mtdItId.value,
          "MtdItId",
          "HMRC-MTD-IT",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship does not exist in ETMP but does exist in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(mtdItId, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
        givenAgentHasNoActiveRelationshipInDes(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenUserIdIsAdmin("any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          mtdItId.value,
          "MtdItId",
          "HMRC-MTD-IT",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    /**
      * Agent's Unhappy paths
      */
    "agent has a mismatched arn" should {
      "return 403" in {
        givenUserIsSubscribedAgent(Arn("unmatched"))
        await(doAgentDeleteRequest(requestPath)).status shouldBe 403
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserIsSubscribedAgent(Arn("unmatched"))
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "agent has no agent enrolments" should {
      "return 403" in {
        givenUserHasNoAgentEnrolments(arn)
        await(doAgentDeleteRequest(requestPath)).status shouldBe 403
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserHasNoAgentEnrolments(arn)
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "es is unavailable" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenEsIsUnavailable()
        givenAgentCanBeDeallocatedInDes(mtdItId, arn)
      }

      "return 502" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 502
        verifyDeleteRecordHasStatuses(Some(SyncStatus.Success), Some(SyncStatus.Failed))
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }

      "try to resume unfinished de-auth and keep delete-record around" in new StubsForThisScenario {
        await(
          deleteRecordRepository.create(
            DeleteRecord(
              arn.value,
              mtdItId.value,
              mtdItIdType,
              DateTime.now.minusMinutes(1)
            )))
        await(doAgentDeleteRequest(requestPath)).status shouldBe 502
        verifyDeleteRecordHasStatuses(Some(SyncStatus.Success), Some(SyncStatus.Failed))
      }
    }

    "DES is unavailable" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(mtdItId, "clientGroupId")
        givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
        givenDesReturnsServiceUnavailable()
      }

      "return 502" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 502
        verifyDeleteRecordHasStatuses(Some(SyncStatus.Failed), None)
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "DES responds with 404" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(mtdItId, "clientGroupId")
        givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
        givenAgentCanNotBeDeallocatedInDes(status = 404)
      }

      "return 404" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 404
        verifyDeleteRecordHasStatuses(Some(SyncStatus.Failed), None)
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    /**
      * Client's Unhappy paths
      */
    "client has a mismatched MtdItId" should {

      "return 403" in {
        givenUserIsSubscribedClient(MtdItId("unmatched"))
        await(doAgentDeleteRequest(requestPath)).status shouldBe 403
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserIsSubscribedClient(MtdItId("unmatched"))
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "client has no client enrolments" should {
      "return 403" in {
        givenUserHasNoClientEnrolments
        await(doAgentDeleteRequest(requestPath)).status shouldBe 403
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserHasNoClientEnrolments
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }
  }

  "DELETE /agent/:arn/service/HMRC-MTD-IT/client/NI/:nino" when {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT/client/NI/${nino.value}"

    def verifyClientRemovedAgentServiceAuthorisationAuditSent(
      arn: String,
      clientId: String,
      clientIdType: String,
      service: String,
      currentUserAffinityGroup: String,
      authProviderId: String,
      authProviderIdType: String) =
      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation,
        detail = Map(
          "agentReferenceNumber"     -> arn,
          "clientId"                 -> clientId,
          "clientIdType"             -> clientIdType,
          "service"                  -> service,
          "currentUserAffinityGroup" -> currentUserAffinityGroup,
          "authProviderId"           -> authProviderId,
          "authProviderIdType"       -> authProviderIdType
        ),
        tags = Map("transactionName" -> "client terminated agent:service authorisation", "path" -> requestPath)
      )

    def verifyHmrcRemovedAgentServiceAuthorisation(
      arn: String,
      clientId: String,
      service: String,
      authProviderId: String,
      authProviderIdType: String) =
      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.HmrcRemovedAgentServiceAuthorisation,
        detail = Map(
          "authProviderId"       -> authProviderId,
          "authProviderIdType"   -> authProviderIdType,
          "agentReferenceNumber" -> arn,
          "clientId"             -> clientId,
          "service"              -> service
        ),
        tags = Map("transactionName" -> "hmrc remove agent:service authorisation", "path" -> requestPath)
      )

    "the relationship exists and the Arn matches that of current Agent user" should {

      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
        givenAgentCanBeDeallocatedInDes(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenUserIdIsAdmin("any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send an audit event called ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          mtdItId.value,
          "MtdItId",
          "HMRC-MTD-IT",
          "Agent",
          "ggUserId-agent",
          "GovernmentGateway")
      }
    }

    "the relationship exists and the MtdItId matches that of current Client user" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(nino, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
        givenAgentCanBeDeallocatedInDes(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenUserIdIsAdmin("any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          mtdItId.value,
          "MtdItId",
          "HMRC-MTD-IT",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship exists and the user is authenticated with Stride" should {
      trait StubsForThisScenario {
        givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-1234456")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
        givenAgentCanBeDeallocatedInDes(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenUserIdIsAdmin("any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send the audit event HmrcRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyHmrcRemovedAgentServiceAuthorisation(
          arn.value,
          mtdItId.value,
          "HMRC-MTD-IT",
          "strideId-1234456",
          "PrivilegedApplication")
      }
    }

    "the relationship exists in ETMP and not exist in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(nino, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenPrincipalGroupIdExistsFor(mtdItId, "clientGroupId")
        givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
        givenAgentCanBeDeallocatedInDes(mtdItId, arn)
        givenUserIdIsAdmin("any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          mtdItId.value,
          "MtdItId",
          "HMRC-MTD-IT",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship does not exist in either ETMP or in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(nino, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenPrincipalGroupIdExistsFor(mtdItId, "clientGroupId")
        givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
        givenAgentHasNoActiveRelationshipInDes(mtdItId, arn)
        givenUserIdIsAdmin("any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          mtdItId.value,
          "MtdItId",
          "HMRC-MTD-IT",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship does not exist in ETMP but does exist in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(nino, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
        givenAgentHasNoActiveRelationshipInDes(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenUserIdIsAdmin("any")
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          mtdItId.value,
          "MtdItId",
          "HMRC-MTD-IT",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    /**
      * Agent's Unhappy paths
      */
    "agent has a mismatched arn" should {
      "return 403" in {
        givenUserIsSubscribedAgent(Arn("unmatched"))
        await(doAgentDeleteRequest(requestPath)).status shouldBe 403
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserIsSubscribedAgent(Arn("unmatched"))
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "agent has no agent enrolments" should {
      "return 403" in {
        givenUserHasNoAgentEnrolments(arn)
        await(doAgentDeleteRequest(requestPath)).status shouldBe 403
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserHasNoAgentEnrolments(arn)
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "es is unavailable" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenEsIsUnavailable()
        givenAgentCanBeDeallocatedInDes(mtdItId, arn)
      }

      "return 502" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 502
        verifyDeleteRecordHasStatuses(Some(SyncStatus.Success), Some(SyncStatus.Failed))
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "DES is unavailable" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenPrincipalGroupIdExistsFor(mtdItId, "clientGroupId")
        givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
        givenDesReturnsServiceUnavailable()
      }

      "return 502" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 502
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "DES responds with 404" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenPrincipalGroupIdExistsFor(mtdItId, "clientGroupId")
        givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
        givenAgentCanNotBeDeallocatedInDes(status = 404)
      }

      "return 404" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 404
        verifyDeleteRecordHasStatuses(Some(SyncStatus.Failed), None)
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    /**
      * Client's Unhappy paths
      */
    "client has a mismatched MtdItId" should {

      "return 403" in {
        givenUserIsSubscribedClient(mtdItId)
        givenMtdItIdIsKnownFor(nino, MtdItId("unmatched"))
        await(doAgentDeleteRequest(requestPath)).status shouldBe 403
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserIsSubscribedClient(mtdItId)
        givenMtdItIdIsKnownFor(nino, MtdItId("unmatched"))
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "client has no client enrolments" should {
      "return 403" in {
        givenUserHasNoClientEnrolments
        await(doAgentDeleteRequest(requestPath)).status shouldBe 403
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserHasNoClientEnrolments
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "client has no groupId" should {
      trait StubsForScenario {
        givenUserIsSubscribedClient(nino)
        givenPrincipalGroupIdNotExistsFor(mtdItId)
      }

      "return 404" in new StubsForScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 404
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }
  }

  "DELETE /test-only/db/agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:mtditid" should {

    val requestPath: String = s"/test-only/db/agent/${arn.value}/service/HMRC-MTD-IT/client/MTDITID/$mtdItId"

    "return 404 for any call" in {

      await(repo.create(RelationshipCopyRecord(arn.value, mtdItId.value, mtdItIdType))) shouldBe 1
      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 404
    }
  }

  "PUT /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:mtditid" should {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT/client/MTDITID/${mtdItId.value}"

    trait StubsForThisScenario {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenEnrolmentExistsForGroupId("bar", Arn("barArn"))
      givenEnrolmentExistsForGroupId("foo", Arn("fooArn"))
      givenDelegatedGroupIdsExistForMtdItId(mtdItId)
      givenAgentCanBeAllocatedInDes(mtdItId, arn)
      givenEnrolmentDeallocationSucceeds("foo", mtdItId)
      givenEnrolmentDeallocationSucceeds("bar", mtdItId)
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
      givenUserIdIsAdmin("any")
    }

    "return 201 when the relationship exists and the Arn matches that of current Agent user" in new StubsForThisScenario {
      givenUserIsSubscribedAgent(arn)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and de-allocation of previous relationship fails" in new StubsForThisScenario {
      givenUserIsSubscribedAgent(arn)
      givenDelegatedGroupIdsExistForMtdItId(mtdItId, "zoo")
      givenEnrolmentExistsForGroupId("zoo", Arn("zooArn"))
      givenEnrolmentDeallocationFailsWith(502, "zoo", mtdItId)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the MtdItId matches that of current Client user" in new StubsForThisScenario {
      givenUserIsSubscribedClient(mtdItId)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the user is authenticated with Stride" in new StubsForThisScenario {
      givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-983283")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and previous relationships too but ARNs not found" in new StubsForThisScenario {
      givenUserIsSubscribedAgent(arn)
      givenDelegatedGroupIdsExistForMtdItId(mtdItId, "zoo")
      givenEnrolmentNotExistsForGroupId("zoo")
      givenEnrolmentDeallocationSucceeds("zoo", mtdItId)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when there are no previous relationships to deallocate" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenAgentCanBeAllocatedInDes(mtdItId, arn)
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
      givenUserIdIsAdmin("any")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    /**
      * Agent's Unhappy paths
      */
    "return 403 for an agent with a mismatched arn" in {
      givenUserIsSubscribedAgent(Arn("unmatched"))

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }

    "return 403 for an agent with no agent enrolments" in {
      givenUserHasNoAgentEnrolments(arn)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }

    "return 502 when ES1 is unavailable" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo", userId = "user1")
      givenGroupInfo(groupId = "foo", agentCode = "bar")
      givenDelegatedGroupIdRequestFailsWith(503)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 502
    }

    "return 502 when ES8 is unavailable" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo", userId = "user1")
      givenGroupInfo(groupId = "foo", agentCode = "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenAgentCanBeAllocatedInDes(mtdItId, arn)
      givenEnrolmentAllocationFailsWith(503)(
        groupId = "foo",
        clientUserId = "user1",
        key = "HMRC-MTD-IT",
        identifier = "MTDITID",
        value = mtdItId.value,
        agentCode = "bar")
      givenUserIdIsAdmin("user1")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 502
      (result.json \ "message").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_ES")
    }

    "return 502 when DES is unavailable" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenDesReturnsServiceUnavailable()
      givenUserIdIsAdmin("any")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 502
      (result.json \ "message").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_DES")
    }

    "return 404 if DES returns 404" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenAgentCanNotBeAllocatedInDes(status = 404)
      givenUserIdIsAdmin("any")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 404
      (result.json \ "code").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_DES")
    }

    /**
      * Client's Unhappy paths
      */
    "return 403 for a client with a mismatched MtdItId" in {
      givenUserIsSubscribedClient(MtdItId("unmatched"))

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }

    "return 403 for a client with no client enrolments" in {
      givenUserHasNoClientEnrolments

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }
  }

  "GET /relationships/service/HMRC-MTD-IT/client/NI/:nino" should {

    val requestPath: String = s"/agent-client-relationships/relationships/service/HMRC-MTD-IT/client/NI/$nino"

    def doRequest = doAgentGetRequest(requestPath)
    val req = FakeRequest()

    "find relationship and send back Json" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      givenMtdItIdIsKnownFor(nino, mtdItId)
      getActiveRelationshipsViaClient(mtdItId, arn)

      val result = await(doRequest)
      result.status shouldBe 200

      val b = result.json
      (result.json \ "arn").get.as[String] shouldBe arn.value
      (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
    }

    "find relationship but filter out if the end date has been changed from 9999-12-31" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      givenMtdItIdIsKnownFor(nino, mtdItId)
      getInactiveRelationshipViaClient(mtdItId, arn.value)

      val result = await(doRequest)
      result.status shouldBe 404
    }

    "find multiple relationships but filter out active and ended relationships" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      givenMtdItIdIsKnownFor(nino, mtdItId)
      getSomeActiveRelationshipsViaClient(mtdItId, arn.value, arn2.value, arn3.value)

      val result = await(doRequest)
      result.status shouldBe 200
      (result.json \ "arn").get.as[String] shouldBe arn3.value
      (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
    }

    "return 404 when DES returns 404 relationship not found" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      givenMtdItIdIsKnownFor(nino, mtdItId)
      getActiveRelationshipFailsWith(mtdItId, status = 404)

      val result = await(doRequest)
      result.status shouldBe 404
    }

    "return 404 when DES returns 400 (treated as relationship not found)" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      givenMtdItIdIsKnownFor(nino, mtdItId)
      getActiveRelationshipFailsWith(mtdItId, status = 400)

      val result = await(doRequest)
      result.status shouldBe 404
    }
  }

}
