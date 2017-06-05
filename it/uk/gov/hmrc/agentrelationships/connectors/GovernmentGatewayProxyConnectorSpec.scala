package uk.gov.hmrc.agentrelationships.connectors

import com.kenshoo.play.metrics.Metrics
import org.scalatestplus.play.OneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.WSHttp
import uk.gov.hmrc.agentclientrelationships.connectors.GovernmentGatewayProxyConnector
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.agentrelationships.stubs.GovernmentGatewayProxyStubs
import uk.gov.hmrc.agentrelationships.support.WireMockSupport
import uk.gov.hmrc.domain.{AgentCode, Nino}
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec

class GovernmentGatewayProxyConnectorSpec extends UnitSpec with OneServerPerSuite with WireMockSupport with GovernmentGatewayProxyStubs {

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
      await(connector.getCredIdFor(Arn("foo"))) shouldBe "bar"
    }

    "fail when credentials for arn has not been found" in {
      givenAgentCredentialsAreNotFoundFor(Arn("foo"))
      an[Exception] should be thrownBy await(connector.getCredIdFor(Arn("foo")))
    }

    "exchange credential identifier for agent code" in {
      givenAgentCodeIsFoundFor("foo", "bar")
      await(connector.getAgentCodeFor("foo")) shouldBe AgentCode("bar")
    }

    "fail if agent code when credentials are invalid" in {
      whenGetUserDetailReturns(500)
      an[Exception] should be thrownBy await(connector.getAgentCodeFor("foo"))
    }

    "fail if agent code when credentials are not of type Agent" in {
      givenAgentCodeIsNotInTheResponseFor("foo")
      an[Exception] should be thrownBy await(connector.getAgentCodeFor("foo"))
    }

    "return set containing agent code if agent is allocated and assigned for a client" in {
      givenAgentIsAllocatedAndAssignedToClient("foo", "bar")
      await(connector.getAllocatedAgentCodes(MtdItId("foo"))) should contain(AgentCode("bar"))
    }

    "return set containing agent code if agent is allocated but not assigned for a client" in {
      givenAgentIsAllocatedButNotAssignedToClient("foo")
      val result = await(connector.getAllocatedAgentCodes(MtdItId("foo")))
      result should not contain AgentCode("bar")
      result should contain(AgentCode("other"))
      result should contain(AgentCode("123ABCD12345"))
    }

    "return set without expected agent code if agent is not allocated for a client" in {
      givenAgentIsNotAllocatedToClient("foo")
      await(connector.getAllocatedAgentCodes(MtdItId("foo"))) should not contain AgentCode("bar")
    }

    "record metrics GsoAdminGetCredentialsForDirectEnrolments" in {
      givenAgentCredentialsAreFoundFor(Arn("foo"), "bar")
      await(connector.getCredIdFor(Arn("foo")))
      val metricsRegistry = app.injector.instanceOf[Metrics].defaultRegistry
      metricsRegistry.getTimers.get("Timer-ConsumedAPI-GGW-GsoAdminGetCredentialsForDirectEnrolments-POST").getCount should be >= 1L
    }

    "record metrics GsoAdminGetUserDetails" in {
      givenAgentCodeIsFoundFor("foo", "bar")
      await(connector.getAgentCodeFor("foo"))
      val metricsRegistry = app.injector.instanceOf[Metrics].defaultRegistry
       metricsRegistry.getTimers.get("Timer-ConsumedAPI-GGW-GsoAdminGetUserDetails-POST").getCount should be >= 1L
    }

    "record metrics GsoAdminGetAssignedAgents" in {
      givenAgentIsAllocatedAndAssignedToClient("foo", "bar")
      await(connector.getAllocatedAgentCodes(MtdItId("foo")))
      val metricsRegistry = app.injector.instanceOf[Metrics].defaultRegistry
      metricsRegistry.getTimers.get("Timer-ConsumedAPI-GGW-GsoAdminGetAssignedAgents-POST").getCount should be >= 1L
    }

    "return set containing agent code if agent is allocated and assigned for a client with NINO" in {
      givenAgentIsAllocatedAndAssignedToClient("CE321007A", "bar")
      await(connector.getAllocatedAgentCodes(Nino("CE321007A"))) should contain(AgentCode("bar"))
    }

    "return set containing agent code if agent is allocated but not assigned for a client with NINO" in {
      givenAgentIsAllocatedButNotAssignedToClient("CE321007A")
      val result = await(connector.getAllocatedAgentCodes(Nino("CE321007A")))
      result should not contain AgentCode("bar")
      result should contain(AgentCode("other"))
      result should contain(AgentCode("123ABCD12345"))
    }
  }
}
