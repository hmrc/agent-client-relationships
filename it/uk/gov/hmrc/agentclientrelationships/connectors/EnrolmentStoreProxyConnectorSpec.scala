package uk.gov.hmrc.agentclientrelationships.connectors

import com.kenshoo.play.metrics.Metrics
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.support.RelationshipNotFound
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, CbcId, Identifier, MtdItId, Service, Vrn}
import uk.gov.hmrc.agentclientrelationships.stubs.{DataStreamStub, EnrolmentStoreProxyStubs}
import uk.gov.hmrc.agentclientrelationships.support.{MetricTestSupport, WireMockSupport}
import uk.gov.hmrc.domain.{AgentCode, Nino}
import uk.gov.hmrc.http
import uk.gov.hmrc.http._
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

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

  implicit val hc: HeaderCarrier = HeaderCarrier()

  val httpClient: HttpClient = app.injector.instanceOf[http.HttpClient]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  val connector =
    new EnrolmentStoreProxyConnector(httpClient, app.injector.instanceOf[Metrics])

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
      givenDelegatedGroupIdsExistFor(mtdItEnrolmentKey, Set("bar", "car", "dar"))
      await(connector.getDelegatedGroupIdsFor(mtdItEnrolmentKey)) should contain("bar")
    }

    "return Empty when MTDITID not found" in {
      givenAuditConnector()
      givenDelegatedGroupIdsNotExistFor(mtdItEnrolmentKey)
      await(connector.getDelegatedGroupIdsFor(mtdItEnrolmentKey)) should be(empty)
    }

    "return some agents's groupIds for given NINO" in {
      givenAuditConnector()
      givenDelegatedGroupIdsExistFor(EnrolmentKey(Service.MtdIt, Nino("AB123456C")), Set("bar", "car", "dar"))
      await(connector.getDelegatedGroupIdsFor(EnrolmentKey(Service.MtdIt, Nino("AB123456C")))) should contain("bar")
    }

    "return Empty when NINO not found" in {
      givenAuditConnector()
      givenDelegatedGroupIdsNotExistFor(EnrolmentKey(Service.MtdIt, Nino("AB123456C")))
      await(connector.getDelegatedGroupIdsFor(EnrolmentKey(Service.MtdIt, Nino("AB123456C")))) should be(empty)
    }

    "return some agents's groupIds for given VRN" in {
      givenAuditConnector()
      givenDelegatedGroupIdsExistFor(vatEnrolmentKey, Set("bar", "car", "dar"))
      await(connector.getDelegatedGroupIdsFor(vatEnrolmentKey)) should contain("bar")
    }

    "return some agents's groupIds for given VATRegNo" in {
      givenAuditConnector()
      givenDelegatedGroupIdsExistFor(EnrolmentKey("HMCE-VATDEC-ORG~VATRegNo~oldfoo"), Set("bar", "car", "dar"))
      await(connector.getDelegatedGroupIdsForHMCEVATDECORG(Vrn("oldfoo"))) should contain("bar")
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

    "return some utr for cbcId (known fact)" in {
      val cbcId = CbcId("XACBC4940653845")
      val expectedUtr = "1172123849"
      givenKnownFactsForCbcId(cbcId.value, expectedUtr)
      await(connector.findUtrForCbcId(cbcId)) shouldBe Some(
        Identifier("UTR", expectedUtr)
      )
    }
  }

  "TaxEnrolments" should {

    val enrolmentKey = EnrolmentKey("HMRC-MTD-IT~MTDITID~ABC1233")

    "allocate an enrolment to an agent" in {
      givenAuditConnector()
      givenEnrolmentAllocationSucceeds("group1", "user1", enrolmentKey, "bar")
      await(connector.allocateEnrolmentToAgent("group1", "user1", enrolmentKey, AgentCode("bar")))
      verifyEnrolmentAllocationAttempt("group1", "user1", enrolmentKey, "bar")
    }

    "throw an exception if allocation failed because of missing agent or enrolment" in {
      givenAuditConnector()
      givenEnrolmentAllocationFailsWith(404)("group1", "user1", enrolmentKey, "bar")
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(connector.allocateEnrolmentToAgent("group1", "user1", enrolmentKey, AgentCode("bar")))
      }
      verifyEnrolmentAllocationAttempt("group1", "user1", enrolmentKey, "bar")
    }

    "throw an exception if allocation failed because of bad request" in {
      givenAuditConnector()
      givenEnrolmentAllocationFailsWith(400)("group1", "user1", enrolmentKey, "bar")
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(connector.allocateEnrolmentToAgent("group1", "user1", enrolmentKey, AgentCode("bar")))
      }
      verifyEnrolmentAllocationAttempt("group1", "user1", enrolmentKey, "bar")
    }

    "throw an exception if allocation failed because of unauthorized" in {
      givenAuditConnector()
      givenEnrolmentAllocationFailsWith(401)("group1", "user1", enrolmentKey, "bar")
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(connector.allocateEnrolmentToAgent("group1", "user1", enrolmentKey, AgentCode("bar")))
      }
      verifyEnrolmentAllocationAttempt("group1", "user1", enrolmentKey, "bar")
    }

    "keep calm if conflict reported" in {
      givenAuditConnector()
      givenEnrolmentAllocationFailsWith(409)("group1", "user1", enrolmentKey, "bar")
      await(connector.allocateEnrolmentToAgent("group1", "user1", enrolmentKey, AgentCode("bar")))
      verifyEnrolmentAllocationAttempt("group1", "user1", enrolmentKey, "bar")
    }

    "throw an exception if service not available when allocating enrolment" in {
      givenAuditConnector()
      givenEnrolmentAllocationFailsWith(503)("group1", "user1", enrolmentKey, "bar")
      an[UpstreamErrorResponse] shouldBe thrownBy {
        await(connector.allocateEnrolmentToAgent("group1", "user1", enrolmentKey, AgentCode("bar")))
      }
      verifyEnrolmentAllocationAttempt("group1", "user1", enrolmentKey, "bar")
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
