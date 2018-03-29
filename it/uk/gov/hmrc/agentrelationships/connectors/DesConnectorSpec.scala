package uk.gov.hmrc.agentrelationships.connectors

import com.github.tomakehurst.wiremock.client.WireMock.{ equalToJson, postRequestedFor, urlPathEqualTo, verify }
import com.kenshoo.play.metrics.Metrics
import org.scalatestplus.play.OneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.utils.UriEncoding
import uk.gov.hmrc.agentclientrelationships.connectors.DesConnector
import uk.gov.hmrc.agentmtdidentifiers.model.{ Arn, MtdItId, Vrn }
import uk.gov.hmrc.agentrelationships.stubs.{ DataStreamStub, DesStubs }
import uk.gov.hmrc.agentrelationships.support.{ MetricTestSupport, WireMockSupport }
import uk.gov.hmrc.domain.{ Nino, SaAgentReference }
import uk.gov.hmrc.http.{ HeaderCarrier, HttpGet, HttpPost }
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext

class DesConnectorSpec extends UnitSpec with OneAppPerSuite with WireMockSupport with DesStubs with DataStreamStub with MetricTestSupport {

  override implicit lazy val app: Application = appBuilder
    .build()

  val httpGet = app.injector.instanceOf[HttpGet]
  val httpPost = app.injector.instanceOf[HttpPost]

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.des.port" -> wireMockPort,
        "auditing.consumer.baseUri.host" -> wireMockHost,
        "auditing.consumer.baseUri.port" -> wireMockPort)

  private implicit val hc = HeaderCarrier()
  private implicit val ec = ExecutionContext.global

  val desConnector = new DesConnector(wireMockBaseUrl, "token", "stub", httpGet, httpPost, app.injector.instanceOf[Metrics])

  val mtdItId = MtdItId("ABCDEF123456789")
  val agentARN = Arn("ABCDE123456")

  "DesConnector GetRegistrationBusinessDetails" should {

    val mtdItId = MtdItId("foo")
    val nino = Nino("AB123456C")

    "return some nino when agent's mtdbsa identifier is known to ETMP" in {
      givenNinoIsKnownFor(mtdItId, nino)
      givenAuditConnector()
      await(desConnector.getNinoFor(mtdItId)) shouldBe nino
    }

    "return nothing when agent's mtdbsa identifier is unknown to ETMP" in {
      givenNinoIsUnknownFor(mtdItId)
      givenAuditConnector()
      an[Exception] should be thrownBy await(desConnector.getNinoFor(mtdItId))
    }

    "fail when agent's mtdbsa identifier is invalid" in {
      givenMtdbsaIsInvalid(mtdItId)
      givenAuditConnector()
      an[Exception] should be thrownBy await(desConnector.getNinoFor(mtdItId))
    }

    "fail when DES is unavailable" in {
      givenDesReturnsServiceUnavailable()
      givenAuditConnector()
      an[Exception] should be thrownBy await(desConnector.getNinoFor(mtdItId))
    }

    "fail when DES is throwing errors" in {
      givenDesReturnsServerError()
      givenAuditConnector()
      an[Exception] should be thrownBy await(desConnector.getNinoFor(mtdItId))
    }

    "record metrics for GetRegistrationBusinessDetailsByMtdbsa" in {
      givenNinoIsKnownFor(mtdItId, Nino("AB123456C"))
      givenCleanMetricRegistry()
      givenAuditConnector()
      await(desConnector.getNinoFor(mtdItId))
      timerShouldExistsAndBeenUpdated("ConsumedAPI-DES-GetRegistrationBusinessDetailsByMtdbsa-GET")
    }

    "return MtdItId when agent's nino is known to ETMP" in {
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenAuditConnector()
      await(desConnector.getMtdIdFor(nino)) shouldBe mtdItId
    }

    "return nothing when agent's nino identifier is unknown to ETMP" in {
      givenMtdItIdIsUnKnownFor(nino)
      givenAuditConnector()
      an[Exception] should be thrownBy await(desConnector.getMtdIdFor(nino))
    }
  }

  "DesConnector GetStatusAgentRelationship" should {

    val nino = Nino("AB123456C")

    "return a CESA identifier when client has an active agent" in {
      val agentId = "bar"
      givenClientHasRelationshipWithAgentInCESA(nino, agentId)
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe Seq(SaAgentReference(agentId))
    }

    "return multiple CESA identifiers when client has multiple active agents" in {
      val agentIds = Seq("001", "002", "003", "004", "005", "005", "007")
      givenClientHasRelationshipWithMultipleAgentsInCESA(nino, agentIds)
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) should contain theSameElementsAs agentIds.map(SaAgentReference.apply)
    }

    "return empty seq when client has no active relationship with an agent" in {
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe empty
    }

    "return empty seq when client has/had no relationship with any agent" in {
      givenClientHasNoRelationshipWithAnyAgentInCESA(nino)
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe empty
    }

    "return empty seq when client relationship with agent ceased" in {
      givenClientRelationshipWithAgentCeasedInCESA(nino, "foo")
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe empty
    }

    "return empty seq when all client's relationships with agents ceased" in {
      givenAllClientRelationshipsWithAgentsCeasedInCESA(nino, Seq("001", "002", "003", "004", "005", "005", "007"))
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino)) shouldBe empty
    }

    "fail when client's nino is invalid" in {
      givenNinoIsInvalid(nino)
      givenAuditConnector()
      an[Exception] should be thrownBy await(desConnector.getClientSaAgentSaReferences(nino))
    }

    "fail when client is unknown" in {
      givenClientIsUnknownInCESAFor(nino)
      givenAuditConnector()
      an[Exception] should be thrownBy await(desConnector.getClientSaAgentSaReferences(nino))
    }

    "fail when DES is unavailable" in {
      givenDesReturnsServiceUnavailable()
      givenAuditConnector()
      an[Exception] should be thrownBy await(desConnector.getClientSaAgentSaReferences(nino))
    }

    "fail when DES is throwing errors" in {
      givenDesReturnsServerError()
      givenAuditConnector()
      an[Exception] should be thrownBy await(desConnector.getClientSaAgentSaReferences(nino))
    }

    "record metrics for GetStatusAgentRelationship Cesa" in {
      givenClientHasRelationshipWithAgentInCESA(nino, "bar")
      givenCleanMetricRegistry()
      givenAuditConnector()
      await(desConnector.getClientSaAgentSaReferences(nino))
      timerShouldExistsAndBeenUpdated("ConsumedAPI-DES-GetStatusAgentRelationship-GET")
    }
  }

  "DesConnector CreateAgentRelationship" should {
    "create relationship between agent and client and return 200" in {
      givenAgentCanBeAllocatedInDes(MtdItId("foo"), Arn("bar"))
      givenAuditConnector()
      await(desConnector.createAgentRelationship(MtdItId("foo"), Arn("bar"))).processingDate should not be null
    }

    "not create relationship between agent and client and return 404" in {
      givenAgentCanNotBeAllocatedInDes
      givenAuditConnector()
      an[Exception] should be thrownBy await(desConnector.createAgentRelationship(MtdItId("foo"), Arn("bar")))
    }

    "request body contains regime as ITSA when client Id is an MtdItId" in {
      givenAgentCanBeAllocatedInDes(MtdItId("foo"), Arn("someArn"))
      givenAuditConnector()

      await(desConnector.createAgentRelationship(MtdItId("foo"), Arn("someArn")))

      verify(1, postRequestedFor(urlPathEqualTo("/registration/relationship"))
        .withRequestBody(equalToJson(s"""{ "regime": "ITSA"}""", true, true)))
    }

    "request body contains regime as VATC and idType as VRN when client Id is a Vrn" in {
      givenAgentCanBeAllocatedInDes(Vrn("someVrn"), Arn("someArn"))
      givenAuditConnector()

      await(desConnector.createAgentRelationship(Vrn("someVrn"), Arn("someArn")))

      verify(1, postRequestedFor(urlPathEqualTo("/registration/relationship"))
        .withRequestBody(equalToJson(s"""{ "regime": "VATC", "idType" : "VRN" }""", true, true)))
    }
  }

  "DesConnector DeleteAgentRelationship" should {
    "delete relationship between agent and client and return 200" in {
      givenAgentCanBeDeallocatedInDes(MtdItId("foo"), Arn("bar"))
      givenAuditConnector()
      await(desConnector.deleteAgentRelationship(MtdItId("foo"), Arn("bar"))).processingDate should not be null
    }

    "not delete relationship between agent and client and return 404" in {
      givenAgentCanNotBeDeallocatedInDes
      givenAuditConnector()
      an[Exception] should be thrownBy await(desConnector.deleteAgentRelationship(MtdItId("foo"), Arn("bar")))
    }
  }

  "DesConnector GetStatusAgentRelationship" should {
    val encodedClientId = UriEncoding.encodePathSegment(mtdItId.value, "UTF-8")

    "return existing active relationships for specified clientId" in {
      getClientActiveAgentRelationships(encodedClientId, "ITSA", agentARN.value)

      val result = await(desConnector.getActiveClientItsaRelationships(mtdItId))
      result.get.arn shouldBe agentARN
    }

    "return notFound active relationships for specified clientId" in {
      getNotFoundClientActiveAgentRelationships(encodedClientId, "ITSA")

      val result = await(desConnector.getActiveClientItsaRelationships(mtdItId))
      result shouldBe None
    }

    "record metrics for GetStatusAgentRelationship" in {
      getClientActiveAgentRelationships(encodedClientId, "ITSA", agentARN.value)

      val result = await(desConnector.getActiveClientItsaRelationships(mtdItId))
      result.get.arn shouldBe agentARN
      timerShouldExistsAndBeenUpdated("ConsumedAPI-DES-GetStatusAgentRelationship-GET")
    }
  }
}