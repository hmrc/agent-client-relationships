package uk.gov.hmrc.agentrelationships.connectors

import com.kenshoo.play.metrics.Metrics
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.AgentClientAuthorisationConnector
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentrelationships.stubs.ACAStubs
import uk.gov.hmrc.agentrelationships.support.{MetricTestSupport, WireMockSupport}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}
import uk.gov.hmrc.play.test.UnitSpec
import scala.concurrent.ExecutionContext.Implicits.global

class AgentClientAuthorisationConnectorSpec  extends UnitSpec
  with GuiceOneServerPerSuite
  with WireMockSupport
  with ACAStubs
  with MetricTestSupport
  with MockitoSugar {

  override implicit lazy val app: Application = appBuilder
    .build()

  val httpClient = app.injector.instanceOf[HttpClient]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.agent-client-authorisation.port" -> wireMockPort
      )

  private implicit val hc = HeaderCarrier()

  val agentARN = Arn("ABCDE123456")
  val nino = Nino("AB213308A")

  val acaConnector = new AgentClientAuthorisationConnector(
    httpClient,
    app.injector.instanceOf[Metrics]
  )

  "getPartialAuthExistsFor" should {

    "return true when a record exists with status PartialAuth for client" in {
      givenPartialAuthExistsFor(agentARN , nino)
      givenCleanMetricRegistry()
      val result = await(acaConnector.getPartialAuthExistsFor(nino, agentARN, "HMRC-MTD-IT"))
      result shouldBe true
      timerShouldExistsAndBeenUpdated("ConsumedAPI-ACA-getPartialAuthExistsFor-HMRC-MTD-IT-GET")
    }

    "return false when no record exists with PartialAuth for client" in {
      givenPartialAuthNotExistsFor(agentARN , nino)
      givenCleanMetricRegistry()
      val result = await(acaConnector.getPartialAuthExistsFor(nino, agentARN, "HMRC-MTD-IT"))
      result shouldBe false
      timerShouldExistsAndBeenUpdated("ConsumedAPI-ACA-getPartialAuthExistsFor-HMRC-MTD-IT-GET")
    }

    "return false when there is a problem with the upstream service" in {
      givenAgentClientAuthorisationReturnsError(agentARN , nino, 503)
      givenCleanMetricRegistry()
      val result = await(acaConnector.getPartialAuthExistsFor(nino, agentARN, "HMRC-MTD-IT"))
      result shouldBe false
      timerShouldExistsAndBeenUpdated("ConsumedAPI-ACA-getPartialAuthExistsFor-HMRC-MTD-IT-GET")
    }

  }



}
