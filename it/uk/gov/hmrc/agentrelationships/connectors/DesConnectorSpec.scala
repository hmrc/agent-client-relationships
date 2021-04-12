package uk.gov.hmrc.agentrelationships.connectors

import com.github.tomakehurst.wiremock.client.WireMock.{equalToJson, postRequestedFor, urlPathEqualTo, verify}
import com.kenshoo.play.metrics.Metrics
import org.joda.time.{DateTimeZone, LocalDate}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.utils.UriEncoding
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.DesConnector
import uk.gov.hmrc.agentclientrelationships.model.{ActiveRelationship, InactiveRelationship, SuspensionDetails, AgentRecord}
import uk.gov.hmrc.agentclientrelationships.services.AgentCacheProvider
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.agentrelationships.stubs.{DataStreamStub, DesStubs, DesStubsGet}
import uk.gov.hmrc.agentrelationships.support.{MetricTestSupport, WireMockSupport}
import uk.gov.hmrc.domain.{Nino, SaAgentReference, TaxIdentifier}
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}
import uk.gov.hmrc.http.HttpClient
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

class DesConnectorSpec
    extends UnitSpec with GuiceOneServerPerSuite with WireMockSupport with DesStubs with DesStubsGet with DataStreamStub
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
        "microservice.services.des.port"                   -> wireMockPort,
        "microservice.services.auth.port"                  -> wireMockPort,
        "microservice.services.des.environment"                  -> "stub",
        "microservice.services.des.authorization-token" -> "token",
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
        "des-if.enabled"                                   -> false
      )

  private implicit val hc: HeaderCarrier = HeaderCarrier()
  private implicit val ec: ExecutionContextExecutor = ExecutionContext.global

  val desConnector =
    new DesConnector(httpClient, metrics, agentCacheProvider)

  val mtdItId = MtdItId("ABCDEF123456789")
  val vrn = Vrn("101747641")
  val agentARN = Arn("ABCDE123456")
  val utr = Utr("1704066305")
  val urn = Urn("AAAAA6426901064")
  val cgt = CgtRef("XMCGTP837878749")

  val otherTaxIdentifier: TaxIdentifier => TaxIdentifier = {
    case MtdItId(_) => MtdItId("ABCDE1234567890")
    case Vrn(_)     => Vrn("101747641")
    case Utr(_)     => Utr("2134514321")
    case Urn(_)     => Urn("AAAAA6426901067")
  }

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

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/registration/relationship"))
          .withRequestBody(equalToJson(s"""{ "regime": "ITSA"}""", true, true)))
    }

    "request body contains regime as VATC and idType as VRN when client Id is a Vrn" in {
      givenAgentCanBeAllocatedInDes(Vrn("someVrn"), Arn("someArn"))
      givenAuditConnector()

      await(desConnector.createAgentRelationship(Vrn("someVrn"), Arn("someArn")))

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
      givenAgentCanBeAllocatedInDes(Utr("someUtr"), Arn("someArn"))
      givenAuditConnector()

      await(desConnector.createAgentRelationship(Utr("someUtr"), Arn("someArn")))

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

    "throw an IllegalArgumentException when the tax identifier is not supported" in {
      an[IllegalArgumentException] should be thrownBy await(desConnector.createAgentRelationship(Eori("foo"), Arn("bar")))
    }

    "fail when DES is throwing errors" in {
      givenDesReturnsServerError()
      an[UpstreamErrorResponse] should be thrownBy await(desConnector.createAgentRelationship(Vrn("someVrn"), Arn("someArn")))
    }

    "fail when DES is unavailable" in {
      givenDesReturnsServiceUnavailable()
      an[UpstreamErrorResponse] should be thrownBy await(desConnector.createAgentRelationship(Vrn("someVrn"), Arn("someArn")))
    }
  }

  "DesConnector GetAgentRecord" should {

    "get agentRecord detail should retrieve agent record from DES" in {
      getAgentRecordForClient(agentARN)

      await(desConnector.getAgentRecord(agentARN)) should be (AgentRecord(Some(SuspensionDetails(suspensionStatus = false, Some(Set.empty)))))
    }

    "throw an IllegalArgumentException when the tax identifier is not supported" in {
      an[Exception] should be thrownBy await(desConnector.getAgentRecord(Eori("foo")))
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

    "delete relationship between agent and client and return 200 for Trust service" in {
      givenAgentCanBeDeallocatedInDes(Utr("foo"), Arn("bar"))
      givenAuditConnector()
      await(desConnector.deleteAgentRelationship(Utr("foo"), Arn("bar"))).processingDate should not be null
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
      an[IllegalArgumentException] should be thrownBy await(desConnector.deleteAgentRelationship(Eori("foo"), Arn("bar")))
    }

    "fail when DES is throwing errors" in {
      givenDesReturnsServerError()
      an[UpstreamErrorResponse] should be thrownBy await(desConnector.deleteAgentRelationship(Vrn("someVrn"), Arn("someArn")))
    }

    "fail when DES is unavailable" in {
      givenDesReturnsServiceUnavailable()
      an[UpstreamErrorResponse] should be thrownBy await(desConnector.deleteAgentRelationship(Vrn("someVrn"), Arn("someArn")))
    }
  }

  "DesConnector GetActiveClientItsaRelationships and GetActiveClientVatRelationships" should {

    "return existing active relationships for specified clientId for ItSa service" in {
      getActiveRelationshipsViaClient(mtdItId, agentARN)

      val result = await(desConnector.getActiveClientRelationships(mtdItId))
      result.get.arn shouldBe agentARN
    }

    "return existing active relationships for specified clientId for Vat service" in {
      getActiveRelationshipsViaClient(vrn, agentARN)

      val result = await(desConnector.getActiveClientRelationships(vrn))
      result.get.arn shouldBe agentARN
    }

    "return None if DES returns 404 for ItSa service" in {
      getActiveRelationshipFailsWith(mtdItId, status = 404)

      val result = await(desConnector.getActiveClientRelationships(mtdItId))
      result shouldBe None
    }

    "return None if DES returns 404 for Vat service" in {
      getActiveRelationshipFailsWith(vrn, status = 404)

      val result = await(desConnector.getActiveClientRelationships(vrn))
      result shouldBe None
    }

    "return None if DES returns 400 for ItSa service" in {
      getActiveRelationshipFailsWith(mtdItId, status = 400)

      val result = await(desConnector.getActiveClientRelationships(mtdItId))
      result shouldBe None
    }

    "return None if DES returns 400 for Vat service" in {
      getActiveRelationshipFailsWith(vrn, status = 400)

      val result = await(desConnector.getActiveClientRelationships(vrn))
      result shouldBe None
    }

    "return None if DES returns 403 AGENT_SUSPENDED" in {
      getActiveRelationshipFailsWithSuspended(vrn)

      val result = await(desConnector.getActiveClientRelationships(vrn))
      result shouldBe None
    }

    "record metrics for GetStatusAgentRelationship for ItSa service" in {
      getActiveRelationshipsViaClient(mtdItId, agentARN)

      val result = await(desConnector.getActiveClientRelationships(mtdItId))
      result.get.arn shouldBe agentARN
      timerShouldExistsAndBeenUpdated("ConsumedAPI-DES-GetStatusAgentRelationship-GET")
    }

    "record metrics for GetStatusAgentRelationship for Vat service" in {
      getActiveRelationshipsViaClient(vrn, agentARN)

      val result = await(desConnector.getActiveClientRelationships(vrn))
      result.get.arn shouldBe agentARN
      timerShouldExistsAndBeenUpdated("ConsumedAPI-DES-GetStatusAgentRelationship-GET")
    }
  }

  "GetInactiveAgentRelationships" should {
    val encodedArn = UriEncoding.encodePathSegment(agentARN.value, "UTF-8")

    "return existing inactive relationships for specified clientId for ItSa service" in {
      getInactiveRelationshipsViaAgent(agentARN, otherTaxIdentifier(mtdItId), mtdItId)

      val result = await(desConnector.getInactiveRelationships(agentARN))
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

      val result = await(desConnector.getInactiveRelationships(agentARN))
      result(0).arn shouldBe agentARN
      result(0).dateFrom shouldBe Some(LocalDate.parse("2015-09-10"))
      result(0).dateTo shouldBe Some(LocalDate.parse("2015-09-21"))
      result(0).clientId shouldBe otherTaxIdentifier(vrn).value
      result(1).arn shouldBe agentARN
      result(1).dateFrom shouldBe Some(LocalDate.parse("2015-09-10"))
      result(1).dateTo shouldBe Some(LocalDate.now())
      result(1).clientId shouldBe vrn.value

    }

    "return empty sequence if DES returns 404 for ItSa service" in {
      getFailAgentInactiveRelationships(encodedArn, status = 404)

      val result = await(desConnector.getInactiveRelationships(agentARN))
      result shouldBe Seq.empty
    }

    "return empty sequence if DES returns 404 for Vat service" in {
      getFailAgentInactiveRelationships(encodedArn, status = 404)

      val result = await(desConnector.getInactiveRelationships(agentARN))
      result shouldBe Seq.empty
    }

    "return empty sequence if DES returns 400 for ItSa service" in {
      getFailAgentInactiveRelationships(encodedArn, status = 400)

      val result = await(desConnector.getInactiveRelationships(agentARN))
      result shouldBe Seq.empty
    }

    "return empty sequence if DES returns 400 for Vat service" in {
      getFailWithSuspendedAgentInactiveRelationships(encodedArn)

      val result = await(desConnector.getInactiveRelationships(agentARN))
      result shouldBe Seq.empty
    }

    "return None if DES returns 403 AGENT_SUSPENDED" in {
      getActiveRelationshipFailsWithSuspended(vrn)

      val result = await(desConnector.getActiveClientRelationships(vrn))
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
      desConnector.isActive(noEndRelationship) shouldBe true
    }
    "return true when the end date is after the current date" in {
      desConnector.isActive(afterCurrentDateRelationship) shouldBe true
    }
    "return false when the end date is before the current date" in {
      desConnector.isActive(beforeCurrentDateRelationship) shouldBe false
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
      desConnector.isNotActive(noEndRelationship) shouldBe false
    }
    "return true when the end date is before the current date" in {
      desConnector.isNotActive(endsBeforeCurrentDate) shouldBe true
    }
    "return true when the end date is equal to the current date" in {
      desConnector.isNotActive(endsAtCurrentDateRelationship) shouldBe true
    }
  }

  "getInactiveClientRelationships" should {

    "return existing inactive relationships for specified clientId for ItSa service" in {

        getInactiveRelationshipsForClient(mtdItId)

      val result = await(desConnector.getInactiveClientRelationships(mtdItId))

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

      val result = await(desConnector.getInactiveClientRelationships(vrn))

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

      val result = await(desConnector.getInactiveClientRelationships(utr))

      result.head shouldBe InactiveRelationship(
        arn = agentARN,
        dateTo = Some(LocalDate.parse("2018-09-09")),
        dateFrom = Some(LocalDate.parse("2015-09-10")),
        clientId = utr.value,
        service = "HMRC-TERS-ORG",
        clientType = "business"
      )
    }

    "return existing inactive relationships for specified clientId for CGT-PD service" in {

      getInactiveRelationshipsForClient(cgt)

      val result = await(desConnector.getInactiveClientRelationships(cgt))

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
      val result = await(desConnector.getInactiveClientRelationships(mtdItId))
      result.isEmpty shouldBe true
    }

    "return empty Seq if the identifier if DES responds with a Bad Request" in {

      getFailInactiveRelationshipsForClient(mtdItId, 400)
      val result = await(desConnector.getInactiveClientRelationships(mtdItId))
      result.isEmpty shouldBe true
    }

    "return empty Seq if the identifier if DES responds with Not Found" in {

      getFailInactiveRelationshipsForClient(mtdItId, 404)
      val result = await(desConnector.getInactiveClientRelationships(mtdItId))
      result.isEmpty shouldBe true
    }

    "fail when DES is unavailable" in {
      givenDesReturnsServiceUnavailable()
      an[UpstreamErrorResponse] should be thrownBy await(desConnector.getInactiveClientRelationships(mtdItId))
    }
  }
}
