package uk.gov.hmrc.agentrelationships.connectors

import org.scalatestplus.play.OneAppPerSuite
import uk.gov.hmrc.agentclientrelationships.WSHttp
import uk.gov.hmrc.agentclientrelationships.connectors.MappingConnector
import uk.gov.hmrc.agentmtdidentifiers.model.Arn
import uk.gov.hmrc.agentrelationships.stubs.MappingStubs
import uk.gov.hmrc.agentrelationships.support.WireMockSupport
import uk.gov.hmrc.domain.SaAgentReference
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec

class MappingConnectorSpec extends UnitSpec with OneAppPerSuite with WireMockSupport with MappingStubs {

  private implicit val hc = HeaderCarrier()

  val mappingConnector = new MappingConnector(wireMockBaseUrl, WSHttp)

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

  }


}