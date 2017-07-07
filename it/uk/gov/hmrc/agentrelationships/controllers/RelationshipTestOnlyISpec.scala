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
import uk.gov.hmrc.agentrelationships.support.{Http, MongoApp, Resource, WireMockSupport}
import uk.gov.hmrc.domain.{Nino, SaAgentReference}
import uk.gov.hmrc.play.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class RelationshipTestOnlyISpec extends UnitSpec
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
        "auditing.consumer.baseUri.port" -> wireMockPort,
        "application.router" -> "testOnlyDoNotUseInAppConf.Routes"
      )
      .configure(mongoConfiguration)

  def repo = app.injector.instanceOf[RelationshipCopyRecordRepository]

  override def beforeEach() {
    super.beforeEach()
    await(repo.ensureIndexes)
  }

  val arn = "AARN0000002"
  val mtditid = "ABCDEF123456789"
  val nino = "AB123456C"
  val mtdItIdType = "MTDITID"

  private def doAgentDeleteRequest(route: String) = Http.delete(s"http://localhost:$port$route")(HeaderCarrier())
  private def doAgentPostRequest(route: String) = Http.post(s"http://localhost:$port$route","",Seq())(HeaderCarrier())

  "DELETE /test-only/db/agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:identifierValue" should {

    val requestPath: String = s"/test-only/db/agent/$arn/service/HMRC-MTD-IT/client/MTDITID/$mtditid"

    "return 204 for a valid arn and mtdItId" in {
      givenAuditConnector()
      await(repo.create(RelationshipCopyRecord(arn,mtditid,mtdItIdType))) shouldBe 1
      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 204
    }

    "return 404 for an invalid mtdItId" in {
      givenAuditConnector()
      await(repo.create(RelationshipCopyRecord(arn,"ABCDEF123456780",mtdItIdType))) shouldBe 1
      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 404
    }

    "return 404 for an invalid arn and mtdItId" in {
      givenAuditConnector()
      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 404
    }

  }

  "POST /test-only/agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:identifierValue" should {

    val requestPath: String = s"/test-only/agent/$arn/service/HMRC-MTD-IT/client/MTDITID/$mtditid"

    "return 201 for a valid arn and mtdItId" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAgentCanBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()
      val result = await(doAgentPostRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 for an existing db record" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAgentCanBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()
      await(repo.create(RelationshipCopyRecord(arn,mtditid,mtdItIdType))) shouldBe 1
      val result = await(doAgentPostRequest(requestPath))
      result.status shouldBe 201
    }

    "return 404 for an existing relationship" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(mtditid,"bar")
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAgentCanBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()
      val result = await(doAgentPostRequest(requestPath))
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_ALREADY_EXISTS"
    }

    "return 404 for a invalid arn and valid mtdItId" in {
      givenAgentCredentialsAreNotFoundFor(Arn(arn))
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAgentCanBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()
      val result = await(doAgentPostRequest(requestPath))
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "INVALID_ARN"
    }

    "return 404 for when allocation in des fails" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenAgentCanNotBeAllocatedInDes
      givenAgentCanBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()
      val result = await(doAgentPostRequest(requestPath))
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_CREATE_FAILED_DES"
    }

    "return 404 for when allocation in gg fails" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(mtditid)
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAgentCannotBeAllocatedInGovernmentGateway(mtditid, "bar")
      givenAuditConnector()
      val result = await(doAgentPostRequest(requestPath))
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_CREATE_FAILED_GG"
    }

  }
}
