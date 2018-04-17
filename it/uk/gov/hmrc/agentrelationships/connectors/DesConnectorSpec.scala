package uk.gov.hmrc.agentrelationships.connectors

import com.github.tomakehurst.wiremock.client.WireMock.{ equalToJson, postRequestedFor, urlPathEqualTo, verify }
import com.kenshoo.play.metrics.Metrics
import org.joda.time.LocalDate
import org.scalatestplus.play.OneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.utils.UriEncoding
import uk.gov.hmrc.agentclientrelationships.connectors.{ DesConnector, ItsaRelationship }
import uk.gov.hmrc.agentmtdidentifiers.model.{ Arn, MtdItId, Utr, Vrn }
import uk.gov.hmrc.agentrelationships.stubs.{ DataStreamStub, DesStubs }
import uk.gov.hmrc.agentrelationships.support.{ MetricTestSupport, WireMockSupport }
import uk.gov.hmrc.domain.{ Nino, SaAgentReference }
import uk.gov.hmrc.http.{ HeaderCarrier, HttpGet, HttpPost, Upstream5xxResponse }
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
  val vrn = Vrn("101747641")
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
      givenAgentCanNotBeAllocatedInDes(status = 404)
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
        .withRequestBody(equalToJson(
          s"""{
             |"regime": "VATC",
             |"idType" : "VRN",
             |"relationshipType" : "ZA01",
             |"authProfile" : "ALL00001"
             |}""".stripMargin, true, true)))
    }

    "throw an IllegalArgumentException when the tax identifier is not supported" in {
      an[IllegalArgumentException] should be thrownBy await(desConnector.createAgentRelationship(Utr("foo"), Arn("bar")))
    }

    "fail when DES is throwing errors" in {
      givenDesReturnsServerError()
      an[Upstream5xxResponse] should be thrownBy await(desConnector.createAgentRelationship(Vrn("someVrn"), Arn("someArn")))
    }

    "fail when DES is unavailable" in {
      givenDesReturnsServiceUnavailable()
      an[Upstream5xxResponse] should be thrownBy await(desConnector.createAgentRelationship(Vrn("someVrn"), Arn("someArn")))
    }
  }

  "DesConnector DeleteAgentRelationship" should {
    "delete relationship between agent and client and return 200 for ItSa service" in {
      givenAgentCanBeDeallocatedInDes(MtdItId("foo"), Arn("bar"))
      givenAuditConnector()
      await(desConnector.deleteAgentRelationship(MtdItId("foo"), Arn("bar"))).processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for Vat service" in {
      givenAgentCanBeDeallocatedInDes(Vrn("foo"), Arn("bar"))
      givenAuditConnector()
      await(desConnector.deleteAgentRelationship(Vrn("foo"), Arn("bar"))).processingDate should not be null
    }

    "not delete relationship between agent and client and return 404 for ItSa service" in {
      givenAgentCanNotBeDeallocatedInDes(status = 404)
      givenAuditConnector()
      an[Exception] should be thrownBy await(desConnector.deleteAgentRelationship(MtdItId("foo"), Arn("bar")))
    }

    "not delete relationship between agent and client and return 404 for Vat service" in {
      givenAgentCanNotBeDeallocatedInDes(status = 404)
      givenAuditConnector()
      an[Exception] should be thrownBy await(desConnector.deleteAgentRelationship(Vrn("foo"), Arn("bar")))
    }

    "throw an IllegalArgumentException when the tax identifier is not supported" in {
      an[IllegalArgumentException] should be thrownBy await(desConnector.deleteAgentRelationship(Utr("foo"), Arn("bar")))
    }

    "fail when DES is throwing errors" in {
      givenDesReturnsServerError()
      an[Upstream5xxResponse] should be thrownBy await(desConnector.deleteAgentRelationship(Vrn("someVrn"), Arn("someArn")))
    }

    "fail when DES is unavailable" in {
      givenDesReturnsServiceUnavailable()
      an[Upstream5xxResponse] should be thrownBy await(desConnector.deleteAgentRelationship(Vrn("someVrn"), Arn("someArn")))
    }
  }

  "DesConnector GetStatusAgentRelationship" should {
    val encodedClientIdMtdItId = UriEncoding.encodePathSegment(mtdItId.value, "UTF-8")
    val encodedClientIdVrn = UriEncoding.encodePathSegment(vrn.value, "UTF-8")

    "return existing active relationships for specified clientId for ItSa service" in {
      getClientActiveAgentRelationshipsItSa(encodedClientIdMtdItId, agentARN.value)

      val result = await(desConnector.getActiveClientItsaRelationships(mtdItId))
      result.get.arn shouldBe agentARN
    }

    "return existing active relationships for specified clientId for Vat service" in {
      getClientActiveAgentRelationshipsVat(encodedClientIdVrn, agentARN.value)

      val result = await(desConnector.getActiveClientVatRelationships(vrn))
      result.get.arn shouldBe agentARN
    }

    "return notFound active relationships for specified clientId for ItSa service" in {
      getNotFoundClientActiveAgentRelationshipsItSa(encodedClientIdMtdItId)

      val result = await(desConnector.getActiveClientItsaRelationships(mtdItId))
      result shouldBe None
    }

    "return notFound active relationships for specified clientId for Vat service" in {
      getNotFoundClientActiveAgentRelationshipsVat(encodedClientIdVrn)

      val result = await(desConnector.getActiveClientVatRelationships(vrn))
      result shouldBe None
    }

    "record metrics for GetStatusAgentRelationship for ItSa service" in {
      getClientActiveAgentRelationshipsItSa(encodedClientIdMtdItId, agentARN.value)

      val result = await(desConnector.getActiveClientItsaRelationships(mtdItId))
      result.get.arn shouldBe agentARN
      timerShouldExistsAndBeenUpdated("ConsumedAPI-DES-GetStatusAgentRelationship-GET")
    }

    "record metrics for GetStatusAgentRelationship for Vat service" in {
      getClientActiveAgentRelationshipsVat(encodedClientIdVrn, agentARN.value)

      val result = await(desConnector.getActiveClientVatRelationships(vrn))
      result.get.arn shouldBe agentARN
      timerShouldExistsAndBeenUpdated("ConsumedAPI-DES-GetStatusAgentRelationship-GET")
    }
  }

  "isActive" should {
    val noEndRelationship = ItsaRelationship(Arn("foo"), None)
    val beforeCurrentDateRelationship = ItsaRelationship(Arn("foo"), Some(LocalDate.parse("1111-11-11")))
    val afterCurrentDateRelationship = ItsaRelationship(Arn("foo"), Some(LocalDate.parse("2222-11-11")))
    "return true when the relationship has no end date" in {
      desConnector.isActive(noEndRelationship) shouldBe true
    }
    "return true when the end date is after the current date" in {
      desConnector.isActive(afterCurrentDateRelationship) shouldBe true
    }
    "return false when the end date is before the current date" in {
      desConnector.isActive(beforeCurrentDateRelationship) shouldBe false
    }
  }
}