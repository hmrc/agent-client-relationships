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

import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.repository.{RelationshipCopyRecord, RelationshipCopyRecordRepository, SyncStatus}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.agentrelationships.stubs._
import uk.gov.hmrc.agentrelationships.support.{MongoApp, Resource, WireMockSupport}
import uk.gov.hmrc.domain.{Nino, SaAgentReference}
import uk.gov.hmrc.play.http.HttpResponse
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class RelationshipISpec extends UnitSpec
  with MongoApp
  with WireMockSupport
  with GovernmentGatewayProxyStubs
  with DesStubs
  with MappingStubs
  with DataStreamStub {

  override implicit lazy val app: Application = appBuilder
    .build()

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.government-gateway-proxy.port" -> wireMockPort,
        "microservice.services.des.port" -> wireMockPort,
        "microservice.services.auth.port" -> wireMockPort,
        "microservice.services.agent-mapping.port" -> wireMockPort,
        "auditing.consumer.baseUri.host" -> wireMockHost,
        "auditing.consumer.baseUri.port" -> wireMockPort)
      .configure(mongoConfiguration)

  def repo = app.injector.instanceOf[RelationshipCopyRecordRepository]

  override def beforeEach() {
    super.beforeEach()
    await(repo.ensureIndexes)
  }

  val arn = "AARN0000002"
  val mtditid = "ABCDEF123456789"
  val nino = "AB123456C"

  "GET /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:identifierValue" should {

    val requestPath: String = s"/agent-client-relationships/agent/$arn/service/HMRC-MTD-IT/client/MTDITID/$mtditid"

    def doRequest = doAgentRequest(requestPath)

    behave like aCheckEndpoint(true, doRequest)

    val mtdItIdType: String = "MTDITID"

    //HAPPY PATHS WHEN CHECKING CESA

    "return 200 when agent not allocated to client in gg but relationship exists in cesa" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenMtdItIdIsKnownFor(Nino(nino), MtdItId(mtditid))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgent(Nino(nino), "foo")
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAgentCanBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()

      def query = repo.find("arn" -> arn, "clientIdentifier" -> mtditid, "clientIdentifierType" -> mtdItIdType)

      await(query) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query).head should have(
        'arn (arn),
        'clientIdentifier (mtditid),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaAgentReference("foo")))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToGGStatus (Some(SyncStatus.Success))
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CopyRelationship,
        detail = Map(
          "arn" -> arn,
          "credId" -> "foo",
          "agentCode" -> "bar",
          "nino" -> nino,
          "saAgentRef" -> "foo",
          "regime" -> "mtd-it",
          "regimeId" -> mtditid,
          "CESARelationship" -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated" -> "true"
        ),
        tags = Map(
          "transactionName"->"copy-relationship",
          "path" -> requestPath
        )
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn" -> arn,
          "credId" -> "foo",
          "agentCode" -> "bar",
          "nino" -> nino,
          "saAgentRef" -> "foo",
          "CESARelationship" -> "true"
        ),
        tags = Map(
          "transactionName"->"check-cesa",
          "path" -> requestPath
        )
      )
    }

    "return 200 when agent credentials unknown but relationship exists in cesa" in {
      givenAgentCredentialsAreNotFoundFor(Arn(arn))
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenMtdItIdIsKnownFor(Nino(nino), MtdItId(mtditid))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgent(Nino(nino), "foo")
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAuditConnector()

      def query = repo.find("arn" -> arn, "clientIdentifier" -> mtditid, "clientIdentifierType" -> mtdItIdType)

      await(query) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query).head should have(
        'arn (arn),
        'clientIdentifier (mtditid),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaAgentReference("foo")))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToGGStatus (Some(SyncStatus.IncompleteInputParams))
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CopyRelationship,
        detail = Map(
          "arn" -> arn,
          "credId" -> "",
          "agentCode" -> "",
          "nino" -> nino,
          "saAgentRef" -> "foo",
          "regime" -> "mtd-it",
          "regimeId" -> mtditid,
          "CESARelationship" -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated" -> "false"
        ),
        tags = Map(
          "transactionName"->"copy-relationship",
          "path" -> requestPath
        )
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn" -> arn,
          "credId" -> "",
          "agentCode" -> "",
          "nino" -> nino,
          "saAgentRef" -> "foo",
          "CESARelationship" -> "true"
        ),
        tags = Map(
          "transactionName"->"check-cesa",
          "path" -> requestPath
        )
      )
    }

    "return 200 when agent code unknown but relationship exists in cesa" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsNotInTheResponseFor("foo")
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenMtdItIdIsKnownFor(Nino(nino), MtdItId(mtditid))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgent(Nino(nino), "foo")
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAgentCanBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()

      def query = repo.find("arn" -> arn, "clientIdentifier" -> mtditid, "clientIdentifierType" -> mtdItIdType)

      await(query) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query).head should have(
        'arn (arn),
        'clientIdentifier (mtditid),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaAgentReference("foo")))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToGGStatus (Some(SyncStatus.IncompleteInputParams))
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CopyRelationship,
        detail = Map(
          "arn" -> arn,
          "credId" -> "foo",
          "agentCode" -> "",
          "nino" -> nino,
          "saAgentRef" -> "foo",
          "regime" -> "mtd-it",
          "regimeId" -> mtditid,
          "CESARelationship" -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated" -> "false"
        ),
        tags = Map(
          "transactionName"->"copy-relationship",
          "path" -> requestPath
        )
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn" -> arn,
          "credId" -> "foo",
          "agentCode" -> "",
          "nino" -> nino,
          "saAgentRef" -> "foo",
          "CESARelationship" -> "true"
        ),
        tags = Map(
          "transactionName"->"check-cesa",
          "path" -> requestPath
        )
      )

    }

    //HAPPY PATHS WHEN RELATIONSHIP COPY ATTEMPT FAILS

    "return 200 when relationship exists only in cesa and relationship copy attempt fails because of etmp" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenMtdItIdIsKnownFor(Nino(nino), MtdItId(mtditid))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgent(Nino(nino), "foo")
      givenAgentCanNotBeAllocatedInDes
      givenAgentCanBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()

      def query = repo.find("arn" -> arn, "clientIdentifier" -> mtditid, "clientIdentifierType" -> mtdItIdType)

      await(query) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query).head should have(
        'arn (arn),
        'clientIdentifier (mtditid),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaAgentReference("foo")))),
        'syncToETMPStatus (Some(SyncStatus.Failed)),
        'syncToGGStatus (None)
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CopyRelationship,
        detail = Map(
          "arn" -> arn,
          "credId" -> "foo",
          "agentCode" -> "bar",
          "nino" -> nino,
          "saAgentRef" -> "foo",
          "regime" -> "mtd-it",
          "regimeId" -> mtditid,
          "CESARelationship" -> "true",
          "etmpRelationshipCreated" -> "false",
          "enrolmentDelegated" -> "false"
        ),
        tags = Map(
          "transactionName"->"copy-relationship",
          "path" -> requestPath
        )
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn" -> arn,
          "credId" -> "foo",
          "agentCode" -> "bar",
          "nino" -> nino,
          "saAgentRef" -> "foo",
          "CESARelationship" -> "true"
        ),
        tags = Map(
          "transactionName"->"check-cesa",
          "path" -> requestPath
        )
      )
    }

    "return 200 when relationship exists only in cesa and relationship copy attempt fails because of gg" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenMtdItIdIsKnownFor(Nino(nino), MtdItId(mtditid))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgent(Nino(nino), "foo")
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAgentCannotBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()

      def query = repo.find("arn" -> arn, "clientIdentifier" -> mtditid, "clientIdentifierType" -> mtdItIdType)

      await(query) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query).head should have(
        'arn (arn),
        'clientIdentifier (mtditid),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaAgentReference("foo")))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToGGStatus (Some(SyncStatus.Failed))
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CopyRelationship,
        detail = Map(
          "arn" -> arn,
          "credId" -> "foo",
          "agentCode" -> "bar",
          "nino" -> nino,
          "saAgentRef" -> "foo",
          "regime" -> "mtd-it",
          "regimeId" -> mtditid,
          "CESARelationship" -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated" -> "false"
        ),
        tags = Map(
          "transactionName"->"copy-relationship",
          "path" -> requestPath
        )
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn" -> arn,
          "credId" -> "foo",
          "agentCode" -> "bar",
          "nino" -> nino,
          "saAgentRef" -> "foo",
          "CESARelationship" -> "true"
        ),
        tags = Map(
          "transactionName"->"check-cesa",
          "path" -> requestPath
        )
      )
    }
  }

  "GET /agent/:arn/service/IR-SA/client/ni/:identifierValue" should {

    val requestPath = s"/agent-client-relationships/agent/$arn/service/IR-SA/client/ni/$nino"
    def doRequest = doAgentRequest(requestPath)

    behave like aCheckEndpoint(false, doRequest)

    "return 200 when agent not allocated to client in gg but relationship exists in cesa" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(nino)
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgent(Nino(nino), "foo")

      val result = await(doRequest)
      result.status shouldBe 200

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn" -> arn,
          "credId" -> "foo",
          "agentCode" -> "bar",
          "nino" -> nino,
          "saAgentRef" -> "foo",
          "CESARelationship" -> "true"
        ),
        tags = Map(
          "transactionName"->"check-cesa",
          "path" -> requestPath
        )
      )
    }

    "return 200 when agent credentials unknown but relationship exists in cesa" in {
      givenAgentCredentialsAreNotFoundFor(Arn(arn))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgent(Nino(nino), "foo")

      val result = await(doRequest)
      result.status shouldBe 200

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn" -> arn,
          "credId" -> "",
          "agentCode" -> "",
          "nino" -> nino,
          "saAgentRef" -> "foo",
          "CESARelationship" -> "true"
        ),
        tags = Map(
          "transactionName"->"check-cesa",
          "path" -> requestPath
        )
      )
    }

    "return 200 when agent code unknown but relationship exists in cesa" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsNotInTheResponseFor("foo")
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgent(Nino(nino), "foo")

      val result = await(doRequest)
      result.status shouldBe 200

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn" -> arn,
          "credId" -> "foo",
          "agentCode" -> "",
          "nino" -> nino,
          "saAgentRef" -> "foo",
          "CESARelationship" -> "true"
        ),
        tags = Map(
          "transactionName"->"check-cesa",
          "path" -> requestPath
        )
      )
    }

    "return 200 when credentials are not found but relationship exists in cesa and no copy attempt is made" in {
      givenAgentCredentialsAreNotFoundFor(Arn(arn))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgent(Nino(nino), "foo")
      givenMtdItIdIsUnKnownFor(Nino(nino))
      def query = repo.find("arn" -> arn, "clientIdentifier" -> nino, "clientIdentifierType" -> "NINO")
      await(query) shouldBe empty
      val result = await(doRequest)
      result.status shouldBe 200
      await(query) shouldBe empty

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn" -> arn,
          "credId" -> "",
          "agentCode" -> "",
          "nino" -> nino,
          "saAgentRef" -> "foo",
          "CESARelationship" -> "true"
        ),
        tags = Map(
          "transactionName"->"check-cesa",
          "path" -> requestPath
        )
      )
    }
  }

  private def doAgentRequest(route: String) = new Resource(route, port).get()

  private def aCheckEndpoint(isMtdItId: Boolean, doRequest: => HttpResponse) = {

    val identifier: String = if (isMtdItId) mtditid else nino
    val identifierType: String = if (isMtdItId) "MTDITID" else "NINO"

    //HAPPY PATH :-)

    "return 200 when relationship exists in gg" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      def query = repo.find("arn" -> arn, "clientIdentifier" -> nino, "clientIdentifierType" -> "NINO")
      await(query) shouldBe empty
      val result = await(doRequest)
      result.status shouldBe 200
      await(query) shouldBe empty
    }

    //UNHAPPY PATHS

    "return 404 when credentials are not found in gg" in {
      givenAgentCredentialsAreNotFoundFor(Arn(arn))
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      givenNinoIsUnknownFor(MtdItId(mtditid))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "INVALID_ARN"
    }

    "return 404 when agent code is not found in gg" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsNotInTheResponseFor("foo")
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      givenNinoIsUnknownFor(MtdItId(mtditid))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "UNKNOWN_AGENT_CODE"
    }

    "return 404 when relationship is not found in gg but relationship copy was made before" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(identifier)
      await(repo.insert(RelationshipCopyRecord(arn,identifier,identifierType)))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when credentials are not found but relationship copy was made before" in {
      givenAgentCredentialsAreNotFoundFor(Arn(arn))
      await(repo.insert(RelationshipCopyRecord(arn,identifier,identifierType)))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "INVALID_ARN"
    }

    //CESA CHECK UNHAPPY PATHS

    "return 404 when agent not allocated to client in gg nor identifier not found in des" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(identifier)
      givenNinoIsUnknownFor(MtdItId(mtditid))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in gg nor cesa" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(identifier)
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenClientHasNoActiveRelationshipWithAgent(Nino(nino))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in gg and also cesa mapping not found" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(identifier)
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenClientHasRelationshipWithAgent(Nino(nino), "foo")
      givenArnIsUnknownFor(Arn(arn))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    //FAILURE CASES


    "return 502 when GsoAdminGetCredentialsForDirectEnrolments returns 5xx" in {
      whenGetCredentialsReturns(500)
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 502 when GsoAdminGetUserDetails returns 5xx" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      whenGetUserDetailReturns(500)
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 502 when GsoAdminGetAssignedAgents returns 5xx" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      whenGetAssignedAgentsReturns(500)
      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 400 when GsoAdminGetCredentialsForDirectEnrolments returns 4xx" in {
      whenGetCredentialsReturns(400)
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      val result = await(doRequest)
      result.status shouldBe 400
    }

    "return 400 when GsoAdminGetUserDetails returns 4xx" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      whenGetUserDetailReturns(400)
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      val result = await(doRequest)
      result.status shouldBe 400
    }

    "return 400 when GsoAdminGetAssignedAgents returns 4xx" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      whenGetAssignedAgentsReturns(400)
      val result = await(doRequest)
      result.status shouldBe 400
    }
  }
}
