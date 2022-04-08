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

package uk.gov.hmrc.agentclientrelationships.controllers

import org.joda.time.{DateTime, DateTimeZone, LocalDate}
import play.api.libs.json.JodaReads._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.connectors.UserDetails
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
      givenAdminUser("foo", "any")

      def query() = repo.find("arn" -> arn.value, "clientIdentifier" -> nino.value, "clientIdentifierType" -> "NINO")

      await(query()) shouldBe empty
      val result = doRequest
      result.status shouldBe 200
      await(query()) shouldBe empty
    }

    //UNHAPPY PATHS

    "return 404 when credentials are not found in es" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientHasRelationshipWithAgentInCESA(nino, arn.value)

      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent code is not found in ugs" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoNotExists("foo")
      givenDelegatedGroupIdsExistFor(mtdItId, Set("foo"))
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientHasRelationshipWithAgentInCESA(nino, arn.value)
      givenAdminUser("foo", "any")

      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when delete is pending" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
      givenAdminUser("foo", "any")
      givenEnrolmentDeallocationFailsWith(404, "foo", mtdItId)

      await(
        deleteRecordRepository.create(
          DeleteRecord(
            arn.value,
            mtdItId.value,
            "MTDITID",
            DateTime.now(DateTimeZone.UTC),
            Some(SyncStatus.Success),
            Some(SyncStatus.Failed))))

      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_DELETE_PENDING"

      await(deleteRecordRepository.remove(arn, mtdItId))
    }

    "return 404 when admin is not found" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAgentGroupWithUsers("foo",
        List(UserDetails(userId = Some("any"), credentialRole = Some("Assistant")))
      )
      givenClientHasRelationshipWithAgentInCESA(nino, arn.value)
      givenNinoIsKnownFor(mtdItId, nino)

      await(
        deleteRecordRepository.create(
          DeleteRecord(
            arn.value,
            mtdItId.value,
            "MTDITID",
            DateTime.now(DateTimeZone.UTC),
            Some(SyncStatus.Success),
            Some(SyncStatus.Failed))))

      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"

      await(deleteRecordRepository.remove(arn, mtdItId))
    }

    //CESA CHECK UNHAPPY PATHS

    "return 404 when agent not allocated to client in es nor identifier not found in des" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistFor(mtdItId)
      givenNinoIsUnknownFor(mtdItId)
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
      givenAdminUser("foo", "any")

      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in es nor cesa and no alt-itsa" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistFor(mtdItId)
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
      givenAdminUser("foo", "any")
      givenAltItsaUpdate(nino, 200)

      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in es, cesa mapping not found and no alt-itsa" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistFor(mtdItId)
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenArnIsUnknownFor(arn)
      givenAdminUser("foo", "any")
      givenAltItsaUpdate(nino, 200)

      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    //FAILURE CASES

    "return 502 when ES1/principal returns 5xx" in {
      givenPrincipalGroupIdRequestFailsWith(500)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")

      val result = doRequest
      result.status shouldBe 500
    }

    "return 502 when UGS returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(500)
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")

      val result = doRequest
      result.status shouldBe 500
    }

    "return 502 when ES1/delegated returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")

      givenDelegatedGroupIdRequestFailsWith(500)
      val result = doRequest
      result.status shouldBe 500
    }

    "return 400 when ES1/principal returns 4xx" in {
      givenPrincipalGroupIdRequestFailsWith(400)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")

      val result = doRequest
      result.status shouldBe 400
    }

    "return 400 when UGS returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(400)
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")

      val result = doRequest
      result.status shouldBe 400
    }

    "return 400 when ES/delegated returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdRequestFailsWith(400)

      val result = doRequest
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
      givenAgentCanBeAllocatedInIF(mtdItId, arn)
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
      givenAdminUser("foo", "any")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = doRequest
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
      givenAgentCanBeAllocatedInIF(mtdItId, arn)

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = doRequest
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
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")  // Old world
      givenAgentCanBeAllocatedInIF(mtdItId, arn)
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
      givenAdminUser("foo", "any")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = doRequest
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

    // HAPPY PATH FOR ALTERNATIVE-ITSA

    "return 200 when no relationship in CESA but there is an alt-itsa invitation for client" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenNinoIsKnownFor(mtdItId, nino)
      givenArnIsUnknownFor(arn)
      givenClientHasNoRelationshipWithAnyAgentInCESA(nino)
      givenAdminUser("foo", "any")
      givenAltItsaUpdate(nino, 201)

      val result = doRequest
      result.status shouldBe 200
    }

    // UNHAPPY PATH FOR ALTERNATIVE-ITSA

    "return 404 when no relationship in CESA and no alt-itsa invitation for client" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenNinoIsKnownFor(mtdItId, nino)
      givenArnIsUnknownFor(arn)
      givenClientHasNoRelationshipWithAnyAgentInCESA(nino)
      givenAdminUser("foo", "any")
      givenAltItsaUpdate(nino, 200)

      val result = doRequest
      result.status shouldBe 404
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
      givenAgentCanNotBeAllocatedInIF(status = 404)
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
      givenAdminUser("foo", "any")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = doRequest
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
      givenAgentCanBeAllocatedInIF(mtdItId, arn)
      givenEnrolmentAllocationFailsWith(404)("foo", "any", "HMRC-MTD-IT", "MTDITID", mtdItId.value, "bar")
      givenAdminUser("foo", "any")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = doRequest
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
      givenAdminUser("foo", "any")

      await(repo.insert(relationshipCopiedSuccessfully))
      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when relationship was previously copied from CESA to ETMP & ES but has since been deleted from ETMP & ES " +
      "(even though the relationship upon which the copy was based still exists in CESA)" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenAdminUser("foo", "any")

      givenNinoIsKnownFor(mtdItId, nino)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")

      givenAgentCanBeAllocatedInIF(mtdItId, arn)
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")

      await(repo.insert(relationshipCopiedSuccessfully))
      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when credentials are not found but relationship copy was made before" in {
      givenPrincipalGroupIdNotExistsFor(arn)

      await(repo.insert(relationshipCopiedSuccessfully))
      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "UNKNOWN_ARN"
    }

    "return 404 when mapping service is unavailable" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenServiceReturnsServiceUnavailable()
      givenAdminUser("foo", "any")

      val result = doRequest
      result.status shouldBe 404
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
      givenAdminUser("foo", "any")

      val result = doRequest
      result.status shouldBe 200
    }

    "return 404 when relationship does not exists in es" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenAdminUser("foo", "any")
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientRelationshipWithAgentCeasedInCESA(nino, "baz")

      val result = doRequest
      result.status shouldBe 404
    }
  }

  "GET /agent/:arn/service/IR-SA/client/ni/:identifierValue" should {

    val requestPath = s"/agent-client-relationships/agent/${arn.value}/service/IR-SA/client/ni/${nino.value}"

    def doRequest = doAgentGetRequest(requestPath)

    //CESA CHECK UNHAPPY PATHS

    "return 404 when agent not allocated to client in es nor identifier not found in des" in {
      getAgentRecordForClient(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForNino(nino)
      givenNinoIsUnknownFor(mtdItId)
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)

      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in es nor cesa" in {
      getAgentRecordForClient(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForNino(nino)
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)

      val result = doRequest
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when mapping is unavailable" in {
      getAgentRecordForClient(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForNino(nino)
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenServiceReturnsServiceUnavailable()

      val result = doRequest
      result.status shouldBe 404
    }

    "return 404 when agent not allocated to client in es and also cesa mapping not found" in {
      getAgentRecordForClient(arn)
      givenPrincipalUser(arn, "foo")
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
      getSuspendedAgentRecordForClient(arn)

      val result = doRequest
      result.status shouldBe 400
    }

    "return 200 when agent credentials unknown but relationship exists in cesa" in {
      getAgentRecordForClient(arn)
      givenPrincipalGroupIdNotExistsFor(arn)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")

      val result = doRequest
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
      getAgentRecordForClient(arn)
      givenPrincipalGroupIdNotExistsFor(arn)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenMtdItIdIsUnKnownFor(nino)

      def query() = repo.find("arn" -> arn.value, "clientIdentifier" -> nino.value, "clientIdentifierType" -> "NINO")

      await(query()) shouldBe empty
      val result = doRequest
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
        givenAgentCanBeDeallocatedInIF(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send an audit event called ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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
        doAgentDeleteRequest(requestPath).status shouldBe 204
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
        doAgentDeleteRequest(requestPath).status shouldBe 204
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
        doAgentDeleteRequest(requestPath).status shouldBe 204
        verifyDeleteRecordNotExists
      }
    }

    "the relationship exists and the MtdItId matches that of current Client user" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(mtdItId, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
        givenAgentCanBeDeallocatedInIF(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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
        givenAgentCanBeDeallocatedInIF(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send the audit event HmrcRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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
        givenAgentCanBeDeallocatedInIF(mtdItId, arn)
        givenAdminUser("foo", "any")
      }

      "return 404" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 404
        verifyDeleteRecordHasStatuses(None, Some(SyncStatus.IncompleteInputParams))
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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
        givenAgentHasNoActiveRelationshipInIF(mtdItId, arn)
        givenAdminUser("foo", "any")
      }

      "return 404" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 404
        verifyDeleteRecordHasStatuses(None, Some(SyncStatus.IncompleteInputParams))
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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
        givenAgentHasNoActiveRelationshipInIF(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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
        doAgentDeleteRequest(requestPath).status shouldBe 403
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserIsSubscribedAgent(Arn("unmatched"))
        doAgentDeleteRequest(requestPath)
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "agent has no agent enrolments" should {
      "return 403" in {
        givenUserHasNoAgentEnrolments(arn)
        doAgentDeleteRequest(requestPath).status shouldBe 403
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserHasNoAgentEnrolments(arn)
        doAgentDeleteRequest(requestPath)
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "es is unavailable" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenEsIsUnavailable()
      }

      "return 503" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 503
        verifyDeleteRecordHasStatuses(None, Some(SyncStatus.Failed))
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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
        doAgentDeleteRequest(requestPath).status shouldBe 503
        verifyDeleteRecordHasStatuses(None, Some(SyncStatus.Failed))
      }
    }

    "DES is unavailable" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenDesReturnsServiceUnavailable()
        givenAdminUser("foo", "any")
      }

      "return 404" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 404
        verifyDeleteRecordHasStatuses(Some(SyncStatus.Failed), Some(SyncStatus.Success))
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenAgentCanNotBeDeallocatedInIF(status = 404)
        givenAdminUser("foo", "any")
      }

      "return 404" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 404
        verifyDeleteRecordHasStatuses(Some(SyncStatus.Failed), Some(SyncStatus.Success))
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    /**
      * Client's Unhappy paths
      */
    "client has a mismatched MtdItId" should {

      "return 403" in {
        givenUserIsSubscribedClient(MtdItId("unmatched"))
        doAgentDeleteRequest(requestPath).status shouldBe 403
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserIsSubscribedClient(MtdItId("unmatched"))
        doAgentDeleteRequest(requestPath)
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "client has no client enrolments" should {
      "return 403" in {
        givenUserHasNoClientEnrolments
        doAgentDeleteRequest(requestPath).status shouldBe 403
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserHasNoClientEnrolments
        doAgentDeleteRequest(requestPath)
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
        givenAgentCanBeDeallocatedInIF(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send an audit event called ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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
        givenAgentCanBeDeallocatedInIF(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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
        givenAgentCanBeDeallocatedInIF(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send the audit event HmrcRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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
        givenAgentCanBeDeallocatedInIF(mtdItId, arn)
        givenAdminUser("foo", "any")
      }

      "return 404" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 404
        verifyDeleteRecordHasStatuses(None, Some(SyncStatus.IncompleteInputParams))
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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
        givenAgentHasNoActiveRelationshipInIF(mtdItId, arn)
        givenAdminUser("foo", "any")
      }

      "return 404" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 404
        verifyDeleteRecordHasStatuses(None, Some(SyncStatus.IncompleteInputParams))
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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
        givenAgentHasNoActiveRelationshipInIF(mtdItId, arn)
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenAdminUser("foo", "any")
      }

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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
        doAgentDeleteRequest(requestPath).status shouldBe 403
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserIsSubscribedAgent(Arn("unmatched"))
        doAgentDeleteRequest(requestPath)
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "agent has no agent enrolments" should {
      "return 403" in {
        givenUserHasNoAgentEnrolments(arn)
        doAgentDeleteRequest(requestPath).status shouldBe 403
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserHasNoAgentEnrolments(arn)
        doAgentDeleteRequest(requestPath)
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "es is unavailable" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenEsIsUnavailable()
      }

      "return 503" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 503
        verifyDeleteRecordHasStatuses(None, Some(SyncStatus.Failed))
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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

      "return 204" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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
        givenEnrolmentDeallocationSucceeds("foo", mtdItId)
        givenAgentCanNotBeDeallocatedInIF(status = 404)
        givenAdminUser("foo", "any")
      }

      "return 404" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 404
        verifyDeleteRecordHasStatuses(Some(SyncStatus.Failed), Some(SyncStatus.Success))
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        doAgentDeleteRequest(requestPath)
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
        doAgentDeleteRequest(requestPath).status shouldBe 403
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserIsSubscribedClient(mtdItId)
        givenMtdItIdIsKnownFor(nino, MtdItId("unmatched"))
        doAgentDeleteRequest(requestPath)
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "client has no client enrolments" should {
      "return 403" in {
        givenUserHasNoClientEnrolments
        doAgentDeleteRequest(requestPath).status shouldBe 403
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserHasNoClientEnrolments
        doAgentDeleteRequest(requestPath)
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "client has no groupId" should {
      trait StubsForScenario {
        givenUserIsSubscribedClient(nino)
        givenMtdItIdIsKnownFor(nino, mtdItId)
        givenAgentCanBeDeallocatedInIF(mtdItId, arn)
        givenPrincipalGroupIdExistsFor(arn, "foo")
        givenAdminUser("foo", "any")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdRequestFailsWith(404)
      }

      "return 204" in new StubsForScenario {
        doAgentDeleteRequest(requestPath).status shouldBe 204
        verifyDeleteRecordNotExists
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForScenario {
        doAgentDeleteRequest(requestPath)
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }
  }

  "DELETE /test-only/db/agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:mtditid" should {

    val requestPath: String = s"/test-only/db/agent/${arn.value}/service/HMRC-MTD-IT/client/MTDITID/$mtdItId"

    "return 404 for any call" in {

      await(repo.create(RelationshipCopyRecord(arn.value, mtdItId.value, mtdItIdType))) shouldBe 1
      val result = doAgentDeleteRequest(requestPath)
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
      givenAgentCanBeAllocatedInIF(mtdItId, arn)
      givenEnrolmentDeallocationSucceeds("foo", mtdItId)
      givenEnrolmentDeallocationSucceeds("bar", mtdItId)
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
      givenAdminUser("foo", "any")
    }

    "return 201 when the relationship exists and de-allocation of previous relationship fails" in new StubsForThisScenario {
      givenUserIsSubscribedClient(mtdItId)
      givenDelegatedGroupIdsExistForMtdItId(mtdItId, "zoo")
      givenEnrolmentExistsForGroupId("zoo", Arn("zooArn"))
      givenEnrolmentDeallocationFailsWith(502, "zoo", mtdItId)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the MtdItId matches that of current Client user" in new StubsForThisScenario {
      givenUserIsSubscribedClient(mtdItId)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the user is authenticated with Stride" in new StubsForThisScenario {
      givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-983283")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and previous relationships too but ARNs not found" in new StubsForThisScenario {
      givenUserIsSubscribedClient(mtdItId)
      givenDelegatedGroupIdsExistForMtdItId(mtdItId, "zoo")
      givenEnrolmentNotExistsForGroupId("zoo")
      givenEnrolmentDeallocationSucceeds("zoo", mtdItId)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when there are no previous relationships to deallocate" in {
      givenUserIsSubscribedClient(mtdItId)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenAgentCanBeAllocatedInIF(mtdItId, arn)
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
      givenAdminUser("foo", "any")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 201 when an agent tries to create a relationship" in {
      givenUserIsSubscribedAgent(arn)
      givenAgentCanBeAllocatedInIF(mtdItId, arn)
      givenPrincipalGroupIdExistsFor(arn, "foo")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 201
    }

    "return 404 when ES1 is unavailable" in new StubsForThisScenario {
      givenUserIsSubscribedClient(mtdItId)
      givenPrincipalUser(arn, "foo", userId = "user1")
      givenGroupInfo(groupId = "foo", agentCode = "bar")
      givenDelegatedGroupIdRequestFailsWith(503)

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 404
    }

    "return 404 when ES8 is unavailable" in {
      givenUserIsSubscribedClient(mtdItId)
      givenPrincipalUser(arn, "foo", userId = "user1")
      givenGroupInfo(groupId = "foo", agentCode = "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenAgentCanBeAllocatedInIF(mtdItId, arn)
      givenEnrolmentAllocationFailsWith(503)(
        groupId = "foo",
        clientUserId = "user1",
        key = "HMRC-MTD-IT",
        identifier = "MTDITID",
        value = mtdItId.value,
        agentCode = "bar")
      givenAdminUser("foo", "user1")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 404
      (result.json \ "message").asOpt[String] shouldBe None
    }

    "return 404 when DES is unavailable" in {
      givenUserIsSubscribedClient(mtdItId)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenDesReturnsServiceUnavailable()
      givenAdminUser("foo", "any")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 404
      (result.json \ "message").asOpt[String] shouldBe None
    }

    "return 404 if IF returns 404" in {
      givenUserIsSubscribedClient(mtdItId)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenAgentCanNotBeAllocatedInIF(status = 404)
      givenAdminUser("foo", "any")

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 404
      (result.json \ "code").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_IF")
    }

    "return 403 for a client with a mismatched MtdItId" in {
      givenUserIsSubscribedClient(MtdItId("unmatched"))

      val result = doAgentPutRequest(requestPath)
      result.status shouldBe 403
    }

    "return 403 for a client with no client enrolments" in {
      givenUserHasNoClientEnrolments

      val result = doAgentPutRequest(requestPath)
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

      val result = doRequest
      result.status shouldBe 200

      (result.json \ "arn").get.as[String] shouldBe arn.value
      (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
    }

    "find relationship but filter out if the end date has been changed from 9999-12-31" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      givenMtdItIdIsKnownFor(nino, mtdItId)
      getInactiveRelationshipViaClient(mtdItId, arn.value)

      val result = doRequest
      result.status shouldBe 404
    }

    "find multiple relationships but filter out active and ended relationships" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      givenMtdItIdIsKnownFor(nino, mtdItId)
      getSomeActiveRelationshipsViaClient(mtdItId, arn.value, arn2.value, arn3.value)

      val result = doRequest
      result.status shouldBe 200
      (result.json \ "arn").get.as[String] shouldBe arn3.value
      (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
    }

    "return 404 when DES returns 404 relationship not found" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      givenMtdItIdIsKnownFor(nino, mtdItId)
      getActiveRelationshipFailsWith(mtdItId, status = 404)

      val result = doRequest
      result.status shouldBe 404
    }

    "return 404 when DES returns 400 (treated as relationship not found)" in {
      givenAuthorisedAsStrideUser(req, "someStrideId")

      givenMtdItIdIsKnownFor(nino, mtdItId)
      getActiveRelationshipFailsWith(mtdItId, status = 400)

      val result = doRequest
      result.status shouldBe 404
    }
  }

  "GET /agent/:arn/client/:nino/legacy-mapped-relationship" should {
    val requestPath: String = s"/agent-client-relationships/agent/$arn/client/$nino/legacy-mapped-relationship"
    def doRequest = doAgentGetRequest(requestPath)
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
