package uk.gov.hmrc.agentrelationships.connectors

import java.net.URL

import org.scalatestplus.play.OneAppPerSuite
import uk.gov.hmrc.agentclientrelationships.connectors.{AuthConnector, Authority}
import uk.gov.hmrc.agentrelationships.stubs.AuthStub
import uk.gov.hmrc.agentrelationships.support.WireMockSupport
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.agentclientrelationships.WSHttp

import scala.concurrent.ExecutionContext.Implicits.global

class AuthConnectorISpec extends UnitSpec with OneAppPerSuite with WireMockSupport with AuthStub {
  private implicit val hc = HeaderCarrier()
  private lazy val connector: AuthConnector = new AuthConnector(new URL(s"http://localhost:$wireMockPort"), WSHttp)

  "AuthConnector currentAuthority" should {
    "return Authority when an authority detail is available" in {
      requestIsAuthenticated().andIsAnAgent()
      await(connector.currentAuthority()) shouldBe Some(Authority(
        enrolmentsUrl = "/auth/oid/556737e15500005500eaf68f/enrolments"))
    }

    "return Authority when user-details does not include an auth provider" in {
      requestIsAuthenticated().andIsAnAgentWithoutAuthProvider()
      await(connector.currentAuthority()) shouldBe Some(Authority(
        enrolmentsUrl = "/auth/oid/556737e15500005500eaf68f/enrolments"))
    }

    "return none when an authority detail is unavailable" in {
      requestIsNotAuthenticated()
      await(connector.currentAuthority()) shouldBe None
    }
  }
}
