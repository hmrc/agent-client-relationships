/*
 * Copyright 2024 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.agentclientrelationships.connectors

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import play.utils.UriEncoding
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.{ActiveRelationship, EnrolmentKey, InactiveRelationship}
import uk.gov.hmrc.agentclientrelationships.services.AgentCacheProvider
import uk.gov.hmrc.agentclientrelationships.stubs.{DataStreamStub, IFAgentClientRelationshipStub, IFStubs}
import uk.gov.hmrc.agentclientrelationships.support.{MetricTestSupport, UnitSpec, WireMockSupport}
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{CapitalGains, MtdIt, MtdItSupp, Pillar2, Ppt, Trust, TrustNT, Vat}
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

class IFConnectorISpec
    extends UnitSpec
    with GuiceOneServerPerSuite
    with WireMockSupport
    with IFStubs
    with IFAgentClientRelationshipStub
    with DataStreamStub
    with MetricTestSupport {

  override implicit lazy val app: Application = appBuilder
    .build()

  val httpClient: HttpClient = app.injector.instanceOf[HttpClient]
  val metrics: Metrics = app.injector.instanceOf[Metrics]
  val agentCacheProvider: AgentCacheProvider = app.injector.instanceOf[AgentCacheProvider]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.enrolment-store-proxy.port"     -> wireMockPort,
        "microservice.services.tax-enrolments.port"            -> wireMockPort,
        "microservice.services.users-groups-search.port"       -> wireMockPort,
        "microservice.services.if.port"                        -> wireMockPort,
        "microservice.services.auth.port"                      -> wireMockPort,
        "microservice.services.if.environment"                 -> "stub",
        "microservice.services.if.authorization-token"         -> "token",
        "microservice.services.if.authorization-api1171-token" -> "token",
        "microservice.services.agent-mapping.port"             -> wireMockPort,
        "auditing.consumer.baseUri.host"                       -> wireMockHost,
        "auditing.consumer.baseUri.port"                       -> wireMockPort,
        "features.copy-relationship.mtd-it"                    -> true,
        "features.copy-relationship.mtd-vat"                   -> true,
        "features.recovery-enable"                             -> false,
        "agent.cache.expires"                                  -> "1 millis",
        "agent.cache.enabled"                                  -> false,
        "agent.trackPage.cache.expires"                        -> "1 millis",
        "agent.trackPage.cache.enabled"                        -> false
      )

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val hc: HeaderCarrier = HeaderCarrier()
  val ifConnector =
    new IFConnector(httpClient, ec)(metrics, appConfig)

  val relationshipConnector =
    new IFRelationshipConnector(httpClient, agentCacheProvider, ec)(metrics, appConfig)

  val mtdItId: MtdItId = MtdItId("ABCDEF123456789")
  val vrn: Vrn = Vrn("101747641")
  val agentARN: Arn = Arn("ABCDE123456")
  val utr: Utr = Utr("1704066305")
  val urn: Urn = Urn("XXTRUST12345678")
  val cgt: CgtRef = CgtRef("XMCGTP837878749")
  val pptRef: PptRef = PptRef("XAPPT0004567890")
  val plrId: PlrId = PlrId("XMPLR0012345678")
  val mtdItEnrolmentKey: EnrolmentKey = EnrolmentKey(Service.MtdIt, mtdItId)
  val mtdItSuppEnrolmentKey: EnrolmentKey = EnrolmentKey(Service.MtdItSupp, mtdItId)
  val vatEnrolmentKey: EnrolmentKey = EnrolmentKey(Service.Vat, vrn)
  val trustEnrolmentKey: EnrolmentKey = EnrolmentKey(Service.HMRCTERSORG, utr)
  val trustNTEnrolmentKey: EnrolmentKey = EnrolmentKey(Service.HMRCTERSNTORG, urn)
  val pptEnrolmentKey: EnrolmentKey = EnrolmentKey(Service.HMRCPPTORG, pptRef)
  val plrEnrolmentKey: EnrolmentKey = EnrolmentKey(Service.HMRCPILLAR2ORG, plrId)

  val otherTaxIdentifier: TaxIdentifier => TaxIdentifier = {
    case MtdItId(_) => MtdItId("ABCDE1234567890")
    case Vrn(_)     => Vrn("101747641")
    case Utr(_)     => Utr("2134514321")
    case Urn(_)     => Urn("XXTRUST12345678")
    case PptRef(_)  => PptRef("XAPPT0004567890")
    case PlrId(_)   => PlrId("XMPLR0012345678")
    case x          => x
  }

  "IfConnector GetBusinessDetails" should {

    val mtdItId = MtdItId("foo")
    val nino = Nino("AB123456C")

    "return some nino when agent's mtdId identifier is known to ETMP" in {
      givenNinoIsKnownFor(mtdItId, nino)
      givenAuditConnector()
      await(ifConnector.getNinoFor(mtdItId)) shouldBe Some(nino)
    }

    "return nothing when agent's mtdId identifier is unknown to ETMP" in {
      givenNinoIsUnknownFor(mtdItId)
      givenAuditConnector()
      await(ifConnector.getNinoFor(mtdItId)) shouldBe None
    }

    "return nothing when agent's mtdId identifier is invalid" in {
      givenmtdIdIsInvalid(mtdItId)
      givenAuditConnector()
      await(ifConnector.getNinoFor(mtdItId)) shouldBe None
    }

    "return nothing when IF is unavailable" in {
      givenReturnsServiceUnavailable()
      givenAuditConnector()
      await(ifConnector.getNinoFor(mtdItId)) shouldBe None
    }

    "return nothing when IF is throwing errors" in {
      givenReturnsServerError()
      givenAuditConnector()
      await(ifConnector.getNinoFor(mtdItId)) shouldBe None
    }

    "record metrics for GetRegistrationDetailsBymtdId" in {
      givenNinoIsKnownFor(mtdItId, Nino("AB123456C"))
      givenCleanMetricRegistry()
      givenAuditConnector()
      await(ifConnector.getNinoFor(mtdItId))
      timerShouldExistsAndBeenUpdated("ConsumedAPI-IF-GetBusinessDetailsByMtdId-GET")
    }

    "return MtdItId when agent's nino is known to ETMP" in {
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenAuditConnector()
      await(ifConnector.getMtdIdFor(nino)) shouldBe Some(mtdItId)
    }

    "return nothing when agent's nino identifier is unknown to ETMP" in {
      givenMtdItIdIsUnKnownFor(nino)
      givenAuditConnector()
      await(ifConnector.getMtdIdFor(nino)) shouldBe None
    }
  }

  "IFConnector CreateAgentRelationship" should {
    "create relationship between agent and client and return 200" in {
      givenAgentCanBeAllocated(mtdItId, Arn("bar"))
      givenAuditConnector()
      await(
        relationshipConnector.createAgentRelationship(mtdItEnrolmentKey, Arn("bar"))
      ).get.processingDate should not be null
    }

    "not create relationship between agent and client and return nothing" in {
      givenAgentCanNotBeAllocated(status = 404)
      givenAuditConnector()
      await(relationshipConnector.createAgentRelationship(mtdItEnrolmentKey, Arn("bar"))) shouldBe None
    }

    "request body contains regime as ITSA when client Id is an MtdItId" in {
      givenAgentCanBeAllocated(mtdItId, Arn("someArn"))
      givenAuditConnector()

      await(relationshipConnector.createAgentRelationship(mtdItEnrolmentKey, Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/registration/relationship"))
          .withHeader("Authorization", containing("Bearer token"))
          .withHeader("Environment", containing("stub"))
          .withRequestBody(
            equalToJson(
              s"""
                 |{
                 |"regime": "ITSA",
                 |"authProfile": "ALL00001",
                 |"relationshipType": "ZA01"
                 |}""".stripMargin,
              true,
              true
            )
          )
      )
    }

    "request body contains regime as ITSA for Supporting Agent when client Id is an MtdItId" in {
      givenAgentCanBeAllocated(mtdItId, Arn("someArn"))
      givenAuditConnector()

      await(relationshipConnector.createAgentRelationship(mtdItSuppEnrolmentKey, Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/registration/relationship"))
          .withHeader("Authorization", containing("Bearer token"))
          .withHeader("Environment", containing("stub"))
          .withRequestBody(
            equalToJson(
              s"""
                 |{
                 |"regime": "ITSA",
                 |"authProfile": "ITSAS001",
                 |"relationshipType": "ZA01"
                 |}""".stripMargin,
              true,
              true
            )
          )
      )
    }

    "request body contains regime as VATC and idType as VRN when client Id is a Vrn" in {
      givenAgentCanBeAllocated(vrn, Arn("someArn"))
      givenAuditConnector()

      await(relationshipConnector.createAgentRelationship(vatEnrolmentKey, Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/registration/relationship"))
          .withRequestBody(
            equalToJson(
              s"""{
                 |"regime": "VATC",
                 |"idType" : "VRN",
                 |"relationshipType" : "ZA01",
                 |"authProfile" : "ALL00001"
                 |}""".stripMargin,
              true,
              true
            )
          )
      )
    }

    "request body contains regime as TRS and idType as UTR when client Id is a UTR" in {
      givenAgentCanBeAllocated(utr, Arn("someArn"))
      givenAuditConnector()

      await(relationshipConnector.createAgentRelationship(trustEnrolmentKey, Arn("someArn")))

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
            )
          )
      )
    }

    "request body contains regime as TRS and idType as URN when client Id is a URN" in {
      givenAgentCanBeAllocated(urn, Arn("someArn"))
      givenAuditConnector()

      await(relationshipConnector.createAgentRelationship(trustNTEnrolmentKey, Arn("someArn")))

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
            )
          )
      )
    }

    "request body contains regime as PPT and idType as ZPPT when client Id is a PptRef" in {
      givenAgentCanBeAllocated(pptRef, Arn("someArn"))
      givenAuditConnector()

      await(relationshipConnector.createAgentRelationship(pptEnrolmentKey, Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/registration/relationship"))
          .withRequestBody(
            equalToJson(
              s"""{
                 |"regime": "PPT",
                 |"refNumber" : "XAPPT0004567890"
                 |}""".stripMargin,
              true,
              true
            )
          )
      )
    }

    "request body contains regime as PLR and idType as PLR when client Id is a PlrId" in {
      givenAgentCanBeAllocated(plrId, Arn("someArn"))
      givenAuditConnector()

      await(relationshipConnector.createAgentRelationship(plrEnrolmentKey, Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/registration/relationship"))
          .withRequestBody(
            equalToJson(
              s"""{
                 |"regime": "PLR",
                 |"refNumber" : "XMPLR0012345678"
                 |}""".stripMargin,
              true,
              true
            )
          )
      )
    }

    "throw an IllegalArgumentException when the tax identifier is not supported" in {
      an[IllegalArgumentException] should be thrownBy await(
        relationshipConnector.createAgentRelationship(EnrolmentKey("foo"), Arn("bar"))
      )
    }

    "return nothing when IF is throwing errors" in {
      givenReturnsServerError()
      await(relationshipConnector.createAgentRelationship(vatEnrolmentKey, Arn("someArn"))) shouldBe None
    }

    "return nothing when IF is unavailable" in {
      givenReturnsServiceUnavailable()
      await(relationshipConnector.createAgentRelationship(vatEnrolmentKey, Arn("someArn"))) shouldBe None
    }
  }

  "IFConnector DeleteAgentRelationship" should {
    "delete relationship between agent and client and return 200 for ItSa service" in {
      givenAgentCanBeDeallocated(mtdItId, Arn("bar"))
      givenAuditConnector()
      await(
        relationshipConnector.deleteAgentRelationship(mtdItEnrolmentKey, Arn("bar"))
      ).get.processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for ItSa Supp service" in {
      givenAgentCanBeDeallocated(mtdItId, Arn("bar"))
      givenAuditConnector()
      await(
        relationshipConnector.deleteAgentRelationship(mtdItSuppEnrolmentKey, Arn("bar"))
      ).get.processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for Vat service" in {
      givenAgentCanBeDeallocated(vrn, Arn("bar"))
      givenAuditConnector()
      await(
        relationshipConnector.deleteAgentRelationship(vatEnrolmentKey, Arn("bar"))
      ).get.processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for Trust service" in {
      givenAgentCanBeDeallocated(utr, Arn("bar"))
      givenAuditConnector()
      await(
        relationshipConnector.deleteAgentRelationship(trustEnrolmentKey, Arn("bar"))
      ).get.processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for Trust service with URN" in {
      givenAgentCanBeDeallocated(urn, Arn("bar"))
      givenAuditConnector()
      await(
        relationshipConnector.deleteAgentRelationship(trustNTEnrolmentKey, Arn("bar"))
      ).get.processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for PPT service with PptRef" in {
      givenAgentCanBeDeallocated(pptRef, Arn("bar"))
      givenAuditConnector()
      await(
        relationshipConnector.deleteAgentRelationship(pptEnrolmentKey, Arn("bar"))
      ).get.processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for Pillar2 service with PlrId" in {
      givenAgentCanBeDeallocated(plrId, Arn("bar"))
      givenAuditConnector()
      await(
        relationshipConnector.deleteAgentRelationship(plrEnrolmentKey, Arn("bar"))
      ).get.processingDate should not be null
    }

    "not delete relationship between agent and client and return nothing for ItSa service" in {
      givenAgentCanNotBeDeallocated(status = 404)
      givenAuditConnector()
      await(relationshipConnector.deleteAgentRelationship(mtdItEnrolmentKey, Arn("bar"))) shouldBe None
    }

    "not delete relationship between agent and client and return nothing for Vat service" in {
      givenAgentCanNotBeDeallocated(status = 404)
      givenAuditConnector()
      await(relationshipConnector.deleteAgentRelationship(mtdItEnrolmentKey, Arn("bar"))) shouldBe None
    }

    "throw an IllegalArgumentException when the tax identifier is not supported" in {
      an[IllegalArgumentException] should be thrownBy await(
        relationshipConnector.deleteAgentRelationship(EnrolmentKey("foo"), Arn("bar"))
      )
    }

    "return nothing when IF is throwing errors" in {
      givenReturnsServerError()
      await(relationshipConnector.deleteAgentRelationship(vatEnrolmentKey, Arn("someArn"))) shouldBe None
    }

    "return nothing when IF is unavailable" in {
      givenReturnsServiceUnavailable()
      await(relationshipConnector.deleteAgentRelationship(vatEnrolmentKey, Arn("someArn"))) shouldBe None
    }
  }

  "IFConnector GetActiveClientRelationships" should {

    "return existing active relationships for specified clientId for ItSa service" in {
      getActiveRelationshipsViaClient(mtdItId, agentARN)
      givenAuditConnector()

      val result = await(relationshipConnector.getActiveClientRelationships(mtdItId, MtdIt))
      result.get.arn shouldBe agentARN
    }

    "return existing active relationships for specified clientId for ItSa Supp service" in {
      getActiveRelationshipsViaClient(mtdItId, agentARN, "ITSAS001")
      givenAuditConnector()

      val result = await(relationshipConnector.getActiveClientRelationships(mtdItId, MtdItSupp))
      result.get.arn shouldBe agentARN
    }

    "return existing active relationships for specified clientId for Vat service" in {
      getActiveRelationshipsViaClient(vrn, agentARN)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(vrn, Vat))
      result.get.arn shouldBe agentARN
    }

    "return existing active relationships for specified clientId for CGT service" in {
      getActiveRelationshipsViaClient(cgt, agentARN)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(cgt, CapitalGains))
      result.get.arn shouldBe agentARN
    }

    "return existing active relationships for specified clientId for TRS (UTR) service" in {
      getActiveRelationshipsViaClient(utr, agentARN)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(utr, Trust))
      result.get.arn shouldBe agentARN

    }

    "return existing active relationships for specified clientId for TRS (URN) service" in {
      getActiveRelationshipsViaClient(urn, agentARN)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(urn, TrustNT))
      result.get.arn shouldBe agentARN

    }

    "return existing active relationships for specified clientId for PPT service" in {
      getActiveRelationshipsViaClient(pptRef, agentARN)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(pptRef, Ppt))
      result.get.arn shouldBe agentARN
    }

    "return existing active relationships for specified clientId for Pillar2 service" in {
      getActiveRelationshipsViaClient(plrId, agentARN)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(plrId, Pillar2))
      result.get.arn shouldBe agentARN
    }

    "return None if IF returns 404 for ItSa service" in {
      getActiveRelationshipFailsWith(mtdItId, status = 404)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(mtdItId, MtdIt))
      result shouldBe None
    }

    "return None if IF returns 404 for Vat service" in {
      getActiveRelationshipFailsWith(vrn, status = 404)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(vrn, Vat))
      result shouldBe None
    }

    "return None if IF returns 404 for CGT service" in {
      getActiveRelationshipFailsWith(cgt, status = 404)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(cgt, CapitalGains))
      result shouldBe None
    }

    "return None if IF returns 404 for TRS (UTR) service" in {
      getActiveRelationshipFailsWith(utr, status = 404)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(utr, Trust))
      result shouldBe None
    }

    "return None if IF returns 404 for TRS (URN) service" in {
      getActiveRelationshipFailsWith(urn, status = 404)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(urn, TrustNT))
      result shouldBe None
    }

    "return None if IF returns 404 for PPT service" in {
      getActiveRelationshipFailsWith(pptRef, status = 404)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(pptRef, Ppt))
      result shouldBe None
    }

    "return None if IF returns 404 for Pillar2 service" in {
      getActiveRelationshipFailsWith(plrId, status = 404)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(plrId, Pillar2))
      result shouldBe None
    }

    "return None if IF returns 400 for ItSa service" in {
      getActiveRelationshipFailsWith(mtdItId, status = 400)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(mtdItId, MtdIt))
      result shouldBe None
    }

    "return None if IF returns 400 for Vat service" in {
      getActiveRelationshipFailsWith(vrn, status = 400)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(vrn, Vat))
      result shouldBe None
    }

    "return None if IF returns 403 AGENT_SUSPENDED" in {
      getActiveRelationshipFailsWithSuspended(vrn)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(vrn, Vat))
      result shouldBe None
    }

    "record metrics for GetStatusAgentRelationship for ItSa service" in {
      givenCleanMetricRegistry()
      givenAuditConnector()
      getActiveRelationshipsViaClient(mtdItId, agentARN)
      val result = await(relationshipConnector.getActiveClientRelationships(mtdItId, MtdIt))
      result.get.arn shouldBe agentARN
      timerShouldExistsAndBeenUpdated("ConsumedAPI-IF-GetActiveClientRelationships-GET")
    }

    "record metrics for GetStatusAgentRelationship for Vat service" in {
      givenCleanMetricRegistry()
      givenAuditConnector()
      getActiveRelationshipsViaClient(vrn, agentARN)

      val result = await(relationshipConnector.getActiveClientRelationships(vrn, Vat))
      result.get.arn shouldBe agentARN
      timerShouldExistsAndBeenUpdated("ConsumedAPI-IF-GetActiveClientRelationships-GET")
    }
  }

  "GetInactiveAgentRelationships" should {
    val encodedArn = UriEncoding.encodePathSegment(agentARN.value, "UTF-8")

    "return existing inactive relationships for specified clientId for ItSa service" in {
      getInactiveRelationshipsViaAgent(agentARN, otherTaxIdentifier(mtdItId), mtdItId)
      givenAuditConnector()
      val result = await(relationshipConnector.getInactiveRelationships(agentARN))
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
      givenAuditConnector()
      val result = await(relationshipConnector.getInactiveRelationships(agentARN))
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
      givenAuditConnector()
      val result = await(relationshipConnector.getInactiveRelationships(agentARN))
      result shouldBe Seq.empty
    }

    "return empty sequence if IF returns 404 for Vat service" in {
      getFailAgentInactiveRelationships(encodedArn, status = 404)
      givenAuditConnector()
      val result = await(relationshipConnector.getInactiveRelationships(agentARN))
      result shouldBe Seq.empty
    }

    "return empty sequence if IF returns 400 for ItSa service" in {
      getFailAgentInactiveRelationships(encodedArn, status = 400)
      givenAuditConnector()
      val result = await(relationshipConnector.getInactiveRelationships(agentARN))
      result shouldBe Seq.empty
    }

    "return empty sequence if IF returns 400 for Vat service" in {
      getFailWithSuspendedAgentInactiveRelationships(encodedArn)
      givenAuditConnector()
      val result = await(relationshipConnector.getInactiveRelationships(agentARN))
      result shouldBe Seq.empty
    }

    "return None if IF returns 403 AGENT_SUSPENDED" in {
      getActiveRelationshipFailsWithSuspended(vrn)
      givenAuditConnector()
      val result = await(relationshipConnector.getActiveClientRelationships(vrn, Vat))
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
      givenAuditConnector()
      relationshipConnector.isActive(noEndRelationship) shouldBe true
    }
    "return true when the end date is after the current date" in {
      givenAuditConnector()
      relationshipConnector.isActive(afterCurrentDateRelationship) shouldBe true
    }
    "return false when the end date is before the current date" in {
      givenAuditConnector()
      relationshipConnector.isActive(beforeCurrentDateRelationship) shouldBe false
    }
  }

  "isInactive" should {
    val noEndRelationship =
      InactiveRelationship(
        Arn("foo"),
        None,
        Some(LocalDate.parse("1111-11-11")),
        "123456789",
        "personal",
        "HMRC-MTD-VAT"
      )
    val endsBeforeCurrentDate =
      InactiveRelationship(
        Arn("foo"),
        Some(LocalDate.parse("1111-11-11")),
        Some(LocalDate.parse("1111-11-11")),
        "123456789",
        "personal",
        "HMRC-MTD-VAT"
      )
    val endsAtCurrentDateRelationship =
      InactiveRelationship(
        Arn("foo"),
        Some(LocalDate.now()),
        Some(LocalDate.parse("1111-11-11")),
        "123456789",
        "personal",
        "HMRC-MTD-VAT"
      )

    "return false when the relationship is active" in {
      givenAuditConnector()
      relationshipConnector.isNotActive(noEndRelationship) shouldBe false
    }
    "return true when the end date is before the current date" in {
      givenAuditConnector()
      relationshipConnector.isNotActive(endsBeforeCurrentDate) shouldBe true
    }
    "return true when the end date is equal to the current date" in {
      givenAuditConnector()
      relationshipConnector.isNotActive(endsAtCurrentDateRelationship) shouldBe true
    }
  }

  "getInactiveClientRelationships" should {

    "return existing inactive relationships for specified clientId for ItSa service" in {

      getInactiveRelationshipsForClient(mtdItId)
      givenAuditConnector()

      val result = await(relationshipConnector.getInactiveClientRelationships(mtdItId, MtdIt))

      result.head shouldBe InactiveRelationship(
        arn = agentARN,
        dateTo = Some(LocalDate.parse("2018-09-09")),
        dateFrom = Some(LocalDate.parse("2015-09-10")),
        clientId = mtdItId.value,
        service = "HMRC-MTD-IT",
        clientType = "personal"
      )
    }

    "return existing inactive relationships for specified clientId for ItSa Supp service" in {

      getInactiveRelationshipsForClient(mtdItId, "ITSAS001")
      givenAuditConnector()

      val result = await(relationshipConnector.getInactiveClientRelationships(mtdItId, MtdItSupp))

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
      givenAuditConnector()

      val result = await(relationshipConnector.getInactiveClientRelationships(vrn, Vat))

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
      givenAuditConnector()

      val result = await(relationshipConnector.getInactiveClientRelationships(utr, Trust))

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
      givenAuditConnector()

      val result = await(relationshipConnector.getInactiveClientRelationships(urn, TrustNT))

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
      givenAuditConnector()

      val result = await(relationshipConnector.getInactiveClientRelationships(cgt, CapitalGains))

      result.head shouldBe InactiveRelationship(
        arn = agentARN,
        dateTo = Some(LocalDate.parse("2018-09-09")),
        dateFrom = Some(LocalDate.parse("2015-09-10")),
        clientId = cgt.value,
        service = "HMRC-CGT-PD",
        clientType = "business"
      )
    }

    "return existing inactive relationships for specified clientId for Pillar2 service" in {

      getInactiveRelationshipsForClient(plrId)
      givenAuditConnector()

      val result = await(relationshipConnector.getInactiveClientRelationships(plrId, Pillar2))
      result.head shouldBe InactiveRelationship(
        arn = agentARN,
        dateTo = Some(LocalDate.parse("2018-09-09")),
        dateFrom = Some(LocalDate.parse("2015-09-10")),
        clientId = plrId.value,
        service = "HMRC-PILLAR2-ORG",
        clientType = "business"
      )
    }

    "return empty Seq if the identifier is valid but there are no inactive relationships" in {

      getNoInactiveRelationshipsForClient(mtdItId)
      givenAuditConnector()
      val result = await(relationshipConnector.getInactiveClientRelationships(mtdItId, MtdIt))
      result.isEmpty shouldBe true
    }

    "return empty Seq if the identifier if IF responds with a Bad Request" in {

      getFailInactiveRelationshipsForClient(mtdItId, 400)
      givenAuditConnector()
      val result = await(relationshipConnector.getInactiveClientRelationships(mtdItId, MtdIt))
      result.isEmpty shouldBe true
    }

    "return empty Seq if the identifier if IF responds with Not Found" in {

      getFailInactiveRelationshipsForClient(mtdItId, 404)
      givenAuditConnector()
      val result = await(relationshipConnector.getInactiveClientRelationships(mtdItId, MtdIt))
      result.isEmpty shouldBe true
    }

    "return empty Seq if IF returns 403 and body AGENT_SUSPENDED" in {
      getFailInactiveRelationshipsForClient(
        mtdItId,
        403,
        Some(
          s""""{"code":"AGENT_SUSPENDED","reason":"The remote endpoint has indicated that the agent is suspended"}"""
        )
      )
      givenAuditConnector()
      val result = await(relationshipConnector.getInactiveClientRelationships(mtdItId, MtdIt))
      result.isEmpty shouldBe true
    }

    "return empty Seq when IF is unavailable" in {
      givenReturnsServiceUnavailable()
      givenAuditConnector()
      await(relationshipConnector.getInactiveClientRelationships(mtdItId, MtdIt)) shouldBe empty
    }
  }
}
