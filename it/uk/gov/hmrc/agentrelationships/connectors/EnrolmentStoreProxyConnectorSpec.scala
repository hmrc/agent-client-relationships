package uk.gov.hmrc.agentrelationships.connectors

import com.kenshoo.play.metrics.Metrics
import org.scalatestplus.play.OneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.WSHttp
import uk.gov.hmrc.agentclientrelationships.connectors.{Enrolment, EnrolmentStoreDataNotFound, EnrolmentStoreProxyConnector}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.agentrelationships.stubs.{DataStreamStub, EnrolmentStoreProxyStubs}
import uk.gov.hmrc.agentrelationships.support.{MetricTestSupport, WireMockSupport}
import uk.gov.hmrc.domain.{AgentCode, Nino}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class EnrolmentStoreProxyConnectorSpec extends UnitSpec with OneServerPerSuite with WireMockSupport
  with EnrolmentStoreProxyStubs with DataStreamStub with MetricTestSupport {

  override implicit lazy val app: Application = appBuilder
    .build()

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
        "microservice.services.tax-enrolments.port" -> wireMockPort,
        "auditing.consumer.baseUri.host" -> wireMockHost,
        "auditing.consumer.baseUri.port" -> wireMockPort
      )

  implicit val hc = HeaderCarrier()

  val connector = new EnrolmentStoreProxyConnector(wireMockBaseUrl, wireMockBaseUrl, WSHttp, app.injector.instanceOf[Metrics])

  "EnrolmentStoreProxy" should {

    "return some agent's groupId for given ARN" in {
      givenAuditConnector()
      givenPrincipalGroupIdExistsFor(Arn("foo"),"bar")
      await(connector.getPrincipalGroupIdFor(Arn("foo"))) shouldBe "bar"
    }

    "return EnrolmentStoreDataNotFound Exception when ARN not found" in {
      givenAuditConnector()
      givenPrincipalGroupIdNotExistsFor(Arn("foo"))
      an[EnrolmentStoreDataNotFound] shouldBe thrownBy {
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

    "return Empty when VRN not found" in {
      givenAuditConnector()
      givenDelegatedGroupIdsNotExistFor(Vrn("foo"))
      await(connector.getDelegatedGroupIdsFor(Vrn("foo"))) should be(empty)
    }

    "return some clients userId for given MTDITID" in {
      givenAuditConnector()
      givenPrincipalUserIdsExistFor(MtdItId("foo"), "bar")
      await(connector.getPrincipalUserIdFor(MtdItId("foo"))) shouldBe "bar"
    }

    "return EnrolmentStoreDataNotFound Exception when MTDITID not found" in {
      givenAuditConnector()
      givenPrincipalUserIdsNotExistFor(MtdItId("foo"))
      an[EnrolmentStoreDataNotFound] shouldBe thrownBy {
        await(connector.getPrincipalUserIdFor(MtdItId("foo")))
      }
    }

    "return some clients userId for given NINO" in {
      givenAuditConnector()
      givenPrincipalUserIdsExistFor(Nino("AB123456C"), "bar")
      await(connector.getPrincipalUserIdFor(Nino("AB123456C"))) shouldBe "bar"
    }

    "return EnrolmentStoreDataNotFound Exception when NINO not found" in {
      givenAuditConnector()
      givenPrincipalUserIdsNotExistFor(Nino("AB123456C"))
      an[EnrolmentStoreDataNotFound] shouldBe thrownBy {
        await(connector.getPrincipalUserIdFor(Nino("AB123456C")))
      }
    }

    "return some clients userId for given VRN" in {
      givenAuditConnector()
      givenPrincipalUserIdsExistFor(Vrn("foo"), "bar")
      await(connector.getPrincipalUserIdFor(Vrn("foo"))) shouldBe "bar"
    }

    "return EnrolmentStoreDataNotFound Exception when VRN not found" in {
      givenAuditConnector()
      givenPrincipalUserIdsNotExistFor(Vrn("foo"))
      an[EnrolmentStoreDataNotFound] shouldBe thrownBy {
        await(connector.getPrincipalUserIdFor(Vrn("foo")))
      }
    }
  }

  "TaxEnrolments" should {

    "allocate an enrolment to an agent" in {
      givenAuditConnector()
      givenEnrolmentAllocationSucceeds("group1", "user1", "FOO", "FOO-ID", "ABC1233", "bar")
      await(connector.allocateEnrolmentToAgent("group1", "user1", Enrolment("FOO", "FOO-ID", "ABC1233"), AgentCode("bar")))
    }

    "throw an exception if allocation failed" in {
      givenAuditConnector()
      givenEnrolmentAllocationFailsWith(404)("group1", "user1", "FOO", "FOO-ID", "ABC1233", "bar")
      an[Exception] shouldBe thrownBy {
        await(connector.allocateEnrolmentToAgent("group1", "user1", Enrolment("FOO", "FOO-ID", "ABC1233"), AgentCode("bar")))
      }
    }

    "de-allocate an enrolment from an agent" in {
      givenAuditConnector()
      givenEnrolmentDeallocationSucceeds("group1", "FOO", "FOO-ID", "ABC1233", "bar")
      await(connector.deallocateEnrolmentFromAgent("group1", Enrolment("FOO", "FOO-ID", "ABC1233"), AgentCode("bar")))
    }

    "throw an exception if de-allocation failed" in {
      givenAuditConnector()
      givenEnrolmentDeallocationFailsWith(404)("group1", "FOO", "FOO-ID", "ABC1233", "bar")
      an[Exception] shouldBe thrownBy {
        await(connector.deallocateEnrolmentFromAgent("group1", Enrolment("FOO", "FOO-ID", "ABC1233"), AgentCode("bar")))
      }
    }
  }
}