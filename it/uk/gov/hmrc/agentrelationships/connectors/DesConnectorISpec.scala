package uk.gov.hmrc.agentrelationships.connectors

import org.scalatestplus.play.OneAppPerSuite
import uk.gov.hmrc.agentclientrelationships.WSHttp
import uk.gov.hmrc.agentclientrelationships.connectors.DesConnector
import uk.gov.hmrc.agentmtdidentifiers.model.MtdItId
import uk.gov.hmrc.agentrelationships.stubs.DesStubs
import uk.gov.hmrc.agentrelationships.support.WireMockSupport
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec

class DesConnectorISpec extends UnitSpec with OneAppPerSuite with WireMockSupport with DesStubs {

  private implicit val hc = HeaderCarrier()

  val desConnector = new DesConnector(wireMockBaseUrl, "token", "stub", WSHttp)

  "DesConnector GetRegistrationBusinessDetails" should {

    val mtdItId = MtdItId("foo")

    "return some nino when agent's mtdbsa identifier is known to ETMP" in {
      val nino = Nino("AB123456C")
      givenNinoIsKnownFor(mtdItId, nino)
      await(desConnector.getNinoFor(mtdItId)) shouldBe nino
    }

    "return nothing when agent's mtdbsa identifier is unknown to ETMP" in {
      givenNinoIsUnknownFor(mtdItId)
      an[Exception] should be thrownBy await(desConnector.getNinoFor(mtdItId))
    }

    "fail when agent's mtdbsa identifier is invalid" in {
      givenMtdbsaIsInvalid(mtdItId)
      an[Exception] should be thrownBy await(desConnector.getNinoFor(mtdItId))
    }

    "fail when DES is unavailable" in {
      givenDesReturnsServiceUnavailable()
      an[Exception] should be thrownBy await(desConnector.getNinoFor(mtdItId))
    }

    "fail when DES is throwing errors" in {
      givenDesReturnsServerError()
      an[Exception] should be thrownBy await(desConnector.getNinoFor(mtdItId))
    }
  }

  "DesConnector GetStatusAgentRelationship" should {

    val nino = Nino("AB123456C")

    "return a CESA identifier when client has an active agent" in {
      val agentId = "bar"
      givenClientHasRelationshipWithAgent(nino, agentId)
      await(desConnector.getCesaAgentReferencesFor(nino)) shouldBe Seq(agentId)
    }

    "return multiple CESA identifiers when client has multiple active agents" in {
      val agentIds = Seq("001","002","003","004","005","005","007")
      givenClientHasRelationshipWithMultipleAgents(nino, agentIds)
      await(desConnector.getCesaAgentReferencesFor(nino)) should contain theSameElementsAs agentIds
    }

    "return empty seq when client has no active relationship with an agent" in {
      givenClientHasNoActiveRelationshipWithAgent(nino)
      await(desConnector.getCesaAgentReferencesFor(nino)) shouldBe empty
    }

    "return empty seq when client has/had no relationship with any agent" in {
      givenClientHasNoRelationshipWithAnyAgent(nino)
      await(desConnector.getCesaAgentReferencesFor(nino)) shouldBe empty
    }

    "return empty seq when client relationship with agent ceased" in {
      givenClientRelationshipWithAgentCeased(nino, "foo")
      await(desConnector.getCesaAgentReferencesFor(nino)) shouldBe empty
    }

    "return empty seq when all client's relationships with agents ceased" in {
      givenAllClientRelationshipsWithAgentsCeased(nino, Seq("001","002","003","004","005","005","007"))
      await(desConnector.getCesaAgentReferencesFor(nino)) shouldBe empty
    }

    "fail when client's nino is invalid" in {
      givenNinoIsInvalid(nino)
      an[Exception] should be thrownBy await(desConnector.getCesaAgentReferencesFor(nino))
    }

    "fail when client is unknown" in {
      givenClientIsUnknownFor(nino)
      an[Exception] should be thrownBy await(desConnector.getCesaAgentReferencesFor(nino))
    }

    "fail when DES is unavailable" in {
      givenDesReturnsServiceUnavailable()
      an[Exception] should be thrownBy await(desConnector.getCesaAgentReferencesFor(nino))
    }

    "fail when DES is throwing errors" in {
      givenDesReturnsServerError()
      an[Exception] should be thrownBy await(desConnector.getCesaAgentReferencesFor(nino))
    }
  }
}