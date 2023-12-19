package uk.gov.hmrc.agentclientrelationships.connectors

import com.kenshoo.play.metrics.Metrics
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.stubs.{DataStreamStub, UsersGroupsSearchStubs}
import uk.gov.hmrc.agentclientrelationships.support.{MetricTestSupport, WireMockSupport}
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpClient
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class UsersGroupsSearchConnectorSpec
    extends UnitSpec
    with GuiceOneServerPerSuite
    with WireMockSupport
    with DataStreamStub
    with MetricTestSupport
    with UsersGroupsSearchStubs {

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
        "agent.trackPage.cache.size"                       -> 1,
        "agent.trackPage.cache.expires"                    -> "1 millis",
        "agent.trackPage.cache.enabled"                    -> true
      )

  implicit val hc: HeaderCarrier = HeaderCarrier()

  val connector = new UsersGroupsSearchConnector(httpClient, app.injector.instanceOf[Metrics])

  "UsersGroupsSearchConnector" should {

    "givenGroupInfo endpoint" should {

      "return some agentCode for a given agent's groupId" in {
        givenAuditConnector()
        givenAgentGroupExistsFor("foo")
        await(connector.getGroupInfo("foo")) shouldBe Some(
          GroupInfo("foo", Some("Agent"), Some(AgentCode("NQJUEJCWT14")))
        )
      }

      "return none agentCode for a given non-agent groupId" in {
        givenAuditConnector()
        givenNonAgentGroupExistsFor("foo")
        await(connector.getGroupInfo("foo")) shouldBe Some(GroupInfo("foo", Some("Organisation"), None))
      }

      "return none if group not exists" in {
        givenAuditConnector()
        givenGroupNotExistsFor("foo")
        await(connector.getGroupInfo("foo")) shouldBe None
      }
    }

  }

}
