package uk.gov.hmrc.agentrelationships.connectors

import com.kenshoo.play.metrics.Metrics
import org.scalatestplus.play.OneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.WSHttp
import uk.gov.hmrc.agentclientrelationships.connectors.{EnrolmentStoreProxyConnector, RelationshipNotFound}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}
import uk.gov.hmrc.agentrelationships.stubs.{DataStreamStub, EnrolmentStoreProxyStubs}
import uk.gov.hmrc.agentrelationships.support.{MetricTestSupport, WireMockSupport}
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
        "auditing.consumer.baseUri.host" -> wireMockHost,
        "auditing.consumer.baseUri.port" -> wireMockPort
      )

  implicit val hc = HeaderCarrier()

  val connector = new EnrolmentStoreProxyConnector(wireMockBaseUrl, WSHttp, app.injector.instanceOf[Metrics])

  "EnrolmentStoreProxy" should {

    "return some agent's groupId for given ARN" in {
      givenAuditConnector()
      givenGroupIdExistsForArn(Arn("foo"),"bar")
      await(connector.getGroupIdFor(Arn("foo"))) shouldBe "bar"
    }

    "return RelationshipNotFound Exception when ARN not found" in {
      givenAuditConnector()
      givenGroupIdNotExistsForArn(Arn("foo"))
      an[RelationshipNotFound] shouldBe thrownBy {
        await(connector.getGroupIdFor(Arn("foo")))
      }
    }

    "return some client's groupIds for given MTDITID" in {
      givenAuditConnector()
      givenGroupIdsExistForMTDITID(MtdItId("foo"), Set("bar", "car", "dar"))
      await(connector.getGroupIdsFor(MtdItId("foo"))) should contain("bar")
    }

    "return Empty when MTDITIT not found" in {
      givenAuditConnector()
      givenGroupIdsNotExistForMTDITID(MtdItId("foo"))
      await(connector.getGroupIdsFor(MtdItId("foo"))) should be(empty)
    }
  }
}