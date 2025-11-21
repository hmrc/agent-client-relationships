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

package uk.gov.hmrc.agentclientrelationships.connectors

import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.stubs.DataStreamStub
import uk.gov.hmrc.agentclientrelationships.stubs.EnrolmentStoreProxyStubs
import uk.gov.hmrc.agentclientrelationships.support.RelationshipNotFound
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentclientrelationships.support.WireMockSupport
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdIt
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import scala.concurrent.ExecutionContext

class EnrolmentStoreProxyConnectorSpec
extends UnitSpec
with GuiceOneServerPerSuite
with WireMockSupport
with EnrolmentStoreProxyStubs
with DataStreamStub
with MockitoSugar {

  override implicit lazy val app: Application = appBuilder.build()

  protected def appBuilder: GuiceApplicationBuilder = new GuiceApplicationBuilder().configure(
    "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
    "microservice.services.tax-enrolments.port" -> wireMockPort,
    "microservice.services.users-groups-search.port" -> wireMockPort,
    "microservice.services.des.port" -> wireMockPort,
    "microservice.services.auth.port" -> wireMockPort,
    "microservice.services.agent-mapping.port" -> wireMockPort,
    "auditing.consumer.baseUri.host" -> wireMockHost,
    "auditing.consumer.baseUri.port" -> wireMockPort,
    "features.copy-relationship.mtd-it" -> true,
    "features.recovery-enable" -> false,
    "agent.cache.expires" -> "1 millis",
    "agent.cache.enabled" -> true
  )

  implicit val request: RequestHeader = FakeRequest()
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  val httpClient: HttpClientV2 = app.injector.instanceOf[HttpClientV2]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  val connector =
    new EnrolmentStoreProxyConnector(
      httpClient,
      app.injector.instanceOf[Metrics],
      appConfig
    )(ec)

  "EnrolmentStoreProxy" should {

    val mtdItEnrolmentKey = EnrolmentKey(Service.MtdIt, MtdItId("foo"))
    val vatEnrolmentKey = EnrolmentKey(Service.Vat, Vrn("foo"))

    "return some agent's groupId for given ARN" in {
      givenAuditConnector()
      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(Arn("foo")), "bar")
      await(connector.getPrincipalGroupIdFor(Arn("foo"))) shouldBe "bar"
    }

    "return RelationshipNotFound Exception when ARN not found" in {
      givenAuditConnector()
      givenPrincipalGroupIdNotExistsFor(agentEnrolmentKey(Arn("foo")))
      an[RelationshipNotFound] shouldBe thrownBy {
        await(connector.getPrincipalGroupIdFor(Arn("foo")))
      }
    }

    "return some agents's groupIds for given MTDITID" in {
      givenAuditConnector()
      givenDelegatedGroupIdsExistFor(
        mtdItEnrolmentKey,
        Set(
          "bar",
          "car",
          "dar"
        )
      )
      await(connector.getDelegatedGroupIdsFor(mtdItEnrolmentKey)) should contain("bar")
    }

    "return Empty when MTDITID not found" in {
      givenAuditConnector()
      givenDelegatedGroupIdsNotExistFor(mtdItEnrolmentKey)
      await(connector.getDelegatedGroupIdsFor(mtdItEnrolmentKey)) should be(empty)
    }

    "return some agents's groupIds for given NINO" in {
      givenAuditConnector()
      givenDelegatedGroupIdsExistFor(
        EnrolmentKey(Service.MtdIt, Nino("AB123456C")),
        Set(
          "bar",
          "car",
          "dar"
        )
      )
      await(connector.getDelegatedGroupIdsFor(EnrolmentKey(Service.MtdIt, Nino("AB123456C")))) should contain("bar")
    }

    "return Empty when NINO not found" in {
      givenAuditConnector()
      givenDelegatedGroupIdsNotExistFor(EnrolmentKey(Service.MtdIt, Nino("AB123456C")))
      await(connector.getDelegatedGroupIdsFor(EnrolmentKey(Service.MtdIt, Nino("AB123456C")))) should be(empty)
    }

    "return some agents's groupIds for given VRN" in {
      givenAuditConnector()
      givenDelegatedGroupIdsExistFor(
        vatEnrolmentKey,
        Set(
          "bar",
          "car",
          "dar"
        )
      )
      await(connector.getDelegatedGroupIdsFor(vatEnrolmentKey)) should contain("bar")
    }

    "return Empty when VRN not found" in {
      givenAuditConnector()
      givenDelegatedGroupIdsNotExistFor(vatEnrolmentKey)
      await(connector.getDelegatedGroupIdsFor(vatEnrolmentKey)) should be(empty)
    }

    "return some ARN for the known groupId" in {
      givenAuditConnector()
      givenEnrolmentExistsForGroupId("bar", agentEnrolmentKey(Arn("foo")))
      await(connector.getAgentReferenceNumberFor("bar")) shouldBe Some(Arn("foo"))
    }

    "return None for unknown groupId" in {
      givenAuditConnector()
      givenEnrolmentNotExistsForGroupId("bar")
      await(connector.getAgentReferenceNumberFor("bar")) shouldBe None
    }

    "return a success when updating friendly name" in {
      val testGroupId = "testGroupId"
      val testEnrolment = EnrolmentKey(MtdIt, MtdItId("ABCDEF123456789")).toString
      givenAuditConnector()
      givenUpdateEnrolmentFriendlyNameResponse(
        testGroupId,
        testEnrolment,
        NO_CONTENT
      )
      await(
        connector.updateEnrolmentFriendlyName(
          testGroupId,
          testEnrolment,
          "testName"
        )
      )
    }

    "throw an error when updating friendly name returns an unexpected status" in {
      val testGroupId = "testGroupId"
      val testEnrolment = EnrolmentKey(MtdIt, MtdItId("ABCDEF123456789")).toString
      givenAuditConnector()
      givenUpdateEnrolmentFriendlyNameResponse(
        testGroupId,
        testEnrolment,
        INTERNAL_SERVER_ERROR
      )
      intercept[UpstreamErrorResponse](
        await(
          connector.updateEnrolmentFriendlyName(
            testGroupId,
            testEnrolment,
            "testName"
          )
        )
      )
    }

    "return some utr for cbcId (known fact)" in {
      val cbcId = CbcId("XACBC4940653845")
      val expectedUtr = "1172123849"
      givenAuditConnector()
      givenCbcUkExistsInES(cbcId, expectedUtr)
      await(connector.queryKnownFacts(Service.Cbc, Seq(Identifier("cbcId", cbcId.value)))).get should contain(
        Identifier("UTR", expectedUtr)
      )
    }

    "return some utr for plrId (known fact)" in {
      val cbcId = CbcId("XACBC4940653845")
      val expectedUtr = "1172123849"
      givenAuditConnector()
      givenCbcUkExistsInES(cbcId, expectedUtr)
      await(connector.queryKnownFacts(Service.Cbc, Seq(Identifier("cbcId", cbcId.value)))).get should contain(
        Identifier("UTR", expectedUtr)
      )
    }
  }

  "TaxEnrolments" should {

    val enrolmentKey = EnrolmentKey("HMRC-MTD-IT~MTDITID~ABC1233")

    "allocate an enrolment to an agent" in {
      givenAuditConnector()
      givenEnrolmentAllocationSucceeds(
        "group1",
        "user1",
        enrolmentKey,
        "bar"
      )
      await(
        connector.allocateEnrolmentToAgent(
          "group1",
          "user1",
          enrolmentKey,
          AgentCode("bar")
        )
      )
      verifyEnrolmentAllocationAttempt(
        "group1",
        "user1",
        enrolmentKey,
        "bar"
      )
    }

    "throw an exception if allocation failed because of missing agent or enrolment" in {
      givenAuditConnector()
      givenEnrolmentAllocationFailsWith(404)(
        "group1",
        "user1",
        enrolmentKey,
        "bar"
      )
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(
          connector.allocateEnrolmentToAgent(
            "group1",
            "user1",
            enrolmentKey,
            AgentCode("bar")
          )
        )
      }
      verifyEnrolmentAllocationAttempt(
        "group1",
        "user1",
        enrolmentKey,
        "bar"
      )
    }

    "throw an exception if allocation failed because of bad request" in {
      givenAuditConnector()
      givenEnrolmentAllocationFailsWith(400)(
        "group1",
        "user1",
        enrolmentKey,
        "bar"
      )
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(
          connector.allocateEnrolmentToAgent(
            "group1",
            "user1",
            enrolmentKey,
            AgentCode("bar")
          )
        )
      }
      verifyEnrolmentAllocationAttempt(
        "group1",
        "user1",
        enrolmentKey,
        "bar"
      )
    }

    "throw an exception if allocation failed because of unauthorized" in {
      givenAuditConnector()
      givenEnrolmentAllocationFailsWith(401)(
        "group1",
        "user1",
        enrolmentKey,
        "bar"
      )
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(
          connector.allocateEnrolmentToAgent(
            "group1",
            "user1",
            enrolmentKey,
            AgentCode("bar")
          )
        )
      }
      verifyEnrolmentAllocationAttempt(
        "group1",
        "user1",
        enrolmentKey,
        "bar"
      )
    }

    "throw an exception if service not available when allocating enrolment" in {
      givenAuditConnector()
      givenEnrolmentAllocationFailsWith(503)(
        "group1",
        "user1",
        enrolmentKey,
        "bar"
      )
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(
          connector.allocateEnrolmentToAgent(
            "group1",
            "user1",
            enrolmentKey,
            AgentCode("bar")
          )
        )
      }
      verifyEnrolmentAllocationAttempt(
        "group1",
        "user1",
        enrolmentKey,
        "bar"
      )
    }

    "de-allocate an enrolment from an agent" in {
      givenAuditConnector()
      givenEnrolmentDeallocationSucceeds("group1", enrolmentKey)
      await(connector.deallocateEnrolmentFromAgent("group1", enrolmentKey))
      verifyEnrolmentDeallocationAttempt("group1", enrolmentKey)
    }

    "throw an exception if de-allocation failed because of missing agent or enrolment" in {
      givenAuditConnector()
      givenEnrolmentDeallocationFailsWith(404)("group1", enrolmentKey)
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(connector.deallocateEnrolmentFromAgent("group1", enrolmentKey))
      }
      verifyEnrolmentDeallocationAttempt("group1", enrolmentKey)
    }

    "throw an exception if de-allocation failed because of bad request" in {
      givenAuditConnector()
      givenEnrolmentDeallocationFailsWith(400)("group1", enrolmentKey)
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(connector.deallocateEnrolmentFromAgent("group1", enrolmentKey))
      }
      verifyEnrolmentDeallocationAttempt("group1", enrolmentKey)
    }

    "throw an exception if de-allocation failed because of unauthorized" in {
      givenAuditConnector()
      givenEnrolmentDeallocationFailsWith(401)("group1", enrolmentKey)
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(connector.deallocateEnrolmentFromAgent("group1", enrolmentKey))
      }
      verifyEnrolmentDeallocationAttempt("group1", enrolmentKey)
    }

    "throw an exception if service not available when de-allocating enrolment" in {
      givenAuditConnector()
      givenEnrolmentDeallocationFailsWith(503)("group1", enrolmentKey)
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(connector.deallocateEnrolmentFromAgent("group1", enrolmentKey))
      }
      verifyEnrolmentDeallocationAttempt("group1", enrolmentKey)
    }
  }

}
