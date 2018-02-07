package uk.gov.hmrc.agentrelationships.connectors

import com.kenshoo.play.metrics.Metrics
import org.scalatestplus.play.OneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.WSHttp
import uk.gov.hmrc.agentclientrelationships.connectors.GovernmentGatewayProxyConnector
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.agentrelationships.stubs.{DataStreamStub, GovernmentGatewayProxyStubs}
import uk.gov.hmrc.agentrelationships.support.{MetricTestSupport, WireMockSupport}
import uk.gov.hmrc.domain.{AgentCode, Nino}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec

class GovernmentGatewayProxyConnectorSpec extends UnitSpec with OneServerPerSuite with WireMockSupport with GovernmentGatewayProxyStubs with DataStreamStub with MetricTestSupport {

  override implicit lazy val app: Application = appBuilder
    .build()

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.government-gateway-proxy.port" -> wireMockPort,
        "auditing.consumer.baseUri.host" -> wireMockHost,
        "auditing.consumer.baseUri.port" -> wireMockPort
      )

  implicit val hc = HeaderCarrier()

  val connector = new GovernmentGatewayProxyConnector(wireMockBaseUrl, WSHttp, app.injector.instanceOf[Metrics])

  "GovernmentGatewayProxy" should {

    "return some agent credentials when credentials has been found" in {
      givenAgentCredentialsAreFoundFor(Arn("foo"), "bar")
      givenAuditConnector()
      await(connector.getCredIdFor(Arn("foo"))) shouldBe "bar"
    }

    "fail when credentials for arn has not been found" in {
      givenAgentCredentialsAreNotFoundFor(Arn("foo"))
      givenAuditConnector()
      an[Exception] should be thrownBy await(connector.getCredIdFor(Arn("foo")))
    }

    "exchange credential identifier for agent code" in {
      givenAgentCodeIsFoundFor("foo", "bar")
      givenAuditConnector()
      await(connector.getAgentCodeFor("foo")) shouldBe AgentCode("bar")
    }

    "fail if agent code when credentials are invalid" in {
      whenGetUserDetailReturns(500)
      an[Exception] should be thrownBy await(connector.getAgentCodeFor("foo"))
    }

    "fail if agent code when credentials are not of type Agent" in {
      givenAgentCodeIsNotInTheResponseFor("foo")
      givenAuditConnector()
      an[Exception] should be thrownBy await(connector.getAgentCodeFor("foo"))
    }

    "return set containing agent code if agent is allocated and assigned for a client" in {
      givenAgentIsAllocatedAndAssignedToClient("foo", "bar")
      givenAuditConnector()
      await(connector.getAllocatedAgentCodes(MtdItId("foo"))) should contain(AgentCode("bar"))
    }

    "return set containing agent code if agent is allocated but not assigned for a client" in {
      givenAgentIsAllocatedButNotAssignedToClient("foo")
      givenAuditConnector()
      val result = await(connector.getAllocatedAgentCodes(MtdItId("foo")))
      result should not contain AgentCode("bar")
      result should contain(AgentCode("other"))
      result should contain(AgentCode("123ABCD12345"))
    }

    "return set without expected agent code if agent is not allocated for a client" in {
      givenAgentIsNotAllocatedToClient("foo")
      givenAuditConnector()
      await(connector.getAllocatedAgentCodes(MtdItId("foo"))) should not contain AgentCode("bar")
    }

    "record metrics GsoAdminGetCredentialsForDirectEnrolments" in {
      givenAgentCredentialsAreFoundFor(Arn("foo"), "bar")
      givenCleanMetricRegistry()
      givenAuditConnector()
      await(connector.getCredIdFor(Arn("foo")))
      timerShouldExistsAndBeenUpdated("ConsumedAPI-GGW-GsoAdminGetCredentialsForDirectEnrolments-POST")
    }

    "record metrics GsoAdminGetUserDetails" in {
      givenAgentCodeIsFoundFor("foo", "bar")
      givenCleanMetricRegistry()
      givenAuditConnector()
      await(connector.getAgentCodeFor("foo"))
      timerShouldExistsAndBeenUpdated("ConsumedAPI-GGW-GsoAdminGetUserDetails-POST")
    }

    "record metrics GsoAdminGetAssignedAgents" in {
      givenAgentIsAllocatedAndAssignedToClient("foo", "bar")
      givenCleanMetricRegistry()
      givenAuditConnector()
      await(connector.getAllocatedAgentCodes(MtdItId("foo")))
      timerShouldExistsAndBeenUpdated("ConsumedAPI-GGW-GsoAdminGetAssignedAgents-POST")
    }

    "return set containing agent code if agent is allocated and assigned for a client with NINO" in {
      givenAgentIsAllocatedAndAssignedToClient("CE321007A", "bar")
      givenAuditConnector()
      await(connector.getAllocatedAgentCodes(Nino("CE321007A"))) should contain(AgentCode("bar"))
    }

    "return set containing agent code if agent is allocated but not assigned for a client with NINO" in {
      givenAgentIsAllocatedButNotAssignedToClient("CE321007A")
      givenAuditConnector()
      val result = await(connector.getAllocatedAgentCodes(Nino("CE321007A")))
      result should not contain AgentCode("bar")
      result should contain(AgentCode("other"))
      result should contain(AgentCode("123ABCD12345"))
    }

    "allocate agent for valid identifiers" in {
      givenAgentCanBeAllocatedInGovernmentGateway("foo", "bar")
      givenAuditConnector()
      val result = await(connector.allocateAgent(AgentCode("bar"), MtdItId("foo")))
      result shouldBe true
    }

    "fail if cannot allocate agent" in {
      givenAgentCannotBeAllocatedInGovernmentGateway("foo", "bar")
      givenAuditConnector()
      an[Exception] should be thrownBy await(connector.allocateAgent(AgentCode("bar"), MtdItId("foo")))
    }

    "deallocate agent for valid identifiers" in {
      givenAgentCanBeDeallocatedInGovernmentGateway("foo", "bar")
      givenAuditConnector()
      await(connector.deallocateAgent(AgentCode("bar"), MtdItId("foo")))
    }

    "fail if cannot deallocate agent" in {
      givenAgentCannotBeDeallocatedInGovernmentGateway("foo", "bar")
      givenAuditConnector()
      an[Exception] should be thrownBy await(connector.deallocateAgent(AgentCode("bar"), MtdItId("foo")))
    }

    "return set containing agent vrns if agent is allocated and assigned for a client in HMCE-VATDEC-ORG" in {
      givenAgentIsAllocatedAndAssignedToClient("101747641", "101747645")
      givenAuditConnector()
      await(connector.getAllocatedAgentVrnsForHmceVatDec(Vrn("101747641"))) should contain(Vrn("101747645"))
    }

    "return set containing agent vrns if agent is allocated but not assigned for a client in HMCE-VATDEC-ORG" in {
      givenAgentIsAllocatedButNotAssignedToClient("101747641")
      givenAuditConnector()
      val result = await(connector.getAllocatedAgentVrnsForHmceVatDec(Vrn("101747641")))
      result should not contain Vrn("101747645")
      result should contain(Vrn("other"))
      result should contain(Vrn("123ABCD12345"))
    }
  }
}
