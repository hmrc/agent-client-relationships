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

import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.MongoRelationshipCopyRecordRepository
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord
import uk.gov.hmrc.agentclientrelationships.stubs._
import uk.gov.hmrc.agentclientrelationships.support.Http
import uk.gov.hmrc.agentclientrelationships.support.MongoApp
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentclientrelationships.support.WireMockSupport
import uk.gov.hmrc.agentmtdidentifiers.model.MtdItId
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest

class RelationshipsControllerTestOnlyISpec
    extends UnitSpec
    with MongoApp
    with GuiceOneServerPerSuite
    with WireMockSupport
    with DesStubs
    with MappingStubs
    with DataStreamStub
    with IntegrationPatience {

  override implicit lazy val app: Application = appBuilder.build()

  protected def appBuilder: GuiceApplicationBuilder = new GuiceApplicationBuilder()
    .configure(
      "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
      "microservice.services.tax-enrolments.port"        -> wireMockPort,
      "microservice.services.users-groups-search.port"   -> wireMockPort,
      "microservice.services.des.port"                   -> wireMockPort,
      "microservice.services.if.port"                    -> wireMockPort,
      "microservice.services.auth.port"                  -> wireMockPort,
      "microservice.services.agent-mapping.port"         -> wireMockPort,
      "auditing.consumer.baseUri.host"                   -> wireMockHost,
      "auditing.consumer.baseUri.port"                   -> wireMockPort,
      "application.router"                               -> "testOnlyDoNotUseInAppConf.Routes",
      "features.recovery-enable"                         -> false
    )
    .configure(mongoConfiguration)

  implicit lazy val ws: WSClient = app.injector.instanceOf[WSClient]
  implicit val request: RequestHeader = FakeRequest()

  def repo = app.injector.instanceOf[MongoRelationshipCopyRecordRepository]

  override def beforeEach() = {
    super.beforeEach()
    await(repo.ensureIndexes())
    ()
  }

  private val arn = "AARN0000002"
  private val mtditid = "ABCDEF123456789"

  private def doAgentDeleteRequest(route: String) = Http.delete(s"http://localhost:$port$route")

  "DELETE /test-only/db/agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:identifierValue" should {

    val requestPath: String = s"/test-only/db/agent/$arn/service/HMRC-MTD-IT/client/MTDITID/$mtditid"

    "return 204 for a valid arn and mtdItId" in {
      givenAuditConnector()
      await(repo.create(RelationshipCopyRecord(arn, Some(EnrolmentKey(Service.MtdIt, MtdItId(mtditid)))))) shouldBe 1
      val result = doAgentDeleteRequest(requestPath)
      result.status shouldBe 204
    }

    "return 404 for an invalid mtdItId" in {
      givenAuditConnector()
      await(
        repo.create(RelationshipCopyRecord(arn, Some(EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF123456780")))))
      ) shouldBe 1
      val result = doAgentDeleteRequest(requestPath)
      result.status shouldBe 404
    }

    "return 404 for an invalid arn and mtdItId" in {
      givenAuditConnector()
      val result = doAgentDeleteRequest(requestPath)
      result.status shouldBe 404
    }

  }

}
