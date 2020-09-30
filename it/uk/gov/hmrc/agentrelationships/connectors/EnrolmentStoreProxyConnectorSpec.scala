package uk.gov.hmrc.agentrelationships.connectors

import com.kenshoo.play.metrics.Metrics
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.agentclientrelationships.support.RelationshipNotFound
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.agentrelationships.stubs.{DataStreamStub, EnrolmentStoreProxyStubs}
import uk.gov.hmrc.agentrelationships.support.{MetricTestSupport, WireMockSupport}
import uk.gov.hmrc.domain.{AgentCode, Nino}
import uk.gov.hmrc.http
import uk.gov.hmrc.http._
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class EnrolmentStoreProxyConnectorSpec
    extends UnitSpec
    with GuiceOneServerPerSuite
    with WireMockSupport
    with EnrolmentStoreProxyStubs
    with DataStreamStub
    with MetricTestSupport
    with MockitoSugar {

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
        "features.copy-relationship.mtd-vat" -> true,
        "features.recovery-enable" -> false,
        "agent.cache.size" -> 1,
        "agent.cache.expires" -> "1 millis",
        "agent.cache.enabled" -> true,
        "agent.trackPage.cache.size" -> 1,
        "agent.trackPage.cache.expires" -> "1 millis",
        "agent.trackPage.cache.enabled" -> true
      )

  implicit val hc = HeaderCarrier()

  val httpClient = app.injector.instanceOf[http.HttpClient]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  val connector =
    new EnrolmentStoreProxyConnector(httpClient, app.injector.instanceOf[Metrics])

  "EnrolmentStoreProxy" should {

    "return some agent's groupId for given ARN" in {
      givenAuditConnector()
      givenPrincipalGroupIdExistsFor(Arn("foo"), "bar")
      await(connector.getPrincipalGroupIdFor(Arn("foo"))) shouldBe "bar"
    }

    "return RelationshipNotFound Exception when ARN not found" in {
      givenAuditConnector()
      givenPrincipalGroupIdNotExistsFor(Arn("foo"))
      an[RelationshipNotFound] shouldBe thrownBy {
        await(connector.getPrincipalGroupIdFor(Arn("foo")))
      }
    }

    "return some agents's groupIds for given MTDITID" in {
      givenAuditConnector()
      givenDelegatedGroupIdsExistFor(MtdItId("foo"), Set("bar", "car", "dar"))
      await(connector.getDelegatedGroupIdsFor(MtdItId("foo"))) should contain("bar")
    }

    "return Empty when MTDITID not found" in {
      givenAuditConnector()
      givenDelegatedGroupIdsNotExistFor(MtdItId("foo"))
      await(connector.getDelegatedGroupIdsFor(MtdItId("foo"))) should be(empty)
    }

    "return some agents's groupIds for given NINO" in {
      givenAuditConnector()
      givenDelegatedGroupIdsExistFor(Nino("AB123456C"), Set("bar", "car", "dar"))
      await(connector.getDelegatedGroupIdsFor(Nino("AB123456C"))) should contain("bar")
    }

    "return Empty when NINO not found" in {
      givenAuditConnector()
      givenDelegatedGroupIdsNotExistFor(Nino("AB123456C"))
      await(connector.getDelegatedGroupIdsFor(Nino("AB123456C"))) should be(empty)
    }

    "return some agents's groupIds for given VRN" in {
      givenAuditConnector()
      givenDelegatedGroupIdsExistFor(Vrn("foo"), Set("bar", "car", "dar"))
      await(connector.getDelegatedGroupIdsFor(Vrn("foo"))) should contain("bar")
    }

    "return some agents's groupIds for given VATRegNo" in {
      givenAuditConnector()
      givenDelegatedGroupIdsExistForKey("HMCE-VATDEC-ORG~VATRegNo~oldfoo", Set("bar", "car", "dar"))
      await(connector.getDelegatedGroupIdsForHMCEVATDECORG(Vrn("oldfoo"))) should contain("bar")
    }

    "return Empty when VRN not found" in {
      givenAuditConnector()
      givenDelegatedGroupIdsNotExistFor(Vrn("foo"))
      await(connector.getDelegatedGroupIdsFor(Vrn("foo"))) should be(empty)
    }

    "return some ARN for the known groupId" in {
      givenAuditConnector()
      givenEnrolmentExistsForGroupId("bar", Arn("foo"))
      await(connector.getAgentReferenceNumberFor("bar")) shouldBe Some(Arn("foo"))
    }

    "return None for unknown groupId" in {
      givenAuditConnector()
      givenEnrolmentNotExistsForGroupId("bar")
      await(connector.getAgentReferenceNumberFor("bar")) shouldBe None
    }
  }

  "TaxEnrolments" should {

    "allocate an enrolment to an agent" in {
      givenAuditConnector()
      givenEnrolmentAllocationSucceeds("group1", "user1", "HMRC-MTD-IT", "MTDITID", "ABC1233", "bar")
      await(connector.allocateEnrolmentToAgent("group1", "user1", MtdItId("ABC1233"), AgentCode("bar")))
      verifyEnrolmentAllocationAttempt("group1", "user1", "HMRC-MTD-IT~MTDITID~ABC1233", "bar")
    }

    "throw an exception if allocation failed because of missing agent or enrolment" in {
      givenAuditConnector()
      givenEnrolmentAllocationFailsWith(404)("group1", "user1", "HMRC-MTD-IT", "MTDITID", "ABC1233", "bar")
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(connector.allocateEnrolmentToAgent("group1", "user1", MtdItId("ABC1233"), AgentCode("bar")))
      }
      verifyEnrolmentAllocationAttempt("group1", "user1", "HMRC-MTD-IT~MTDITID~ABC1233", "bar")
    }

    "throw an exception if allocation failed because of bad request" in {
      givenAuditConnector()
      givenEnrolmentAllocationFailsWith(400)("group1", "user1", "HMRC-MTD-IT", "MTDITID", "ABC1233", "bar")
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(connector.allocateEnrolmentToAgent("group1", "user1", MtdItId("ABC1233"), AgentCode("bar")))
      }
      verifyEnrolmentAllocationAttempt("group1", "user1", "HMRC-MTD-IT~MTDITID~ABC1233", "bar")
    }

    "throw an exception if allocation failed because of unauthorized" in {
      givenAuditConnector()
      givenEnrolmentAllocationFailsWith(401)("group1", "user1", "HMRC-MTD-IT", "MTDITID", "ABC1233", "bar")
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(connector.allocateEnrolmentToAgent("group1", "user1", MtdItId("ABC1233"), AgentCode("bar")))
      }
      verifyEnrolmentAllocationAttempt("group1", "user1", "HMRC-MTD-IT~MTDITID~ABC1233", "bar")
    }

    "keep calm if conflict reported" in {
      givenAuditConnector()
      givenEnrolmentAllocationFailsWith(409)("group1", "user1", "HMRC-MTD-IT", "MTDITID", "ABC1233", "bar")
      await(connector.allocateEnrolmentToAgent("group1", "user1", MtdItId("ABC1233"), AgentCode("bar")))
      verifyEnrolmentAllocationAttempt("group1", "user1", "HMRC-MTD-IT~MTDITID~ABC1233", "bar")
    }

    "throw an exception if service not available when allocating enrolment" in {
      givenAuditConnector()
      givenEnrolmentAllocationFailsWith(503)("group1", "user1", "HMRC-MTD-IT", "MTDITID", "ABC1233", "bar")
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(connector.allocateEnrolmentToAgent("group1", "user1", MtdItId("ABC1233"), AgentCode("bar")))
      }
      verifyEnrolmentAllocationAttempt("group1", "user1", "HMRC-MTD-IT~MTDITID~ABC1233", "bar")
    }

    "de-allocate an enrolment from an agent" in {
      givenAuditConnector()
      givenEnrolmentDeallocationSucceeds("group1", "HMRC-MTD-IT", "MTDITID", "ABC1233")
      await(connector.deallocateEnrolmentFromAgent("group1", MtdItId("ABC1233")))
      verifyEnrolmentDeallocationAttempt("group1", "HMRC-MTD-IT~MTDITID~ABC1233")
    }

    "throw an exception if de-allocation failed because of missing agent or enrolment" in {
      givenAuditConnector()
      givenEnrolmentDeallocationFailsWith(404)("group1", "HMRC-MTD-IT", "MTDITID", "ABC1233")
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(connector.deallocateEnrolmentFromAgent("group1", MtdItId("ABC1233")))
      }
      verifyEnrolmentDeallocationAttempt("group1", "HMRC-MTD-IT~MTDITID~ABC1233")
    }

    "throw an exception if de-allocation failed because of bad request" in {
      givenAuditConnector()
      givenEnrolmentDeallocationFailsWith(400)("group1", "HMRC-MTD-IT", "MTDITID", "ABC1233")
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(connector.deallocateEnrolmentFromAgent("group1", MtdItId("ABC1233")))
      }
      verifyEnrolmentDeallocationAttempt("group1", "HMRC-MTD-IT~MTDITID~ABC1233")
    }

    "throw an exception if de-allocation failed because of unauthorized" in {
      givenAuditConnector()
      givenEnrolmentDeallocationFailsWith(401)("group1", "HMRC-MTD-IT", "MTDITID", "ABC1233")
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(connector.deallocateEnrolmentFromAgent("group1", MtdItId("ABC1233")))
      }
      verifyEnrolmentDeallocationAttempt("group1", "HMRC-MTD-IT~MTDITID~ABC1233")
    }

    "throw an exception if service not available when de-allocating enrolment" in {
      givenAuditConnector()
      givenEnrolmentDeallocationFailsWith(503)("group1", "HMRC-MTD-IT", "MTDITID", "ABC1233")
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(connector.deallocateEnrolmentFromAgent("group1", MtdItId("ABC1233")))
      }
      verifyEnrolmentDeallocationAttempt("group1", "HMRC-MTD-IT~MTDITID~ABC1233")
    }
  }
}
