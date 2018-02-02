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

import javax.inject.Inject

import com.google.inject.AbstractModule
import org.scalatestplus.play.OneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.repository.{MongoRelationshipCopyRecordRepository, RelationshipCopyRecord, RelationshipCopyRecordRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.agentrelationships.stubs.{DataStreamStub, DesStubs, GovernmentGatewayProxyStubs, MappingStubs}
import uk.gov.hmrc.agentrelationships.support.{MongoApp, Resource, WireMockSupport}
import uk.gov.hmrc.domain.{Nino, SaAgentReference}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class TestRelationshipCopyRecordRepository @Inject()(moduleComponent: ReactiveMongoComponent)
  extends MongoRelationshipCopyRecordRepository(moduleComponent) {
  override def create(record: RelationshipCopyRecord)(implicit ec: ExecutionContext): Future[Int] = {
    Future.failed(new Exception("Could not connect the mongo db."))
  }
}

class RelationshipWithoutMongoISpec extends UnitSpec
  with MongoApp
  with OneServerPerSuite
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
      .overrides(new AbstractModule {
        override def configure(): Unit = {
          bind(classOf[RelationshipCopyRecordRepository]).to(classOf[TestRelationshipCopyRecordRepository])
        }
      })

  def repo = app.injector.instanceOf[MongoRelationshipCopyRecordRepository]

  override def beforeEach() {
    super.beforeEach()
    await(repo.ensureIndexes)
  }

  val arn = "AARN0000002"
  val mtditid = "ABCDEF123456789"
  val nino = "AB123456C"


  "GET /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:identifierValue" should {

    val requestPath = s"/agent-client-relationships/agent/$arn/service/HMRC-MTD-IT/client/MTDITID/$mtditid"

    val identifier: String = mtditid
    val identifierType: String = "MTDITID"

    "return 200 when relationship exists only in cesa and relationship copy attempt fails because of mongo" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(identifier)
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenMtdItIdIsKnownFor(Nino(nino), MtdItId(mtditid))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(Nino(nino), "foo")
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAgentCanBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()


      def query = repo.find("arn" -> arn, "clientIdentifier" -> identifier, "clientIdentifierType" -> identifierType)

      await(query) shouldBe empty

      val result = await(doAgentRequest(requestPath))
      result.status shouldBe 200

      await(query) shouldBe empty

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn" -> arn,
          "credId" -> "foo",
          "agentCode" -> "bar",
          "nino" -> nino,
          "saAgentRef" -> "foo",
          "service" -> "mtd-it",
          "clientId" -> mtditid,
          "clientIdType" -> "ni",
          "CESARelationship" -> "true",
          "etmpRelationshipCreated" -> "false",
          "enrolmentDelegated" -> "false",
          "AgentDBRecord" -> "false",
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
  }

  "GET /agent/:arn/service/IR-SA/client/ni/:identifierValue" should {

    val requestPath = s"/agent-client-relationships/agent/$arn/service/IR-SA/client/ni/$nino"

    val identifier: String = nino
    val identifierType: String = "NINO"

    "return 200 when relationship exists only in cesa and relationship copy is never attempted" in {
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(Nino(nino), "foo")
      givenAuditConnector()

      def query = repo.find("arn" -> arn, "clientIdentifier" -> identifier, "clientIdentifierType" -> identifierType)

      await(query) shouldBe empty

      val result = await(doAgentRequest(requestPath))
      result.status shouldBe 200

      await(query) shouldBe empty

      verifyAuditRequestNotSent(
        event = AgentClientRelationshipEvent.CreateRelationship
      )

      verifyAuditRequestSent(1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn" -> arn,
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

  private def doAgentRequest(route: String) = new Resource(route, port).get()

}

