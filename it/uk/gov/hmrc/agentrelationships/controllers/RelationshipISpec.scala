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
import uk.gov.hmrc.agentclientrelationships.repository.{MongoRelationshipCopyRecordRepository, RelationshipCopyRecord, SyncStatus}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.agentrelationships.stubs._
import uk.gov.hmrc.agentrelationships.support._
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.domain.{Nino, SaAgentReference}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, Upstream5xxResponse}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class RelationshipISpec extends UnitSpec
  with MongoApp
  with OneServerPerSuite
  with WireMockSupport
  with GovernmentGatewayProxyStubs
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
        "microservice.services.government-gateway-proxy.port" -> wireMockPort,
        "microservice.services.des.port" -> wireMockPort,
        "microservice.services.auth.port" -> wireMockPort,
        "microservice.services.agent-mapping.port" -> wireMockPort,
        "auditing.consumer.baseUri.host" -> wireMockHost,
        "auditing.consumer.baseUri.port" -> wireMockPort)
      .configure(mongoConfiguration)

  def repo = app.injector.instanceOf[MongoRelationshipCopyRecordRepository]

  override def beforeEach() {
    super.beforeEach()
    await(repo.ensureIndexes)
  }

  val arn = "AARN0000002"
  val mtditid = "ABCDEF123456789"
  val nino = "AB123456C"
  val mtdItIdType = "MTDITID"

  val relationshipCopiedSuccessfully = RelationshipCopyRecord(
    arn,
    mtditid,
    "MTDITID",
    syncToETMPStatus = Some(SyncStatus.Success),
    syncToGGStatus = Some(SyncStatus.Success)
  )

  "GET /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:nino" should {

    val requestPath: String = s"/agent-client-relationships/agent/$arn/service/HMRC-MTD-IT/client/MTDITID/$mtditid"

    def doRequest = doAgentGetRequest(requestPath)

    behave like aCheckEndpoint(true, doRequest)

    //HAPPY PATHS WHEN CHECKING CESA

    "return 200 when agent not allocated to client in gg but relationship exists in cesa" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenMtdItIdIsKnownFor(Nino(nino), MtdItId(mtditid))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(Nino(nino), "foo")
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAgentCanBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()

      def query() = repo.find("arn" -> arn, "clientIdentifier" -> mtditid, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn),
        'clientIdentifier (mtditid),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaAgentReference("foo")))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToGGStatus (Some(SyncStatus.Success))
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CreateRelationship,
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
          "arn" -> arn,
          "credId" -> "foo",
          "agentCode" -> "bar",
          "nino" -> nino,
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
      givenAgentCredentialsAreNotFoundFor(Arn(arn))
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenMtdItIdIsKnownFor(Nino(nino), MtdItId(mtditid))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(Nino(nino), "foo")
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAuditConnector()

      def query() = repo.find("arn" -> arn, "clientIdentifier" -> mtditid, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn),
        'clientIdentifier (mtditid),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaAgentReference("foo")))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToGGStatus (Some(SyncStatus.IncompleteInputParams))
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CreateRelationship,
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
          "arn" -> arn,
          "credId" -> "",
          "agentCode" -> "",
          "nino" -> nino,
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
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsNotInTheResponseFor("foo")
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenMtdItIdIsKnownFor(Nino(nino), MtdItId(mtditid))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(Nino(nino), "foo")
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAgentCanBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()

      def query() = repo.find("arn" -> arn, "clientIdentifier" -> mtditid, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn),
        'clientIdentifier (mtditid),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaAgentReference("foo")))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToGGStatus (Some(SyncStatus.IncompleteInputParams))
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CreateRelationship,
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
          "arn" -> arn,
          "credId" -> "foo",
          "agentCode" -> "",
          "nino" -> nino,
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
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenMtdItIdIsKnownFor(Nino(nino), MtdItId(mtditid))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(Nino(nino), "foo")
      givenAgentCanNotBeAllocatedInDes
      givenAgentCanBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()

      def query() = repo.find("arn" -> arn, "clientIdentifier" -> mtditid, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn),
        'clientIdentifier (mtditid),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaAgentReference("foo")))),
        'syncToETMPStatus (Some(SyncStatus.Failed)),
        'syncToGGStatus (None)
      )


    }

    "return 200 when relationship exists only in cesa and relationship copy attempt fails because of gg" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenMtdItIdIsKnownFor(Nino(nino), MtdItId(mtditid))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(Nino(nino), "foo")
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAgentCannotBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()

      def query() = repo.find("arn" -> arn, "clientIdentifier" -> mtditid, "clientIdentifierType" -> mtdItIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn),
        'clientIdentifier (mtditid),
        'clientIdentifierType (mtdItIdType),
        'references (Some(Set(SaAgentReference("foo")))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToGGStatus (Some(SyncStatus.Failed))
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CreateRelationship,
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
          "arn" -> arn,
          "credId" -> "foo",
          "agentCode" -> "bar",
          "nino" -> nino,
          "saAgentRef" -> "foo",
          "CESARelationship" -> "true"
        ),
        tags = Map(
          "transactionName" -> "check-cesa",
          "path" -> requestPath
        )
      )
    }

    "return 404 when relationship is not found in gg but relationship copy was made before" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenAuditConnector()
      await(repo.insert(relationshipCopiedSuccessfully))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when relationship was previously copied from CESA to ETMP & GG but has since been deleted from ETMP & GG " +
    "(even though the relationship upon which the copy was based still exists in CESA)" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)

      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenMtdItIdIsKnownFor(Nino(nino), MtdItId(mtditid))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(Nino(nino), "foo")

      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAgentCanBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()

      await(repo.insert(relationshipCopiedSuccessfully))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when credentials are not found but relationship copy was made before" in {
      givenAgentCredentialsAreNotFoundFor(Arn(arn))
      givenAuditConnector()
      await(repo.insert(relationshipCopiedSuccessfully))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "INVALID_ARN"
    }
  }

  "GET /agent/:arn/service/IR-SA/client/ni/:identifierValue" should {

    val requestPath = s"/agent-client-relationships/agent/$arn/service/IR-SA/client/ni/$nino"

    def doRequest = doAgentGetRequest(requestPath)

    //CESA CHECK UNHAPPY PATHS

    "return 404 when agent not allocated to client in gg nor identifier not found in des" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(nino)
      givenNinoIsUnknownFor(MtdItId(mtditid))
      givenClientHasNoActiveRelationshipWithAgentInCESA(Nino(nino))
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in gg nor cesa" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(nino)
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenClientHasNoActiveRelationshipWithAgentInCESA(Nino(nino))
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 5xx mapping is unavailable" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(nino)
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenClientHasRelationshipWithAgentInCESA(Nino(nino), "foo")
      givenServiceReturnsServiceUnavailable()
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 502
      (result.json \ "code").as[String] shouldBe "Upstream5xxResponse"
    }

    "return 404 when agent not allocated to client in gg and also cesa mapping not found" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(nino)
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenClientHasRelationshipWithAgentInCESA(Nino(nino), "foo")
      givenArnIsUnknownFor(Arn(arn))
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 200 when agent credentials unknown but relationship exists in cesa" in {
      givenAgentCredentialsAreNotFoundFor(Arn(arn))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(Nino(nino), "foo")
      givenAuditConnector()

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
          "transactionName" -> "check-cesa",
          "path" -> requestPath
        )
      )
    }

    "return 200 when credentials are not found but relationship exists in cesa and no copy attempt is made" in {
      givenAgentCredentialsAreNotFoundFor(Arn(arn))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(Nino(nino), "foo")
      givenMtdItIdIsUnKnownFor(Nino(nino))
      givenAuditConnector()

      def query() = repo.find("arn" -> arn, "clientIdentifier" -> nino, "clientIdentifierType" -> "NINO")

      await(query()) shouldBe empty
      val result = await(doRequest)
      result.status shouldBe 200
      await(query()) shouldBe empty

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
          "transactionName" -> "check-cesa",
          "path" -> requestPath
        )
      )
    }
  }
  "DELETE /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:identifierValue" should {

    val requestPath: String = s"/agent-client-relationships/agent/$arn/service/HMRC-MTD-IT/client/MTDITID/$mtditid"

    "return 204 when the relationship exists and the Arn matches that of current Agent user" in {
      givenUserIsSubscribedAgent(Arn(arn))
      writeAuditSucceeds()
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtditid, "bar")
      givenAgentCanBeDeallocatedInDes(mtditid, arn)
      givenAgentCanBeDeallocatedInGovernmentGateway(mtditid, "bar")

      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 204
    }

    "return 204 when the relationship exists and the MtdItId matches that of current Client user" in {
      givenUserIsSubscribedClient(MtdItId(mtditid))
      writeAuditSucceeds()
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtditid, "bar")
      givenAgentCanBeDeallocatedInDes(mtditid, arn)
      givenAgentCanBeDeallocatedInGovernmentGateway(mtditid, "bar")

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
      givenUserHasNoAgentEnrolments(Arn(arn))

      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 403
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

    val identifier: String = if (isMtdItId) mtditid else nino

    //HAPPY PATH :-)

    "return 200 when relationship exists in gg" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      givenAuditConnector()

      def query() = repo.find("arn" -> arn, "clientIdentifier" -> nino, "clientIdentifierType" -> "NINO")

      await(query()) shouldBe empty
      val result = await(doRequest)
      result.status shouldBe 200
      await(query()) shouldBe empty
    }

    //UNHAPPY PATHS

    "return 404 when credentials are not found in gg" in {
      givenAgentCredentialsAreNotFoundFor(Arn(arn))
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      givenNinoIsUnknownFor(MtdItId(mtditid))
      givenClientIsUnknownInCESAFor(Nino(nino))
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "INVALID_ARN"
    }

    "return 404 when agent code is not found in gg" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsNotInTheResponseFor("foo")
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      givenNinoIsUnknownFor(MtdItId(mtditid))
      givenClientIsUnknownInCESAFor(Nino(nino))
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "UNKNOWN_AGENT_CODE"
    }

    //CESA CHECK UNHAPPY PATHS

    "return 404 when agent not allocated to client in gg nor identifier not found in des" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(identifier)
      givenNinoIsUnknownFor(MtdItId(mtditid))
      givenClientHasNoActiveRelationshipWithAgentInCESA(Nino(nino))
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in gg nor cesa" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(identifier)
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenClientHasNoActiveRelationshipWithAgentInCESA(Nino(nino))
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in gg and also cesa mapping not found" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(identifier)
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenClientHasRelationshipWithAgentInCESA(Nino(nino), "foo")
      givenArnIsUnknownFor(Arn(arn))
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    //FAILURE CASES


    "return 502 when GsoAdminGetCredentialsForDirectEnrolments returns 5xx" in {
      whenGetCredentialsReturns(500)
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 502 when GsoAdminGetUserDetails returns 5xx" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      whenGetUserDetailReturns(500)
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 502 when GsoAdminGetAssignedAgents returns 5xx" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAuditConnector()
      whenGetAssignedAgentsReturns(500)
      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 400 when GsoAdminGetCredentialsForDirectEnrolments returns 4xx" in {
      whenGetCredentialsReturns(400)
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 400
    }

    "return 400 when GsoAdminGetUserDetails returns 4xx" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      whenGetUserDetailReturns(400)
      givenAgentIsAllocatedAndAssignedToClient(identifier, "bar")
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 400
    }

    "return 400 when GsoAdminGetAssignedAgents returns 4xx" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      whenGetAssignedAgentsReturns(400)
      givenAuditConnector()
      val result = await(doRequest)
      result.status shouldBe 400
    }
  }

  "DELETE /test-only/db/agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:identifierValue" should {

    val requestPath: String = s"/test-only/db/agent/$arn/service/HMRC-MTD-IT/client/MTDITID/$mtditid"

    "return 404 for any call" in {
      givenAuditConnector()
      await(repo.create(RelationshipCopyRecord(arn, mtditid, mtdItIdType))) shouldBe 1
      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 404
    }
  }

  "PUT /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:identifierValue" should {

    val requestPath: String = s"/agent-client-relationships/agent/$arn/service/HMRC-MTD-IT/client/MTDITID/$mtditid"

    "return 201 when the relationship exists and the Arn matches that of current Agent user" in {
      givenUserIsSubscribedAgent(Arn(arn))
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAgentCanBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the MtdItId matches that of current Client user" in {
      givenUserIsSubscribedClient(MtdItId(mtditid))
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAgentCanBeAllocatedInGovernmentGateway(mtditid, "bar")
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
      givenUserHasNoAgentEnrolments(Arn(arn))

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
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
}