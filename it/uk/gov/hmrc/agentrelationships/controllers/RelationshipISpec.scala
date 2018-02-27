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

import org.scalatest.mock.MockitoSugar
import org.scalatestplus.play.OneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipReference.{SaRef, VatRef}
import uk.gov.hmrc.agentclientrelationships.repository.{MongoRelationshipCopyRecordRepository, RelationshipCopyRecord, SyncStatus}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.agentrelationships.stubs._
import uk.gov.hmrc.agentrelationships.support._
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.domain.{AgentCode, Nino, SaAgentReference, TaxIdentifier}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class RelationshipISpec extends UnitSpec
  with MongoApp
  with OneServerPerSuite
  with WireMockSupport
  with RelationshipStubs
  with DesStubs
  with MappingStubs
  with DataStreamStub
  with AuthStub
  with MockitoSugar {

  lazy val mockAuthConnector = mock[PlayAuthConnector]
  override implicit lazy val app: Application = appBuilder
    .build()

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
        "microservice.services.tax-enrolments.port" -> wireMockPort,
        "microservice.services.users-groups-search.port" -> wireMockPort,
        "microservice.services.des.port" -> wireMockPort,
        "microservice.services.auth.port" -> wireMockPort,
        "microservice.services.agent-mapping.port" -> wireMockPort,
        "auditing.consumer.baseUri.host" -> wireMockHost,
        "auditing.consumer.baseUri.port" -> wireMockPort,
        "features.copy-relationship.mtd-it" -> true,
        "features.copy-relationship.mtd-vat" -> true)
      .configure(mongoConfiguration)

  def repo = app.injector.instanceOf[MongoRelationshipCopyRecordRepository]

  override def beforeEach() {
    super.beforeEach()
    await(repo.ensureIndexes)
  }

  val arn = Arn("AARN0000002")
  val mtdItId = MtdItId("ABCDEF123456789")
  val nino = Nino("AB123456C")
  val vrn = Vrn("101747641")
  val mtdItIdType = "MTDITID"
  val mtdVatIdType = "MTDVATID"
  val oldAgentCode = "oldAgentCode"
  val testAgentUser = "testAgentUser"
  val testAgentGroup = "testAgentGroup"

  val relationshipCopiedSuccessfully = RelationshipCopyRecord(
    arn.value,
    mtdItId.value,
    "MTDITID",
    syncToETMPStatus = Some(SyncStatus.Success),
    syncToGGStatus = Some(SyncStatus.Success)
  )

  val relationshipCopiedSuccessfullyForMtdVat = RelationshipCopyRecord(
    arn.value,
    vrn.value,
    mtdVatIdType,
    syncToETMPStatus = Some(SyncStatus.Success),
    syncToGGStatus = Some(SyncStatus.Success)
  )

  "GET /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:mtdItId" should {

    val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT/client/MTDITID/${mtdItId.value}"

    def doRequest = doAgentGetRequest(requestPath)

    behave like aCheckEndpoint(true, doRequest)

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
      givenAuditConnector()

      def query() = repo.find("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (mtdItId.value),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaRef(SaAgentReference("foo"))))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToGGStatus (Some(SyncStatus.Success))
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "any",
          "agentCode" -> "bar",
          "nino" -> nino.value,
          "saAgentRef" -> "foo",
          "service" -> "mtd-it",
          "clientId" -> mtdItId.value,
          "clientIdType" -> "ni",
          "CESARelationship" -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated" -> "true",
          "AgentDBRecord" -> "true",
          "Journey" -> "CopyExistingCESARelationship"
        ),
        tags = Map(
          "transactionName" -> "create-relationship",
          "path" -> requestPath
        )
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "any",
          "agentCode" -> "bar",
          "nino" -> nino.value,
          "saAgentRef" -> "foo",
          "CESARelationship" -> "true"
        ),
        tags = Map(
          "transactionName" -> "check-cesa",
          "path" -> requestPath
        )
      )
    }

    "return 200 when agent credentials unknown but relationship exists in cesa" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenNinoIsKnownFor(mtdItId, nino)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenAgentCanBeAllocatedInDes(mtdItId, arn)
      givenAuditConnector()

      def query() = repo.find("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (mtdItId.value),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaRef(SaAgentReference("foo"))))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToGGStatus (Some(SyncStatus.IncompleteInputParams))
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "",
          "agentCode" -> "",
          "nino" -> nino.value,
          "saAgentRef" -> "foo",
          "service" -> "mtd-it",
          "clientId" -> mtdItId.value,
          "clientIdType" -> "ni",
          "CESARelationship" -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated" -> "false",
          "AgentDBRecord" -> "true",
          "Journey" -> "CopyExistingCESARelationship"
        ),
        tags = Map(
          "transactionName" -> "create-relationship",
          "path" -> requestPath
        )
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "",
          "agentCode" -> "",
          "nino" -> nino.value,
          "saAgentRef" -> "foo",
          "CESARelationship" -> "true"
        ),
        tags = Map(
          "transactionName" -> "check-cesa",
          "path" -> requestPath
        )
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
      givenAuditConnector()

      def query() = repo.find("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (mtdItId.value),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaRef(SaAgentReference("foo"))))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToGGStatus (Some(SyncStatus.IncompleteInputParams))
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "any",
          "agentCode" -> "",
          "nino" -> nino.value,
          "saAgentRef" -> "foo",
          "service" -> "mtd-it",
          "clientId" -> mtdItId.value,
          "clientIdType" -> "ni",
          "CESARelationship" -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated" -> "false",
          "AgentDBRecord" -> "true",
          "Journey" -> "CopyExistingCESARelationship"
        ),
        tags = Map(
          "transactionName" -> "create-relationship",
          "path" -> requestPath
        )
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "any",
          "agentCode" -> "",
          "nino" -> nino.value,
          "saAgentRef" -> "foo",
          "CESARelationship" -> "true"
        ),
        tags = Map(
          "transactionName" -> "check-cesa",
          "path" -> requestPath
        )
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
      givenAgentCanNotBeAllocatedInDes
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
      givenAuditConnector()

      def query() = repo.find("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (mtdItId.value),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaRef(SaAgentReference("foo"))))),
        'syncToETMPStatus (Some(SyncStatus.Failed)),
        'syncToGGStatus (None)
      )


    }

    "return 200 when relationship exists only in cesa and relationship copy attempt fails because of gg" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenNinoIsKnownFor(mtdItId, nino)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenAgentCanBeAllocatedInDes(mtdItId, arn)
      givenEnrolmentAllocationFailsWith(404)("foo","any","HMRC-MTD-IT","MTDITID",mtdItId.value,"bar")
      givenAuditConnector()

      def query() = repo.find("arn" -> arn.value, "clientIdentifier" -> mtdItId.value, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (mtdItId.value),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaRef(SaAgentReference("foo"))))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToGGStatus (Some(SyncStatus.Failed))
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "any",
          "agentCode" -> "bar",
          "nino" -> nino.value,
          "saAgentRef" -> "foo",
          "service" -> "mtd-it",
          "clientId" -> mtdItId.value,
          "clientIdType" -> "ni",
          "CESARelationship" -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated" -> "false",
          "AgentDBRecord" -> "true",
          "Journey" -> "CopyExistingCESARelationship"
        ),
        tags = Map(
          "transactionName" -> "create-relationship",
          "path" -> requestPath
        )
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "any",
          "agentCode" -> "bar",
          "nino" -> nino.value,
          "saAgentRef" -> "foo",
          "CESARelationship" -> "true"
        ),
        tags = Map(
          "transactionName" -> "check-cesa",
          "path" -> requestPath
        )
      )
    }

    "return 404 when relationship is not found in es but relationship copy was made before" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenAuditConnector()
      await(repo.insert(relationshipCopiedSuccessfully))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when relationship was previously copied from CESA to ETMP & GG but has since been deleted from ETMP & GG " +
      "(even though the relationship upon which the copy was based still exists in CESA)" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)

      givenNinoIsKnownFor(mtdItId, nino)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")

      givenAgentCanBeAllocatedInDes(mtdItId, arn)
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
      givenAuditConnector()

      await(repo.insert(relationshipCopiedSuccessfully))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when credentials are not found but relationship copy was made before" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenAuditConnector()
      await(repo.insert(relationshipCopiedSuccessfully))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "INVALID_ARN"
    }

    "return 502 when mapping service is unavailable" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenServiceReturnsServiceUnavailable()
      givenAuditConnector()

      val result = await(doRequest)
      result.status shouldBe 502
    }
  }

  "GET /agent/:arn/service/HMRC-MTD-VAT/client/VRN/:vrn" should {

    val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-VAT/client/VRN/${vrn.value}"

    def doRequest = doAgentGetRequest(requestPath)

    //HAPPY PATH :-)

    "return 200 when relationship exists in es" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")
      givenAuditConnector()

      def query() = repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

      await(query()) shouldBe empty
      val result = await(doRequest)
      result.status shouldBe 200
      await(query()) shouldBe empty
    }

    //UNHAPPY PATHS

    "return 404 when credentials are not found in es" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "INVALID_ARN"
    }

    "return 404 when agent code is not found in ugs" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoNotExists("foo")
      givenDelegatedGroupIdsExistFor(vrn, Set("foo"))
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "UNKNOWN_AGENT_CODE"
    }

    //FAILURE CASES

    "return 502 when ES1/principal returns 5xx" in {
      givenPrincipalGroupIdRequestFailsWith(500)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 502 when UGS returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(500)
      givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 502 when ES1/delegated returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAuditConnector()
      givenDelegatedGroupIdRequestFailsWith(500)
      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 400 when ES1/principal returns 4xx" in {
      givenPrincipalGroupIdRequestFailsWith(400)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 400
    }

    "return 400 when UGS returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(400)
      givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 400
    }

    "return 400 when ES1/delegated returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdRequestFailsWith(400)
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 400
    }

    //HAPPY PATHS WHEN CHECKING HMRC-VATDEC-ORG

    "return 200 when agent not allocated to client in es but relationship exists in HMCE-VATDEC-ORG" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
      givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
      givenAgentCanBeAllocatedInDes(vrn, arn)
      givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")
      givenAuditConnector()

      def query() = repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (vrn.value),
        'clientIdentifierType (mtdVatIdType),
        'references (Some(Set(VatRef(AgentCode(oldAgentCode))))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToGGStatus (Some(SyncStatus.Success))
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "any",
          "agentCode" -> "bar",
          "oldAgentCodes" -> oldAgentCode,
          "service" -> "mtd-vat",
          "vrn" -> vrn.value,
          "GGRelationship" -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated" -> "true",
          "AgentDBRecord" -> "true",
          "Journey" -> "CopyExistingGGRelationship"
        ),
        tags = Map(
          "transactionName" -> "create-relationship",
          "path" -> requestPath
        )
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckGG,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "any",
          "agentCode" -> "bar",
          "oldAgentCodes" -> oldAgentCode,
          "vrn" -> vrn.value,
          "GGRelationship" -> "true"
        ),
        tags = Map(
          "transactionName" -> "check-gg",
          "path" -> requestPath
        )
      )
    }

    "return 200 when agent credentials unknown but relationship exists in HMCE-VATDEC-ORG" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
      givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
      givenAgentCanBeAllocatedInDes(vrn, arn)
      givenAuditConnector()

      def query() = repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (vrn.value),
        'clientIdentifierType (mtdVatIdType),
        'references (Some(Set(VatRef(AgentCode(oldAgentCode))))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToGGStatus (Some(SyncStatus.IncompleteInputParams))
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "",
          "agentCode" -> "",
          "service" -> "mtd-vat",
          "vrn" -> vrn.value,
          "oldAgentCodes" -> oldAgentCode,
          "GGRelationship" -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated" -> "false",
          "AgentDBRecord" -> "true",
          "Journey" -> "CopyExistingGGRelationship"
        ),
        tags = Map(
          "transactionName" -> "create-relationship",
          "path" -> requestPath
        )
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckGG,
        detail = Map(
          "arn" -> arn.value,
          "vrn" -> vrn.value,
          "oldAgentCodes" -> oldAgentCode,
          "credId" -> "",
          "agentCode" -> "",
          "GGRelationship" -> "true"
        ),
        tags = Map(
          "transactionName" -> "check-gg",
          "path" -> requestPath
        )
      )
    }

    "return 200 when agent code unknown but relationship exists in HMCE-VATDEC-ORG" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoNotExists("foo")
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
      givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
      givenAgentCanBeAllocatedInDes(vrn, arn)
      givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")
      givenAuditConnector()

      def query() = repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (vrn.value),
        'clientIdentifierType (mtdVatIdType),
        'references (Some(Set(VatRef(AgentCode(oldAgentCode))))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToGGStatus (Some(SyncStatus.IncompleteInputParams))
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "any",
          "agentCode" -> "",
          "service" -> "mtd-vat",
          "vrn" -> vrn.value,
          "oldAgentCodes" -> oldAgentCode,
          "GGRelationship" -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated" -> "false",
          "AgentDBRecord" -> "true",
          "Journey" -> "CopyExistingGGRelationship"
        ),
        tags = Map(
          "transactionName" -> "create-relationship",
          "path" -> requestPath
        )
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckGG,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "any",
          "vrn" -> vrn.value,
          "oldAgentCodes" -> oldAgentCode,
          "agentCode" -> "",
          "GGRelationship" -> "true"
        ),
        tags = Map(
          "transactionName" -> "check-gg",
          "path" -> requestPath
        )
      )

    }

    //HAPPY PATHS WHEN RELATIONSHIP COPY ATTEMPT FAILS

    "return 200 when relationship exists only in HMCE-VATDEC-ORG and relationship copy attempt fails because of etmp" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
      givenAgentCanNotBeAllocatedInDes
      givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")
      givenAuditConnector()

      def query() = repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (vrn.value),
        'clientIdentifierType (mtdVatIdType),
        'references (Some(Set(VatRef(AgentCode(oldAgentCode))))),
        'syncToETMPStatus (Some(SyncStatus.Failed)),
        'syncToGGStatus (None)
      )
    }

    "return 200 when relationship exists only in HMCE-VATDEC-ORG and relationship copy attempt fails because of gg" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
      givenAgentCanBeAllocatedInDes(vrn, arn)
      givenEnrolmentAllocationFailsWith(404)("foo", "any", "HMRC-MTD-VAT", "MTDVATID", vrn.value, "bar")
      givenAuditConnector()

      def query() = repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (vrn.value),
        'clientIdentifierType (mtdVatIdType),
        'references (Some(Set(VatRef(AgentCode(oldAgentCode))))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToGGStatus (Some(SyncStatus.Failed))
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "any",
          "agentCode" -> "bar",
          "service" -> "mtd-vat",
          "vrn" -> vrn.value,
          "oldAgentCodes" -> oldAgentCode,
          "GGRelationship" -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated" -> "false",
          "AgentDBRecord" -> "true",
          "Journey" -> "CopyExistingGGRelationship"
        ),
        tags = Map(
          "transactionName" -> "create-relationship",
          "path" -> requestPath
        )
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckGG,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "any",
          "agentCode" -> "bar",
          "GGRelationship" -> "true",
          "vrn" -> vrn.value,
          "oldAgentCodes" -> oldAgentCode
        ),
        tags = Map(
          "transactionName" -> "check-gg",
          "path" -> requestPath
        )
      )
    }

    "return 404 when relationship is not found in es but relationship copy was made before" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenAuditConnector()
      await(repo.insert(relationshipCopiedSuccessfullyForMtdVat))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when relationship was previously copied from HMCE-VATDEC-ORG to ETMP & GG but has since been deleted from ETMP & GG " +
      "(even though the relationship upon which the copy was based still exists in HMCE-VATDEC-ORG)" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)

      givenAgentCanBeAllocatedInDes(vrn, arn)
      givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")
      givenAuditConnector()

      await(repo.insert(relationshipCopiedSuccessfullyForMtdVat))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when credentials are not found but relationship copy was made before" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenAuditConnector()
      await(repo.insert(relationshipCopiedSuccessfullyForMtdVat))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "INVALID_ARN"
    }

    "return 502 when mapping service is unavailable" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
      givenServiceReturnsServiceUnavailable()
      givenAuditConnector()

      val result = await(doRequest)
      result.status shouldBe 502
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
      givenAuditConnector()
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
      givenAuditConnector()
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
      givenAuditConnector()
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
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 200 when agent credentials unknown but relationship exists in cesa" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenAuditConnector()

      val result = await(doRequest)
      result.status shouldBe 200

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "",
          "agentCode" -> "",
          "nino" -> nino.value,
          "saAgentRef" -> "foo",
          "CESARelationship" -> "true"
        ),
        tags = Map(
          "transactionName" -> "check-cesa",
          "path" -> requestPath
        )
      )
    }

    "return 200 when credentials are not found but relationship exists in cesa and no copy attempt is made" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenMtdItIdIsUnKnownFor(nino)
      givenAuditConnector()

      def query() = repo.find("arn" -> arn.value, "clientIdentifier" -> nino.value, "clientIdentifierType" -> "NINO")

      await(query()) shouldBe empty
      val result = await(doRequest)
      result.status shouldBe 200
      await(query()) shouldBe empty

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn" -> arn.value,
          "credId" -> "",
          "agentCode" -> "",
          "nino" -> nino.value,
          "saAgentRef" -> "foo",
          "CESARelationship" -> "true"
        ),
        tags = Map(
          "transactionName" -> "check-cesa",
          "path" -> requestPath
        )
      )
    }
  }

  "GET /agent/:arn/service/HMCE-VATDEC-ORG/client/vrn/:vrn" should {

    val requestPath = s"/agent-client-relationships/agent/${arn.value}/service/HMCE-VATDEC-ORG/client/vrn/${vrn.value}"

    def doRequest = doAgentGetRequest(requestPath)

    "return 404 when agent not allocated to client in es" in {
      givenDelegatedGroupIdsNotExistForMtdItId(vrn)
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in agent-mapping but allocated in es" in {
      givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")
      givenArnIsUnknownFor(arn)
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 5xx mapping is unavailable" in {
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, "foo")
      givenServiceReturnsServiceUnavailable()
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 200 when agent credentials unknown but relationship exists in mapping" in {
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
      givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
      givenAuditConnector()

      val result = await(doRequest)
      result.status shouldBe 200

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckGG,
        detail = Map(
          "arn" -> arn.value,
          "vrn" -> vrn.value,
          "oldAgentCodes" -> oldAgentCode,
          "credId" -> "",
          "agentCode" -> "",
          "GGRelationship" -> "true"
        ),
        tags = Map(
          "transactionName" -> "check-gg",
          "path" -> requestPath
        )
      )
    }
  }

  "DELETE /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:identifierValue" should {

    val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT/client/MTDITID/${mtdItId.value}"

    "return 204 when the relationship exists and the Arn matches that of current Agent user" in {
      givenUserIsSubscribedAgent(arn)
      writeAuditSucceeds()
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
      givenAgentCanBeDeallocatedInDes(mtdItId, arn)
      givenEnrolmentDeallocationSucceeds("foo",mtdItId,"bar")

      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 204
    }

    "return 204 when the relationship exists and the MtdItId matches that of current Client user" in {
      givenUserIsSubscribedClient(mtdItId)
      writeAuditSucceeds()
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
      givenAgentCanBeDeallocatedInDes(mtdItId, arn)
      //givenAgentCanBeDeallocatedInGovernmentGateway(mtditid, "bar")
      givenEnrolmentDeallocationSucceeds("foo",mtdItId,"bar")

      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 204
    }

    "return 204 when the relationship exists in ETMP and not exist in GG" in {
      givenUserIsSubscribedClient(mtdItId)
      writeAuditSucceeds()
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)

      givenAgentCanBeDeallocatedInDes(mtdItId, arn)

      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 204
    }

    "return 204 when the relationship does not exists in ETMP and in GG" in {
      givenUserIsSubscribedClient(mtdItId)
      writeAuditSucceeds()
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenAgentHasNoActiveRelationshipInDes(mtdItId, arn)

      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 204
    }

    "return 204 when the relationship does not exists in ETMP but exists in GG" in {
      givenUserIsSubscribedClient(mtdItId)
      writeAuditSucceeds()
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtdItId, "bar")
      givenAgentHasNoActiveRelationshipInDes(mtdItId, arn)

      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 204
    }

    /**
      * Agent's Unhappy paths
      */

    "return 403 for an agent with a mismatched arn" in {
      givenUserIsSubscribedAgent(Arn("unmatched"))

      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 403
    }

    "return 403 for an agent with no agent enrolments" in {
      givenUserHasNoAgentEnrolments(arn)

      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 403
    }

    "return 502 when gg is unavailable" in {
      givenUserIsSubscribedAgent(arn)

      givenEsIsUnavailable()
      givenAgentCanBeDeallocatedInDes(mtdItId, arn)

      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 502
    }

    /**
      * Client's Unhappy paths
      */

    "return 403 for a client with a mismatched MtdItId" in {
      givenUserIsSubscribedClient(MtdItId("unmatched"))

      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 403
    }

    "return 403 for a client with no client enrolments" in {
      givenUserHasNoClientEnrolments

      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 403
    }
  }

  private def doAgentGetRequest(route: String) = new Resource(route, port).get()

  private def doAgentPutRequest(route: String) = Http.putEmpty(s"http://localhost:$port$route")(HeaderCarrier())

  private def doAgentDeleteRequest(route: String) = Http.delete(s"http://localhost:$port$route")(HeaderCarrier())

  private def aCheckEndpoint(isMtdItId: Boolean, doRequest: => HttpResponse) = {

    val identifier: TaxIdentifier = if (isMtdItId) mtdItId else nino

    //HAPPY PATH :-)

    "return 200 when relationship exists in es" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      givenAuditConnector()

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
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      givenNinoIsUnknownFor(mtdItId)
      givenClientIsUnknownInCESAFor(nino)
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "INVALID_ARN"
    }

    "return 404 when agent code is not found in ugs" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoNotExists("foo")
      givenDelegatedGroupIdsExistFor(identifier, Set("foo"))
      givenNinoIsUnknownFor(mtdItId)
      givenClientIsUnknownInCESAFor(nino)
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "UNKNOWN_AGENT_CODE"
    }

    //CESA CHECK UNHAPPY PATHS

    "return 404 when agent not allocated to client in es nor identifier not found in des" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(identifier)
      givenNinoIsUnknownFor(mtdItId)
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in es nor cesa" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(identifier)
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in es and also cesa mapping not found" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(identifier)
      givenNinoIsKnownFor(mtdItId, nino)
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenArnIsUnknownFor(arn)
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    //FAILURE CASES


    "return 502 when ES1/principal returns 5xx" in {
      givenPrincipalGroupIdRequestFailsWith(500)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 502 when UGS returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(500)
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 502 when ES1/delegated returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAuditConnector()
      givenDelegatedGroupIdRequestFailsWith(500)
      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 400 when ES1/principal returns 4xx" in {
      givenPrincipalGroupIdRequestFailsWith(400)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 400
    }

    "return 400 when UGS returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(400)
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 400
    }

    "return 400 when ES/delegated returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdRequestFailsWith(400)
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 400
    }
  }

  "DELETE /test-only/db/agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:mtditid" should {

    val requestPath: String = s"/test-only/db/agent/${arn.value}/service/HMRC-MTD-IT/client/MTDITID/$mtdItId"

    "return 404 for any call" in {
      givenAuditConnector()
      await(repo.create(RelationshipCopyRecord(arn.value, mtdItId.value, mtdItIdType))) shouldBe 1
      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 404
    }
  }

  "PUT /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:mtditid" should {

    val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT/client/MTDITID/${mtdItId.value}"

    "return 201 when the relationship exists and the Arn matches that of current Agent user" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenAgentCanBeAllocatedInDes(mtdItId, arn)
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
      givenAuditConnector()

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the MtdItId matches that of current Client user" in {
      givenUserIsSubscribedClient(mtdItId)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenAgentCanBeAllocatedInDes(mtdItId, arn)
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
      givenAuditConnector()

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

    "return 502 when gg is unavailable" in {
      givenUserIsSubscribedAgent(arn)
      givenEsIsUnavailable()

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 502
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

  "PUT /agent/:arn/service/HMRC-MTD-VAT/client/VRN/:vrn" should {
    val vrn = Vrn("101747641")
    val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-VAT/client/VRN/${vrn.value}"

    "return 201 when the relationship exists and the Arn matches that of current Agent user" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenAgentCanBeAllocatedInDes(vrn, arn)
      givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")
      givenAuditConnector()

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the Vrn matches that of current Client user" in {
      givenUserIsSubscribedClient(vrn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenAgentCanBeAllocatedInDes(vrn, arn)
      givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")
      givenAuditConnector()

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

    "return 502 when gg is unavailable" in {
      givenUserIsSubscribedAgent(arn)
      givenEsIsUnavailable()

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 502
    }

    /**
      * Client's Unhappy paths
      */

    "return 403 for a client with a mismatched Vrn" in {
      givenUserIsSubscribedClient(Vrn("unmatched"))

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }

    "return 403 for a client with no client enrolments" in {
      givenUserHasNoClientEnrolments

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }
  }
}

trait RelationshipStubs extends EnrolmentStoreProxyStubs with UsersGroupsSearchStubs {

  def givenPrincipalUser(taxIdentifier: TaxIdentifier, groupId: String, userId: String = "any") = {
    givenPrincipalGroupIdExistsFor(taxIdentifier, groupId)
    givenPrincipalUserIdExistFor(taxIdentifier, userId)
  }

  def givenDelegatedGroupIdsNotExistForMtdItId(mtdItId: MtdItId) = {
    givenDelegatedGroupIdsNotExistFor(mtdItId)
  }

  def givenDelegatedGroupIdsNotExistForNino(nino: Nino) = {
    givenDelegatedGroupIdsNotExistFor(nino)
  }

  def givenDelegatedGroupIdsNotExistForMtdVatId(vrn: Vrn) = {
    givenDelegatedGroupIdsNotExistFor(vrn)
  }

  def givenMTDITEnrolmentAllocationSucceeds(mtdItId: MtdItId, agentCode: String) = {
    givenEnrolmentAllocationSucceeds("foo", "any", "HMRC-MTD-IT", "MTDITID", mtdItId.value, agentCode)
  }

  def givenMTDVATEnrolmentAllocationSucceeds(vrn: Vrn, agentCode: String) = {
    givenEnrolmentAllocationSucceeds("foo", "any", "HMRC-MTD-VAT", "MTDVATID", vrn.value, agentCode)
  }

  def givenAgentIsAllocatedAndAssignedToClient(taxIdentifier: TaxIdentifier, agentCode: String) = {
    givenDelegatedGroupIdsExistFor(taxIdentifier, Set("foo"))
    givenGroupInfo("foo", agentCode)
  }

  def givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn: Vrn, agentCode: String) = {
    givenDelegatedGroupIdsExistForKey(s"HMCE-VATDEC-ORG~VATRegNo~${vrn.value}", Set("oldvatfoo"))
    givenGroupInfo("oldvatfoo", agentCode)
  }

  def givenDelegatedGroupIdsNotExistForMtdItId(taxIdentifier: TaxIdentifier) = {
    givenDelegatedGroupIdsNotExistFor(taxIdentifier)
  }

}