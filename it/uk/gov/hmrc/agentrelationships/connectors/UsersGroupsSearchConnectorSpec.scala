package uk.gov.hmrc.agentrelationships.connectors

import com.kenshoo.play.metrics.Metrics
import org.scalatestplus.play.OneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.connectors.{GroupInfo, UsersGroupsSearchConnector}
import uk.gov.hmrc.agentclientrelationships.support.{AdminNotFound, RelationshipNotFound}
import uk.gov.hmrc.agentrelationships.stubs.{DataStreamStub, UsersGroupsSearchStubs}
import uk.gov.hmrc.agentrelationships.support.{MetricTestSupport, WireMockSupport}
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.http.{HeaderCarrier, HttpGet}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class UsersGroupsSearchConnectorSpec
    extends UnitSpec
    with OneServerPerSuite
    with WireMockSupport
    with DataStreamStub
    with MetricTestSupport
    with UsersGroupsSearchStubs {

  override implicit lazy val app: Application = appBuilder
    .build()

  val httpGet: HttpGet = app.injector.instanceOf[HttpGet]

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.users-groups-search.port" -> wireMockPort,
        "auditing.consumer.baseUri.host"                 -> wireMockHost,
        "auditing.consumer.baseUri.port"                 -> wireMockPort,
        "features.recovery-enable"                       -> "false"
      )

  implicit val hc: HeaderCarrier = HeaderCarrier()

  val connector = new UsersGroupsSearchConnector(wireMockBaseUrl, httpGet, app.injector.instanceOf[Metrics])

  "UsersGroupsSearchConnector" should {

    "givenGroupInfo endpoint" should {

      "return some agentCode for a given agent's groupId" in {
        givenAuditConnector()
        givenAgentGroupExistsFor("foo")
        await(connector.getGroupInfo("foo")) shouldBe Some(GroupInfo("foo", Some("Agent"), Some(AgentCode("NQJUEJCWT14"))))
      }

      "return none agentCode for a given non-agent groupId" in {
        givenAuditConnector()
        givenNonAgentGroupExistsFor("foo")
        await(connector.getGroupInfo("foo")) shouldBe Some(GroupInfo("foo", Some("Organisation"), None))
      }

      "return onone if group not exists" in {
        givenAuditConnector()
        givenGroupNotExistsFor("foo")
        await(connector.getGroupInfo("foo")) shouldBe None
      }
    }

  }



}
