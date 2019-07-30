package uk.gov.hmrc.agentrelationships.connectors

import com.kenshoo.play.metrics.Metrics
import org.scalatestplus.play.OneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.connectors.{GroupInfo, UsersGroupsSearchConnector}
import uk.gov.hmrc.agentclientrelationships.support.{AdminNotFound, RelationshipNotFound, UserNotFound}
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

  val httpGet = app.injector.instanceOf[HttpGet]

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
        await(connector.getGroupInfo("foo")) shouldBe GroupInfo("foo", Some("Agent"), Some(AgentCode("NQJUEJCWT14")))
      }

      "return none agentCode for a given non-agent groupId" in {
        givenAuditConnector()
        givenNonAgentGroupExistsFor("foo")
        await(connector.getGroupInfo("foo")) shouldBe GroupInfo("foo", Some("Organisation"), None)
      }

      "throw exception if group not exists" in {
        givenAuditConnector()
        givenGroupNotExistsFor("foo")
        an[RelationshipNotFound] shouldBe thrownBy {
          await(connector.getGroupInfo("foo"))
        }
      }
    }

    "isAdmin endpoint" should {
      "return true if the user is an admin" in {
        givenAuditConnector()
        givenUserIdIsAdmin("userId")
        await(connector.isAdmin("userId")) shouldBe true
      }

      "return false if the user is not an admin" in {
        givenAuditConnector()
        givenUserIdIsNotAdmin("userId")
        await(connector.isAdmin("userId")) shouldBe false
      }

      "throw an exception if the user does not exist" in {
        givenAuditConnector()
        givenUserIdNotExistsFor("userId")
        intercept[UserNotFound] {
          await(connector.isAdmin("userId"))
        }
      }
    }

    "getAdminUser" should {
      "return the first user id that is an admin" in {
        givenUserIdIsNotAdmin("userId-1")
        givenUserIdIsAdmin("userId-2")
        givenUserIdIsAdmin("userId-3")

        await(connector.getAdminUserId(Seq("userId-1", "userId-2", "userId-3"))) shouldBe "userId-2"
      }

      "throw a AdminNotFound if there is no admin user in the group of user ids" in {
        givenUserIdIsNotAdmin("userId-1")
        givenUserIdIsNotAdmin("userId-2")
        givenUserIdIsNotAdmin("userId-3")

        intercept[AdminNotFound] {
          await(connector.getAdminUserId(Seq("userId-1", "userId-2", "userId-3"))) shouldBe "userId-2"
        }.getMessage shouldBe "NO_ADMIN_USER"
      }

      "throw a UserNotFound exception is there is no user found for the user id" in {
        givenUserIdNotExistsFor("userId-1")
        givenUserIdIsNotAdmin("userId-2")
        givenUserIdIsNotAdmin("userId-3")

        intercept[UserNotFound] {
          await(connector.getAdminUserId(Seq("userId-1", "userId-2", "userId-3"))) shouldBe "userId-2"
        }.getMessage shouldBe "UNKNOWN_USER_ID"
      }
    }
  }



}
