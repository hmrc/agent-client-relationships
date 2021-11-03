package uk.gov.hmrc.agentclientrelationships.connectors

import com.github.tomakehurst.wiremock.client.WireMock.{equalToJson, postRequestedFor, urlPathEqualTo, verify}
import com.kenshoo.play.metrics.Metrics
import org.joda.time.{DateTimeZone, LocalDate}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import play.utils.UriEncoding
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.{ActiveRelationship, InactiveRelationship}
import uk.gov.hmrc.agentclientrelationships.services.AgentCacheProvider
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.agentclientrelationships.stubs.{DataStreamStub, IFStubs}
import uk.gov.hmrc.agentclientrelationships.support.{MetricTestSupport, WireMockSupport}
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, UpstreamErrorResponse}
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

class IFConnectorISpec
  extends UnitSpec with GuiceOneServerPerSuite with WireMockSupport with IFStubs with DataStreamStub
    with MetricTestSupport {


  override implicit lazy val app: Application = appBuilder
    .build()

  val httpClient = app.injector.instanceOf[HttpClient]
  val metrics = app.injector.instanceOf[Metrics]
  val agentCacheProvider = app.injector.instanceOf[AgentCacheProvider]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
        "microservice.services.tax-enrolments.port"        -> wireMockPort,
        "microservice.services.users-groups-search.port"   -> wireMockPort,
        "microservice.services.if.port"                    -> wireMockPort,
        "microservice.services.auth.port"                  -> wireMockPort,
        "microservice.services.if.environment"            -> "stub",
        "microservice.services.if.authorization-token"    -> "token",
        "microservice.services.agent-mapping.port"         -> wireMockPort,
        "auditing.consumer.baseUri.host"                   -> wireMockHost,
        "auditing.consumer.baseUri.port"                   -> wireMockPort,
        "features.copy-relationship.mtd-it"                -> true,
        "features.copy-relationship.mtd-vat"               -> true,
        "features.recovery-enable"                         -> false,
        "agent.cache.size"                                 -> 1,
        "agent.cache.expires"                              -> "1 millis",
        "agent.cache.enabled"                              -> false,
        "agent.trackPage.cache.size"                       -> 1,
        "agent.trackPage.cache.expires"                    -> "1 millis",
        "agent.trackPage.cache.enabled"                    -> false,
        "des-if.enabled"                                   -> true
      )

  private implicit val hc: HeaderCarrier = HeaderCarrier()
  private implicit val ec: ExecutionContextExecutor = ExecutionContext.global

  val ifConnector =
    new IFConnector(httpClient, metrics, agentCacheProvider)

  val mtdItId = MtdItId("ABCDEF123456789")
  val vrn = Vrn("101747641")
  val agentARN = Arn("ABCDE123456")
  val utr = Utr("1704066305")
  val urn = Urn("XXTRUST12345678")
  val cgt = CgtRef("XMCGTP837878749")
  val pptRef = PptRef("XAPPT0004567890")

  val otherTaxIdentifier: TaxIdentifier => TaxIdentifier = {
    case MtdItId(_) => MtdItId("ABCDE1234567890")
    case Vrn(_)     => Vrn("101747641")
    case Utr(_)     => Utr("2134514321")
    case Urn(_)     => Urn("XXTRUST12345678")
    case PptRef(_)  => PptRef("XAPPT0004567890")
  }

  "IFConnector CreateAgentRelationship" should {
    "create relationship between agent and client and return 200" in {
      givenAgentCanBeAllocatedInIF(MtdItId("foo"), Arn("bar"))
      givenAuditConnector()
      await(ifConnector.createAgentRelationship(MtdItId("foo"), Arn("bar"))).processingDate should not be null
    }

    "not create relationship between agent and client and return 404" in {
      givenAgentCanNotBeAllocatedInIF(status = 404)
      givenAuditConnector()
      an[Exception] should be thrownBy await(ifConnector.createAgentRelationship(MtdItId("foo"), Arn("bar")))
    }

    "request body contains regime as ITSA when client Id is an MtdItId" in {
      givenAgentCanBeAllocatedInIF(MtdItId("foo"), Arn("someArn"))
      givenAuditConnector()

      await(ifConnector.createAgentRelationship(MtdItId("foo"), Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/registration/relationship"))
          .withRequestBody(equalToJson(s"""{ "regime": "ITSA"}""", true, true)))
    }

    "request body contains regime as VATC and idType as VRN when client Id is a Vrn" in {
      givenAgentCanBeAllocatedInIF(Vrn("someVrn"), Arn("someArn"))
      givenAuditConnector()

      await(ifConnector.createAgentRelationship(Vrn("someVrn"), Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/registration/relationship"))
          .withRequestBody(equalToJson(
            s"""{
               |"regime": "VATC",
               |"idType" : "VRN",
               |"relationshipType" : "ZA01",
               |"authProfile" : "ALL00001"
               |}""".stripMargin,
            true,
            true
          ))
      )
    }

    "request body contains regime as TRS and idType as UTR when client Id is a UTR" in {
      givenAgentCanBeAllocatedInIF(Utr("someUtr"), Arn("someArn"))
      givenAuditConnector()

      await(ifConnector.createAgentRelationship(Utr("someUtr"), Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/registration/relationship"))
          .withRequestBody(
            equalToJson(
              s"""{
                 |"regime": "TRS",
                 |"idType" : "UTR"
                 |}""".stripMargin,
              true,
              true
            ))
      )
    }

    "request body contains regime as TRS and idType as URN when client Id is a URN" in {
      givenAgentCanBeAllocatedInIF(Urn("someUrn"), Arn("someArn"))
      givenAuditConnector()

      await(ifConnector.createAgentRelationship(Urn("someUrn"), Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/registration/relationship"))
          .withRequestBody(
            equalToJson(
              s"""{
                 |"regime": "TRS",
                 |"idType" : "URN"
                 |}""".stripMargin,
              true,
              true
            ))
      )
    }

    "request body contains regime as PPT and idType as ZPPT when client Id is a PptRef" in {
      givenAgentCanBeAllocatedInIF(PptRef("somePpt"), Arn("someArn"))
      givenAuditConnector()

      await(ifConnector.createAgentRelationship(PptRef("somePpt"), Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/registration/relationship"))
          .withRequestBody(
            equalToJson(
              s"""{
                 |"regime": "PPT",
                 |"refNumber" : "somePpt"
                 |}""".stripMargin,
              true,
              true
            ))
      )
    }

    "throw an IllegalArgumentException when the tax identifier is not supported" in {
      an[IllegalArgumentException] should be thrownBy await(ifConnector.createAgentRelationship(Eori("foo"), Arn("bar")))
    }

    "fail when IF is throwing errors" in {
      givenIFReturnsServerError()
      an[UpstreamErrorResponse] should be thrownBy await(ifConnector.createAgentRelationship(Vrn("someVrn"), Arn("someArn")))
    }

    "fail when IT is unavailable" in {
      givenIFReturnsServiceUnavailable()
      an[UpstreamErrorResponse] should be thrownBy await(ifConnector.createAgentRelationship(Vrn("someVrn"), Arn("someArn")))
    }
  }

  "IFConnector DeleteAgentRelationship" should {
    "delete relationship between agent and client and return 200 for ItSa service" in {
      givenAgentCanBeDeallocatedInIF(MtdItId("foo"), Arn("bar"))
      givenAuditConnector()
      await(ifConnector.deleteAgentRelationship(MtdItId("foo"), Arn("bar"))).processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for Vat service" in {
      givenAgentCanBeDeallocatedInIF(Vrn("foo"), Arn("bar"))
      givenAuditConnector()
      await(ifConnector.deleteAgentRelationship(Vrn("foo"), Arn("bar"))).processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for Trust service" in {
      givenAgentCanBeDeallocatedInIF(Utr("foo"), Arn("bar"))
      givenAuditConnector()
      await(ifConnector.deleteAgentRelationship(Utr("foo"), Arn("bar"))).processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for Trust service with URN" in {
      givenAgentCanBeDeallocatedInIF(Urn("foo"), Arn("bar"))
      givenAuditConnector()
      await(ifConnector.deleteAgentRelationship(Urn("foo"), Arn("bar"))).processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for PPT service with PptRef" in {
      givenAgentCanBeDeallocatedInIF(PptRef("foo"), Arn("bar"))
      givenAuditConnector()
      await(ifConnector.deleteAgentRelationship(PptRef("foo"), Arn("bar"))).processingDate should not be null
    }

    "not delete relationship between agent and client and return 404 for ItSa service" in {
      givenAgentCanNotBeDeallocatedInIF(status = 404)
      givenAuditConnector()
      an[Exception] should be thrownBy await(ifConnector.deleteAgentRelationship(MtdItId("foo"), Arn("bar")))
    }

    "not delete relationship between agent and client and return 404 for Vat service" in {
      givenAgentCanNotBeDeallocatedInIF(status = 404)
      givenAuditConnector()
      an[Exception] should be thrownBy await(ifConnector.deleteAgentRelationship(Vrn("foo"), Arn("bar")))
    }

    "throw an IllegalArgumentException when the tax identifier is not supported" in {
      an[IllegalArgumentException] should be thrownBy await(ifConnector.deleteAgentRelationship(Eori("foo"), Arn("bar")))
    }

    "fail when IF is throwing errors" in {
      givenIFReturnsServerError()
      an[UpstreamErrorResponse] should be thrownBy await(ifConnector.deleteAgentRelationship(Vrn("someVrn"), Arn("someArn")))
    }

    "fail when IF is unavailable" in {
      givenIFReturnsServiceUnavailable()
      an[UpstreamErrorResponse] should be thrownBy await(ifConnector.deleteAgentRelationship(Vrn("someVrn"), Arn("someArn")))
    }
  }

  "IFConnector GetActiveClientItsaRelationships and GetActiveClientVatRelationships" should {

    "return existing active relationships for specified clientId for ItSa service" in {
      getActiveRelationshipsViaClient(mtdItId, agentARN)

      val result = await(ifConnector.getActiveClientRelationships(mtdItId))
      result.get.arn shouldBe agentARN
    }

    "return existing active relationships for specified clientId for Vat service" in {
      getActiveRelationshipsViaClient(vrn, agentARN)

      val result = await(ifConnector.getActiveClientRelationships(vrn))
      result.get.arn shouldBe agentARN
    }

    "return None if IF returns 404 for ItSa service" in {
      getActiveRelationshipFailsWith(mtdItId, status = 404)

      val result = await(ifConnector.getActiveClientRelationships(mtdItId))
      result shouldBe None
    }

    "return None if IF returns 404 for Vat service" in {
      getActiveRelationshipFailsWith(vrn, status = 404)

      val result = await(ifConnector.getActiveClientRelationships(vrn))
      result shouldBe None
    }

    "return None if IF returns 400 for ItSa service" in {
      getActiveRelationshipFailsWith(mtdItId, status = 400)

      val result = await(ifConnector.getActiveClientRelationships(mtdItId))
      result shouldBe None
    }

    "return None if IF returns 400 for Vat service" in {
      getActiveRelationshipFailsWith(vrn, status = 400)

      val result = await(ifConnector.getActiveClientRelationships(vrn))
      result shouldBe None
    }

    "return None if IF returns 403 AGENT_SUSPENDED" in {
      getActiveRelationshipFailsWithSuspended(vrn)

      val result = await(ifConnector.getActiveClientRelationships(vrn))
      result shouldBe None
    }

    "record metrics for GetStatusAgentRelationship for ItSa service" in {
      givenCleanMetricRegistry()
      givenAuditConnector()
      getActiveRelationshipsViaClient(mtdItId, agentARN)
      val result = await(ifConnector.getActiveClientRelationships(mtdItId))
      result.get.arn shouldBe agentARN
      timerShouldExistsAndBeenUpdated("ConsumedAPI-IF-GetActiveClientRelationships-GET")
    }

    "record metrics for GetStatusAgentRelationship for Vat service" in {
      givenCleanMetricRegistry()
      givenAuditConnector()
      getActiveRelationshipsViaClient(vrn, agentARN)

      val result = await(ifConnector.getActiveClientRelationships(vrn))
      result.get.arn shouldBe agentARN
      timerShouldExistsAndBeenUpdated("ConsumedAPI-IF-GetActiveClientRelationships-GET")
    }
  }

  "GetInactiveAgentRelationships" should {
    val encodedArn = UriEncoding.encodePathSegment(agentARN.value, "UTF-8")

    "return existing inactive relationships for specified clientId for ItSa service" in {
      getInactiveRelationshipsViaAgent(agentARN, otherTaxIdentifier(mtdItId), mtdItId)

      val result = await(ifConnector.getInactiveRelationships(agentARN))
      result(0).arn shouldBe agentARN
      result(0).dateFrom shouldBe Some(LocalDate.parse("2015-09-10"))
      result(0).dateTo shouldBe Some(LocalDate.parse("2015-09-21"))
      result(0).clientId shouldBe otherTaxIdentifier(mtdItId).value
      result(1).arn shouldBe agentARN
      result(1).dateFrom shouldBe Some(LocalDate.parse("2015-09-10"))
      result(1).dateTo shouldBe Some(LocalDate.now())
      result(1).clientId shouldBe mtdItId.value
    }

    "return existing inactive relationships for specified clientId for Vat service" in {
      getInactiveRelationshipsViaAgent(agentARN, otherTaxIdentifier(vrn), vrn)

      val result = await(ifConnector.getInactiveRelationships(agentARN))
      result(0).arn shouldBe agentARN
      result(0).dateFrom shouldBe Some(LocalDate.parse("2015-09-10"))
      result(0).dateTo shouldBe Some(LocalDate.parse("2015-09-21"))
      result(0).clientId shouldBe otherTaxIdentifier(vrn).value
      result(1).arn shouldBe agentARN
      result(1).dateFrom shouldBe Some(LocalDate.parse("2015-09-10"))
      result(1).dateTo shouldBe Some(LocalDate.now())
      result(1).clientId shouldBe vrn.value

    }

    "return empty sequence if IF returns 404 for ItSa service" in {
      getFailAgentInactiveRelationships(encodedArn, status = 404)

      val result = await(ifConnector.getInactiveRelationships(agentARN))
      result shouldBe Seq.empty
    }

    "return empty sequence if IF returns 404 for Vat service" in {
      getFailAgentInactiveRelationships(encodedArn, status = 404)

      val result = await(ifConnector.getInactiveRelationships(agentARN))
      result shouldBe Seq.empty
    }

    "return empty sequence if IF returns 400 for ItSa service" in {
      getFailAgentInactiveRelationships(encodedArn, status = 400)

      val result = await(ifConnector.getInactiveRelationships(agentARN))
      result shouldBe Seq.empty
    }

    "return empty sequence if IF returns 400 for Vat service" in {
      getFailWithSuspendedAgentInactiveRelationships(encodedArn)

      val result = await(ifConnector.getInactiveRelationships(agentARN))
      result shouldBe Seq.empty
    }

    "return None if IF returns 403 AGENT_SUSPENDED" in {
      getActiveRelationshipFailsWithSuspended(vrn)

      val result = await(ifConnector.getActiveClientRelationships(vrn))
      result shouldBe None
    }
  }

  "isActive" should {
    val noEndRelationship = ActiveRelationship(Arn("foo"), None, Some(LocalDate.parse("1111-11-11")))
    val afterCurrentDateRelationship =
      ActiveRelationship(Arn("foo"), Some(LocalDate.parse("2222-11-11")), Some(LocalDate.parse("1111-11-11")))
    val beforeCurrentDateRelationship =
      ActiveRelationship(Arn("foo"), Some(LocalDate.parse("1111-11-11")), Some(LocalDate.parse("1111-11-11")))
    "return true when the relationship has no end date" in {
      ifConnector.isActive(noEndRelationship) shouldBe true
    }
    "return true when the end date is after the current date" in {
      ifConnector.isActive(afterCurrentDateRelationship) shouldBe true
    }
    "return false when the end date is before the current date" in {
      ifConnector.isActive(beforeCurrentDateRelationship) shouldBe false
    }
  }

  "isInactive" should {
    val noEndRelationship =
      InactiveRelationship(Arn("foo"), None, Some(LocalDate.parse("1111-11-11")), "123456789", "personal", "HMRC-MTD-VAT")
    val endsBeforeCurrentDate =
      InactiveRelationship(
        Arn("foo"),
        Some(LocalDate.parse("1111-11-11")),
        Some(LocalDate.parse("1111-11-11")),
        "123456789",
        "personal",
        "HMRC-MTD-VAT")
    val endsAtCurrentDateRelationship =
      InactiveRelationship(
        Arn("foo"),
        Some(LocalDate.now(DateTimeZone.UTC)),
        Some(LocalDate.parse("1111-11-11")),
        "123456789",
        "personal",
        "HMRC-MTD-VAT")

    "return false when the relationship is active" in {
      ifConnector.isNotActive(noEndRelationship) shouldBe false
    }
    "return true when the end date is before the current date" in {
      ifConnector.isNotActive(endsBeforeCurrentDate) shouldBe true
    }
    "return true when the end date is equal to the current date" in {
      ifConnector.isNotActive(endsAtCurrentDateRelationship) shouldBe true
    }
  }

  "getInactiveClientRelationships" should {

    "return existing inactive relationships for specified clientId for ItSa service" in {

      getInactiveRelationshipsForClient(mtdItId)

      val result = await(ifConnector.getInactiveClientRelationships(mtdItId))

      result.head shouldBe InactiveRelationship(
        arn = agentARN,
        dateTo = Some(LocalDate.parse("2018-09-09")),
        dateFrom = Some(LocalDate.parse("2015-09-10")),
        clientId = mtdItId.value,
        service = "HMRC-MTD-IT",
        clientType = "personal"
      )
    }

    "return existing inactive relationships for specified clientId for VAT service" in {

      getInactiveRelationshipsForClient(vrn)

      val result = await(ifConnector.getInactiveClientRelationships(vrn))

      result.head shouldBe InactiveRelationship(
        arn = agentARN,
        dateTo = Some(LocalDate.parse("2018-09-09")),
        dateFrom = Some(LocalDate.parse("2015-09-10")),
        clientId = vrn.value,
        service = "HMRC-MTD-VAT",
        clientType = "business"
      )
    }

    "return existing inactive relationships for specified clientId for Trust service with UTR" in {

      getInactiveRelationshipsForClient(utr)

      val result = await(ifConnector.getInactiveClientRelationships(utr))

      result.head shouldBe InactiveRelationship(
        arn = agentARN,
        dateTo = Some(LocalDate.parse("2018-09-09")),
        dateFrom = Some(LocalDate.parse("2015-09-10")),
        clientId = utr.value,
        service = "HMRC-TERS-ORG",
        clientType = "business"
      )
    }

    "return existing inactive relationships for specified clientId for Trust service with URN" in {

      getInactiveRelationshipsForClient(urn)

      val result = await(ifConnector.getInactiveClientRelationships(urn))

      result.head shouldBe InactiveRelationship(
        arn = agentARN,
        dateTo = Some(LocalDate.parse("2018-09-09")),
        dateFrom = Some(LocalDate.parse("2015-09-10")),
        clientId = urn.value,
        service = "HMRC-TERSNT-ORG",
        clientType = "business"
      )
    }

    "return existing inactive relationships for specified clientId for CGT-PD service" in {

      getInactiveRelationshipsForClient(cgt)

      val result = await(ifConnector.getInactiveClientRelationships(cgt))

      result.head shouldBe InactiveRelationship(
        arn = agentARN,
        dateTo = Some(LocalDate.parse("2018-09-09")),
        dateFrom = Some(LocalDate.parse("2015-09-10")),
        clientId = cgt.value,
        service = "HMRC-CGT-PD",
        clientType = "business"
      )
    }

    "return empty Seq if the identifier is valid but there are no inactive relationships" in {

      getNoInactiveRelationshipsForClient(mtdItId)
      val result = await(ifConnector.getInactiveClientRelationships(mtdItId))
      result.isEmpty shouldBe true
    }

    "return empty Seq if the identifier if IF responds with a Bad Request" in {

      getFailInactiveRelationshipsForClient(mtdItId, 400)
      val result = await(ifConnector.getInactiveClientRelationships(mtdItId))
      result.isEmpty shouldBe true
    }

    "return empty Seq if the identifier if IF responds with Not Found" in {

      getFailInactiveRelationshipsForClient(mtdItId, 404)
      val result = await(ifConnector.getInactiveClientRelationships(mtdItId))
      result.isEmpty shouldBe true
    }

    "fail when IF is unavailable" in {
      givenIFReturnsServiceUnavailable()
      an[UpstreamErrorResponse] should be thrownBy await(ifConnector.getInactiveClientRelationships(mtdItId))
    }
  }
}

