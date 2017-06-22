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
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.agentclientrelationships.repository.{RelationshipCopyRecord, RelationshipCopyRecordRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.agentrelationships.stubs.{DesStubs, GovernmentGatewayProxyStubs, MappingStubs}
import uk.gov.hmrc.agentrelationships.support.{MongoApp, Resource, WireMockSupport}
import uk.gov.hmrc.domain.{Nino, SaAgentReference}
import uk.gov.hmrc.play.http.HttpResponse
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class TestRelationshipCopyRecordRepository @Inject()(moduleComponent: ReactiveMongoComponent)
  extends RelationshipCopyRecordRepository(moduleComponent) {
  override def create(record: RelationshipCopyRecord)(implicit ec: ExecutionContext): Future[Unit] = {
    Future.failed(new Exception("Could not connect the mongo db."))
  }
}

class RelationshipWithoutMongoISpec extends UnitSpec
  with MongoApp
  with WireMockSupport
  with GovernmentGatewayProxyStubs
  with DesStubs
  with MappingStubs {

  override implicit lazy val app: Application = appBuilder
    .build()

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.government-gateway-proxy.port" -> wireMockPort,
        "microservice.services.des.port" -> wireMockPort,
        "microservice.services.agent-mapping.port" -> wireMockPort,
        "auditing.enabled" -> false)
      .configure(mongoConfiguration)
      .overrides(new AbstractModule {
        override def configure(): Unit = {
          bind(classOf[RelationshipCopyRecordRepository]).to(classOf[TestRelationshipCopyRecordRepository])
        }
      })

  def repo = app.injector.instanceOf[RelationshipCopyRecordRepository]

  override def beforeEach() {
    super.beforeEach()
    await(repo.ensureIndexes)
  }

  val arn = "AARN0000002"
  val mtditid = "ABCDEF123456789"
  val nino = "AB123456C"


  "GET /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:identifierValue" should {

    behave like aCheckEndpoint(true, doAgentRequest(s"/agent-client-relationships/agent/$arn/service/HMRC-MTD-IT/client/MTDITID/$mtditid"))
  }

  "GET /agent/:arn/service/IR-SA/client/ni/:identifierValue" should {

    behave like aCheckEndpoint(false, doAgentRequest(s"/agent-client-relationships/agent/$arn/service/IR-SA/client/ni/$nino"))
  }

  private def doAgentRequest(route: String) = new Resource(route, port).get()

  private def aCheckEndpoint(isMtdItId: Boolean, doRequest: => HttpResponse) = {

    val identifier: String = if (isMtdItId) mtditid else nino
    val identifierType: String = if (isMtdItId) "MTDITID" else "NINO"

    "return 200 when relationship exists only in cesa and relationship copy attempt fails because of mongo" in {
      givenAgentCredentialsAreFoundFor(Arn(arn), "foo")
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAgentIsNotAllocatedToClient(identifier)
      givenNinoIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenMtdItIdIsKnownFor(MtdItId(mtditid), Nino(nino))
      givenArnIsKnownFor(Arn(arn), SaAgentReference("foo"))
      givenClientHasRelationshipWithAgent(Nino(nino), "foo")
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenAgentCanBeAllocatedInGovernmentGateway(mtditid, "bar")

      def query = repo.find("arn" -> arn, "clientIdentifier" -> identifier, "clientIdentifierType" -> identifierType)

      await(query) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query) shouldBe empty
    }
  }
}

