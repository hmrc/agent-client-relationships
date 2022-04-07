package uk.gov.hmrc.agentclientrelationships.connectors

import com.kenshoo.play.metrics.Metrics
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.stubs.{DataStreamStub, MappingStubs}
import uk.gov.hmrc.agentclientrelationships.support.{MetricTestSupport, UnitSpec, WireMockSupport}
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.domain.{AgentCode, SaAgentReference}
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}

import scala.concurrent.ExecutionContext.Implicits.global

class MappingConnectorSpec
    extends UnitSpec
    with GuiceOneServerPerSuite
    with WireMockSupport
    with MappingStubs
    with DataStreamStub
    with MetricTestSupport {

  override implicit lazy val app: Application = appBuilder
    .build()

  val httpClient = app.injector.instanceOf[HttpClient]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
        "microservice.services.tax-enrolments.port"        -> wireMockPort,
        "microservice.services.users-groups-search.port"   -> wireMockPort,
        "microservice.services.des.port"                   -> wireMockPort,
        "microservice.services.auth.port"                  -> wireMockPort,
        "microservice.services.agent-mapping.port"         -> wireMockPort,
        "auditing.consumer.baseUri.host"                   -> wireMockHost,
        "auditing.consumer.baseUri.port"                   -> wireMockPort,
        "features.copy-relationship.mtd-it"                -> true,
        "features.copy-relationship.mtd-vat"               -> true,
        "features.recovery-enable"                         -> false,
        "agent.cache.size"                                 -> 1,
        "agent.cache.expires"                              -> "1 millis",
        "agent.cache.enabled"                              -> true,
        "agent.trackPage.cache.size"                                 -> 1,
        "agent.trackPage.cache.expires"                              -> "1 millis",
        "agent.trackPage.cache.enabled"                              -> true
      )

  private implicit val hc = HeaderCarrier()

  val mappingConnector = new MappingConnector(
    httpClient,
    app.injector.instanceOf[Metrics]
  )

  "MappingConnector" should {

    val arn = Arn("foo")

    "return CESA agent reference for some known ARN" in {
      givenAuditConnector()
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      await(mappingConnector.getSaAgentReferencesFor(arn)) shouldBe Seq(SaAgentReference("foo"))
    }

    "return multiple CESA agent reference for some known ARN" in {
      val references = Seq(SaAgentReference("001"), SaAgentReference("002"))
      givenArnIsKnownFor(arn, references)
      givenAuditConnector()
      await(mappingConnector.getSaAgentReferencesFor(arn)) shouldBe references
    }

    "return empty sequence when arn is unknown in " in {
      givenArnIsUnknownFor(arn)
      givenAuditConnector()
      await(mappingConnector.getSaAgentReferencesFor(arn)) shouldBe empty
    }

    "return empty sequence when mapping service is unavailable" in {
      givenServiceReturnsServiceUnavailable()
      givenAuditConnector()
      await(mappingConnector.getSaAgentReferencesFor(arn)) shouldBe empty
    }

    "return empty sequence when mapping service is throwing errors" in {
      givenServiceReturnsServerError()
      givenAuditConnector()
      await(mappingConnector.getSaAgentReferencesFor(arn)) shouldBe empty
    }

    "record metrics for Mappings" in {
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenCleanMetricRegistry()
      givenAuditConnector()
      await(mappingConnector.getSaAgentReferencesFor(arn))
      timerShouldExistsAndBeenUpdated("ConsumedAPI-Digital-Mappings-GET")
    }

    "return agent codes for some known ARN" in {
      givenArnIsKnownFor(arn, AgentCode("foo"))
      givenAuditConnector()
      await(mappingConnector.getAgentCodesFor(arn)) shouldBe Seq(AgentCode("foo"))
    }

    "return multiple agent codes for some known ARN" in {
      val oldAgentCodes = Seq(AgentCode("001"), AgentCode("002"))
      givenArnIsKnownForAgentCodes(arn, oldAgentCodes)
      givenAuditConnector()
      await(mappingConnector.getAgentCodesFor(arn)) shouldBe oldAgentCodes
    }
  }
}
