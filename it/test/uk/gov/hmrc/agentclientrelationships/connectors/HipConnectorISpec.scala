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
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.helpers.HipHeaders
import uk.gov.hmrc.agentclientrelationships.model.ActiveRelationship
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.InactiveRelationship
import uk.gov.hmrc.agentclientrelationships.model.RegistrationRelationshipResponse
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ClientDetailsNotFound
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ErrorRetrievingClientDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa.ItsaBusinessDetails
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Cbc
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdIt
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Pillar2
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Ppt
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.agentclientrelationships.services.AgentCacheProvider
import uk.gov.hmrc.agentclientrelationships.stubs.DataStreamStub
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentclientrelationships.support.WireMockSupport
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2

import java.time.LocalDate
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor

class HipConnectorISpec
extends UnitSpec
with GuiceOneServerPerSuite
with WireMockSupport
with HipStub
with DataStreamStub {

  override implicit lazy val app: Application = appBuilder.build()

  val httpClient: HttpClientV2 = app.injector.instanceOf[HttpClientV2]
  val agentCacheProvider: AgentCacheProvider = app.injector.instanceOf[AgentCacheProvider]
  val hipHeaders: HipHeaders = app.injector.instanceOf[HipHeaders]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  val testNino = NinoWithoutSuffix("AA000001")

  protected def appBuilder: GuiceApplicationBuilder = new GuiceApplicationBuilder().configure(
    "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
    "microservice.services.tax-enrolments.port" -> wireMockPort,
    "microservice.services.users-groups-search.port" -> wireMockPort,
    "microservice.services.if.port" -> wireMockPort,
    "microservice.services.auth.port" -> wireMockPort,
    "microservice.services.if.environment" -> "stub",
    "microservice.services.if.authorization-api1171-token" -> "token",
    "microservice.services.agent-mapping.port" -> wireMockPort,
    "auditing.consumer.baseUri.host" -> wireMockHost,
    "auditing.consumer.baseUri.port" -> wireMockPort,
    "features.copy-relationship.mtd-it" -> true,
    "features.recovery-enable" -> false,
    "agent.cache.expires" -> "1 millis",
    "agent.cache.enabled" -> false,
    "microservice.services.hip.port" -> wireMockPort,
    "microservice.services.hip.authorization-token" -> "token"
  )

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val request: RequestHeader = FakeRequest()
  val hipConnector =
    new HipConnector(
      httpClient,
      agentCacheProvider,
      hipHeaders,
      appConfig
    )(ec)

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
    case Vrn(_) => Vrn("101747641")
    case Utr(_) => Utr("2134514321")
    case Urn(_) => Urn("XXTRUST12345678")
    case PptRef(_) => PptRef("XAPPT0004567890")
    case PlrId(_) => PlrId("XMPLR0012345678")
    case x => x
  }

  "HIPConnector CreateAgentRelationship" should {

    "create relationship between agent and client and return 200" in {
      givenAgentCanBeAllocated(mtdItId, Arn("bar"))
      givenAuditConnector()
      await(hipConnector.createAgentRelationship(mtdItEnrolmentKey, Arn("bar"))) shouldBe
        RegistrationRelationshipResponse("2001-12-17T09:30:47Z")
    }

    "not create relationship between agent and client and return nothing" in {
      givenAgentCanNotBeAllocated(status = 404)
      givenAuditConnector()
      intercept[UpstreamErrorResponse](await(hipConnector.createAgentRelationship(mtdItEnrolmentKey, Arn("bar"))))
    }

    "request body contains regime as ITSA when client Id is an MtdItId" in {
      givenAgentCanBeAllocated(mtdItId, Arn("someArn"))
      givenAuditConnector()

      await(hipConnector.createAgentRelationship(mtdItEnrolmentKey, Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/etmp/RESTAdapter/rosm/agent-relationship"))
          .withHeader("Authorization", containing("Basic token"))
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

      await(hipConnector.createAgentRelationship(mtdItSuppEnrolmentKey, Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/etmp/RESTAdapter/rosm/agent-relationship"))
          .withHeader("Authorization", containing("Basic token"))
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

      await(hipConnector.createAgentRelationship(vatEnrolmentKey, Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/etmp/RESTAdapter/rosm/agent-relationship")).withRequestBody(
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

      await(hipConnector.createAgentRelationship(trustEnrolmentKey, Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/etmp/RESTAdapter/rosm/agent-relationship")).withRequestBody(
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

      await(hipConnector.createAgentRelationship(trustNTEnrolmentKey, Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/etmp/RESTAdapter/rosm/agent-relationship")).withRequestBody(
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

      await(hipConnector.createAgentRelationship(pptEnrolmentKey, Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/etmp/RESTAdapter/rosm/agent-relationship")).withRequestBody(
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

      await(hipConnector.createAgentRelationship(plrEnrolmentKey, Arn("someArn")))

      verify(
        1,
        postRequestedFor(urlPathEqualTo("/etmp/RESTAdapter/rosm/agent-relationship")).withRequestBody(
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
      givenAuditConnector()
      an[IllegalArgumentException] should be thrownBy await(
        hipConnector.createAgentRelationship(EnrolmentKey("foo"), Arn("bar"))
      )
    }

    "return nothing when IF is throwing errors" in {
      givenReturnsServerError()
      givenAuditConnector()
      intercept[UpstreamErrorResponse](await(hipConnector.createAgentRelationship(vatEnrolmentKey, Arn("someArn"))))
    }

    "return nothing when IF is unavailable" in {
      givenAuditConnector()
      givenReturnsServiceUnavailable()
      intercept[UpstreamErrorResponse](await(hipConnector.createAgentRelationship(vatEnrolmentKey, Arn("someArn"))))
    }
  }

  "HIPConnector DeleteAgentRelationship" should {
    "delete relationship between agent and client and return 200 for ItSa service" in {
      givenAgentCanBeDeallocated(mtdItId, Arn("bar"))
      givenAuditConnector()
      await(hipConnector.deleteAgentRelationship(mtdItEnrolmentKey, Arn("bar"))).get.processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for ItSa Supp service" in {
      givenAgentCanBeDeallocated(mtdItId, Arn("bar"))
      givenAuditConnector()
      await(
        hipConnector.deleteAgentRelationship(mtdItSuppEnrolmentKey, Arn("bar"))
      ).get.processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for Vat service" in {
      givenAgentCanBeDeallocated(vrn, Arn("bar"))
      givenAuditConnector()
      await(hipConnector.deleteAgentRelationship(vatEnrolmentKey, Arn("bar"))).get.processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for Trust service" in {
      givenAgentCanBeDeallocated(utr, Arn("bar"))
      givenAuditConnector()
      await(hipConnector.deleteAgentRelationship(trustEnrolmentKey, Arn("bar"))).get.processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for Trust service with URN" in {
      givenAgentCanBeDeallocated(urn, Arn("bar"))
      givenAuditConnector()
      await(hipConnector.deleteAgentRelationship(trustNTEnrolmentKey, Arn("bar"))).get.processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for PPT service with PptRef" in {
      givenAgentCanBeDeallocated(pptRef, Arn("bar"))
      givenAuditConnector()
      await(hipConnector.deleteAgentRelationship(pptEnrolmentKey, Arn("bar"))).get.processingDate should not be null
    }

    "delete relationship between agent and client and return 200 for Pillar2 service with PlrId" in {
      givenAgentCanBeDeallocated(plrId, Arn("bar"))
      givenAuditConnector()
      await(hipConnector.deleteAgentRelationship(plrEnrolmentKey, Arn("bar"))).get.processingDate should not be null
    }

    "not delete relationship between agent and client and return nothing for ItSa service" in {
      givenAgentCanNotBeDeallocated(status = 404)
      givenAuditConnector()
      an[UpstreamErrorResponse] should be thrownBy await(
        hipConnector.deleteAgentRelationship(mtdItEnrolmentKey, Arn("bar"))
      )
    }

    "not delete relationship between agent and client and return nothing for Vat service" in {
      givenAgentCanNotBeDeallocated(status = 404)
      givenAuditConnector()
      an[UpstreamErrorResponse] should be thrownBy await(
        hipConnector.deleteAgentRelationship(mtdItEnrolmentKey, Arn("bar"))
      )
    }

    "throw an IllegalArgumentException when the tax identifier is not supported" in {
      givenAuditConnector()
      an[IllegalArgumentException] should be thrownBy await(
        hipConnector.deleteAgentRelationship(EnrolmentKey("foo"), Arn("bar"))
      )
    }

    "return an exception when HIP has returned an error" in {
      givenAuditConnector()
      givenReturnsServerError()
      an[UpstreamErrorResponse] should be thrownBy await(
        hipConnector.deleteAgentRelationship(vatEnrolmentKey, Arn("someArn"))
      )
    }

    "return an exception when HIP is unavailable" in {
      givenAuditConnector()
      givenReturnsServiceUnavailable()
      an[UpstreamErrorResponse] should be thrownBy await(
        hipConnector.deleteAgentRelationship(vatEnrolmentKey, Arn("someArn"))
      )
    }
  }

  "HIPConnector GetActiveClientRelationships" should {

    "return existing active relationships for specified clientId for ItSa service" in {
      getActiveRelationshipsViaClient(mtdItId, agentARN)
      givenAuditConnector()

      val result = await(hipConnector.getActiveClientRelationships(mtdItId, Service.MtdIt))
      result.get.arn shouldBe agentARN
    }

    "return existing active relationships for specified clientId for ItSa Supp service" in {
      getActiveRelationshipsViaClient(
        mtdItId,
        agentARN,
        "ITSAS001"
      )
      givenAuditConnector()

      val result = await(hipConnector.getActiveClientRelationships(mtdItId, Service.MtdItSupp))
      result.get.arn shouldBe agentARN
    }

    "return existing active relationships for specified clientId for Vat service" in {
      getActiveRelationshipsViaClient(vrn, agentARN)
      givenAuditConnector()
      val result = await(hipConnector.getActiveClientRelationships(vrn, Service.Vat))
      result.get.arn shouldBe agentARN
    }

    "return existing active relationships for specified clientId for CGT service" in {
      getActiveRelationshipsViaClient(cgt, agentARN)
      givenAuditConnector()
      val result = await(hipConnector.getActiveClientRelationships(cgt, Service.CapitalGains))
      result.get.arn shouldBe agentARN
    }

    "return existing active relationships for specified clientId for TRS (UTR) service" in {
      getActiveRelationshipsViaClient(utr, agentARN)
      givenAuditConnector()
      val result = await(hipConnector.getActiveClientRelationships(utr, Service.Trust))
      result.get.arn shouldBe agentARN

    }

    "return existing active relationships for specified clientId for TRS (URN) service" in {
      getActiveRelationshipsViaClient(urn, agentARN)
      givenAuditConnector()
      val result = await(hipConnector.getActiveClientRelationships(urn, Service.TrustNT))
      result.get.arn shouldBe agentARN

    }

    "return existing active relationships for specified clientId for PPT service" in {
      getActiveRelationshipsViaClient(pptRef, agentARN)
      givenAuditConnector()
      val result = await(hipConnector.getActiveClientRelationships(pptRef, Ppt))
      result.get.arn shouldBe agentARN
    }

    "return existing active relationships for specified clientId for Pillar2 service" in {
      getActiveRelationshipsViaClient(plrId, agentARN)
      givenAuditConnector()
      val result = await(hipConnector.getActiveClientRelationships(plrId, Pillar2))
      result.get.arn shouldBe agentARN
    }

    "return None if IF returns 404 for ItSa service" in {
      getActiveRelationshipFailsWith(mtdItId, status = 404)
      givenAuditConnector()
      val result = await(hipConnector.getActiveClientRelationships(mtdItId, MtdIt))
      result shouldBe None
    }

    "return None if IF returns 404 for Vat service" in {
      getActiveRelationshipFailsWith(vrn, status = 404)
      givenAuditConnector()
      val result = await(hipConnector.getActiveClientRelationships(vrn, Service.Vat))
      result shouldBe None
    }

    "return None if IF returns 404 for CGT service" in {
      getActiveRelationshipFailsWith(cgt, status = 404)
      givenAuditConnector()
      val result = await(hipConnector.getActiveClientRelationships(cgt, Service.CapitalGains))
      result shouldBe None
    }

    "return None if IF returns 404 for TRS (UTR) service" in {
      getActiveRelationshipFailsWith(utr, status = 404)
      givenAuditConnector()
      val result = await(hipConnector.getActiveClientRelationships(utr, Service.Trust))
      result shouldBe None
    }

    "return None if IF returns 404 for TRS (URN) service" in {
      getActiveRelationshipFailsWith(urn, status = 404)
      givenAuditConnector()
      val result = await(hipConnector.getActiveClientRelationships(urn, Service.TrustNT))
      result shouldBe None
    }

    "return None if IF returns 404 for PPT service" in {
      getActiveRelationshipFailsWith(pptRef, status = 404)
      givenAuditConnector()
      val result = await(hipConnector.getActiveClientRelationships(pptRef, Service.Ppt))
      result shouldBe None
    }

    "return None if IF returns 404 for Pillar2 service" in {
      getActiveRelationshipFailsWith(plrId, status = 404)
      givenAuditConnector()
      val result = await(hipConnector.getActiveClientRelationships(plrId, Service.Pillar2))
      result shouldBe None
    }

    "return None if IF returns 400 for ItSa service" in {
      getActiveRelationshipFailsWith(mtdItId, status = 400)
      givenAuditConnector()
      val result = await(hipConnector.getActiveClientRelationships(mtdItId, Service.MtdIt))
      result shouldBe None
    }

    "return None if IF returns 400 for Vat service" in {
      getActiveRelationshipFailsWith(vrn, status = 400)
      givenAuditConnector()
      val result = await(hipConnector.getActiveClientRelationships(vrn, Service.Vat))
      result shouldBe None
    }

    "return None if IF returns 403 AGENT_SUSPENDED" in {
      getActiveRelationshipFailsWithSuspended(vrn)
      givenAuditConnector()
      val result = await(hipConnector.getActiveClientRelationships(vrn, Cbc))
      result shouldBe None
    }
  }

  "isActive" should {
    val noEndRelationship = ActiveRelationship(
      Arn("foo"),
      None,
      Some(LocalDate.parse("1111-11-11"))
    )
    val afterCurrentDateRelationship = ActiveRelationship(
      Arn("foo"),
      Some(LocalDate.parse("2222-11-11")),
      Some(LocalDate.parse("1111-11-11"))
    )
    val beforeCurrentDateRelationship = ActiveRelationship(
      Arn("foo"),
      Some(LocalDate.parse("1111-11-11")),
      Some(LocalDate.parse("1111-11-11"))
    )
    "return true when the relationship has no end date" in {
      givenAuditConnector()
      hipConnector.isActive(noEndRelationship) shouldBe true
    }
    "return true when the end date is after the current date" in {
      givenAuditConnector()
      hipConnector.isActive(afterCurrentDateRelationship) shouldBe true
    }
    "return false when the end date is before the current date" in {
      givenAuditConnector()
      hipConnector.isActive(beforeCurrentDateRelationship) shouldBe false
    }
  }

  "isInactive" should {
    val noEndRelationship = InactiveRelationship(
      Arn("foo"),
      None,
      Some(LocalDate.parse("1111-11-11")),
      "123456789",
      "personal",
      "HMRC-MTD-VAT"
    )
    val endsBeforeCurrentDate = InactiveRelationship(
      Arn("foo"),
      Some(LocalDate.parse("1111-11-11")),
      Some(LocalDate.parse("1111-11-11")),
      "123456789",
      "personal",
      "HMRC-MTD-VAT"
    )
    val endsAtCurrentDateRelationship = InactiveRelationship(
      Arn("foo"),
      Some(LocalDate.now()),
      Some(LocalDate.parse("1111-11-11")),
      "123456789",
      "personal",
      "HMRC-MTD-VAT"
    )

    "return false when the relationship is active" in {
      givenAuditConnector()
      hipConnector.isNotActive(noEndRelationship) shouldBe false
    }
    "return true when the end date is before the current date" in {
      givenAuditConnector()
      hipConnector.isNotActive(endsBeforeCurrentDate) shouldBe true
    }
    "return true when the end date is equal to the current date" in {
      givenAuditConnector()
      hipConnector.isNotActive(endsAtCurrentDateRelationship) shouldBe true
    }
  }

  ".getItsaBusinessDetails" should {

    "return business details when receiving a 200 status" in {
      givenAuditConnector()
      givenMtdItsaBusinessDetailsExists(testNino, MtdItId("XAIT0000111122"))
      await(hipConnector.getItsaBusinessDetails(testNino)) shouldBe Right(
        ItsaBusinessDetails(
          "Erling Haal",
          Some("AA1 1AA"),
          "GB"
        )
      )
    }

    "return the first set of business details when receiving multiple" in {
      givenAuditConnector()
      givenMultipleItsaBusinessDetailsExists(testNino)
      await(hipConnector.getItsaBusinessDetails(testNino)) shouldBe Right(
        ItsaBusinessDetails(
          "Erling Haal",
          Some("AA1 1AA"),
          "GB"
        )
      )
    }

    "return a ClientDetailsNotFound error when no items are returned in the businessData array" in {
      givenAuditConnector()
      givenEmptyItsaBusinessDetailsExists(testNino)
      await(hipConnector.getItsaBusinessDetails(testNino)) shouldBe Left(ClientDetailsNotFound)
    }

    "return a ClientDetailsNotFound error when receiving a 404 status" in {
      givenAuditConnector()
      givenItsaBusinessDetailsError(testNino, NOT_FOUND)
      await(hipConnector.getItsaBusinessDetails(testNino)) shouldBe Left(ClientDetailsNotFound)
    }

    "return an ErrorRetrievingClientDetails error when receiving an unexpected status" in {
      givenAuditConnector()
      givenItsaBusinessDetailsError(testNino, INTERNAL_SERVER_ERROR)
      await(hipConnector.getItsaBusinessDetails(testNino)) should matchPattern {
        case Left(ErrorRetrievingClientDetails(INTERNAL_SERVER_ERROR, msg))
            if msg.startsWith("Unexpected error during 'getItsaBusinessDetails'") =>
      }
    }
  }

}
