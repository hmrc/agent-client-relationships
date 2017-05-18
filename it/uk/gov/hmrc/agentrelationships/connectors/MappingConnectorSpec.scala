package uk.gov.hmrc.agentrelationships.connectors

import com.kenshoo.play.metrics.Metrics
import org.scalatestplus.play.OneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.WSHttp
import uk.gov.hmrc.agentclientrelationships.connectors.MappingConnector
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentrelationships.stubs.MappingStubs
import uk.gov.hmrc.agentrelationships.support.WireMockSupport
import uk.gov.hmrc.domain.SaAgentReference
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec

class MappingConnectorSpec extends UnitSpec with OneAppPerSuite with WireMockSupport with MappingStubs {

  override implicit lazy val app: Application = appBuilder
    .build()

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.agent-mapping.port" -> wireMockPort,
        "auditing.consumer.baseUri.host" -> wireMockHost,
        "auditing.consumer.baseUri.port" -> wireMockPort
      )

  private implicit val hc = HeaderCarrier()

  val mappingConnector = new MappingConnector(wireMockBaseUrl, WSHttp, app.injector.instanceOf[Metrics])

  "MappingConnector" should {

    val arn = Arn("foo")

    "return CESA agent reference for some known ARN" in {
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      await(mappingConnector.getSaAgentReferencesFor(arn)) shouldBe Seq(SaAgentReference("foo"))
    }

    "return multiple CESA agent reference for some known ARN" in {
      val references = Seq(SaAgentReference("001"), SaAgentReference("002"))
      givenArnIsKnownFor(arn, references)
      await(mappingConnector.getSaAgentReferencesFor(arn)) shouldBe references
    }

    "fail when arn is unknown in " in {
      givenArnIsUnknownFor(arn)
      an[Exception] should be thrownBy await(mappingConnector.getSaAgentReferencesFor(arn))
    }

    "fail when mapping service is unavailable" in {
      givenServiceReturnsServiceUnavailable()
      an[Exception] should be thrownBy await(mappingConnector.getSaAgentReferencesFor(arn))
    }

    "fail when mapping service is throwing errors" in {
      givenServiceReturnsServerError()
      an[Exception] should be thrownBy await(mappingConnector.getSaAgentReferencesFor(arn))
    }

    "record metrics for Mappings" in {
      val metricsRegistry = app.injector.instanceOf[Metrics].defaultRegistry
      metricsRegistry.getTimers.get("Timer-ConsumedAPI-Digital-Mappings-GET").getCount should be >= 1L
    }


  }


}