package uk.gov.hmrc.agentrelationships.services

import com.kenshoo.play.metrics.Metrics
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.audit.AuditData
import uk.gov.hmrc.agentclientrelationships.connectors.{EnrolmentStoreProxyConnector, UserDetails, UsersGroupsSearchConnector}
import uk.gov.hmrc.agentclientrelationships.services.{AgentUser, AgentUserService}
import uk.gov.hmrc.agentclientrelationships.support.{AdminNotFound, RelationshipNotFound}
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentrelationships.stubs.{EnrolmentStoreProxyStubs, UsersGroupsSearchStubs}
import uk.gov.hmrc.agentrelationships.support.WireMockSupport
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.http.{HeaderCarrier, HttpDelete, HttpGet, HttpPost}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext

class AgentUserServiceISpec
  extends UnitSpec
    with GuiceOneAppPerSuite
    with WireMockSupport
    with EnrolmentStoreProxyStubs
    with UsersGroupsSearchStubs {

  override implicit lazy val app: Application = appBuilder.build()

  val httpGet = app.injector.instanceOf[HttpGet with HttpPost with HttpDelete]

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.users-groups-search.port" -> wireMockPort,
        "auditing.consumer.baseUri.host"                 -> wireMockHost,
        "auditing.consumer.baseUri.port"                 -> wireMockPort,
        "features.recovery-enable"                       -> "false"
      )

  val esConnector =
    new EnrolmentStoreProxyConnector(wireMockBaseUrl, wireMockBaseUrl, httpGet, app.injector.instanceOf[Metrics])

  private val searchConnector = new UsersGroupsSearchConnector(wireMockBaseUrl, httpGet, app.injector.instanceOf[Metrics])

  private val agentUserService = new AgentUserService(esConnector, searchConnector)

  implicit val hc: HeaderCarrier = HeaderCarrier()
  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  implicit val auditData = new AuditData

  val arnNoAdmin = Arn("noadmin")
  val arnWithAdmin = Arn("withadmin")

  "AgentUserService" should {

    "Throw exception when no principal users found for ARN" in {
      givenPrincipalGroupIdExistsFor(arnNoAdmin, "bar")
      givenAgentGroupWithUsers("bar", List.empty)
      an[AdminNotFound] shouldBe thrownBy {
        await(agentUserService.getAgentAdminUserFor(arnNoAdmin))
      }
    }

    "Throw exception when no admin user among principal users found for ARN" in {
      givenPrincipalGroupIdExistsFor(arnNoAdmin, "bar")
      givenAgentGroupWithUsers("bar",
        List(
          UserDetails(userId = Some("baz1"), credentialRole = Some("Assistant")),
          UserDetails(userId = Some("baz2"), credentialRole = Some("Assistant"))
        )
      )
      an[AdminNotFound] shouldBe thrownBy {
        await(agentUserService.getAgentAdminUserFor(arnNoAdmin))
      }
    }

    "Find first admin user for ARN" in {
      givenPrincipalGroupIdExistsFor(arnWithAdmin, "bar")
      givenAgentGroupExistsFor("bar")
      givenAgentGroupWithUsers("bar",
        List(
          UserDetails(userId = Some("assistant1"), credentialRole = Some("Assistant")),
          UserDetails(userId = Some("administrator"), credentialRole = Some("Admin")),
          UserDetails(userId = Some("assistant3"), credentialRole = Some("Assistant"))
        )
      )
      await(agentUserService.getAgentAdminUserFor(arnWithAdmin)) shouldBe
        AgentUser("administrator", "bar", AgentCode("NQJUEJCWT14"), arnWithAdmin)
    }

  }

}
