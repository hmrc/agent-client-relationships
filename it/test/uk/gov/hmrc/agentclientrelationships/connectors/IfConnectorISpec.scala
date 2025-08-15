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

import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.helpers.IfHeaders
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.agentclientrelationships.stubs.{DataStreamStub, IfStub}
import uk.gov.hmrc.agentclientrelationships.support.{UnitSpec, WireMockSupport}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

class IFConnectorISpec
extends UnitSpec
with GuiceOneServerPerSuite
with WireMockSupport
with IfStub
with DataStreamStub {

  override implicit lazy val app: Application = appBuilder.build()

  val httpClient: HttpClientV2 = app.injector.instanceOf[HttpClientV2]
  val metrics: Metrics = app.injector.instanceOf[Metrics]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

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
    "features.copy-relationship.mtd-vat" -> true,
    "features.recovery-enable" -> false,
    "agent.cache.expires" -> "1 millis",
    "agent.cache.enabled" -> false,
    "agent.trackPage.cache.expires" -> "1 millis",
    "agent.trackPage.cache.enabled" -> false,
    "hip.BusinessDetails.enabled" -> false
  )

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val request: RequestHeader = FakeRequest()
  val ifConnector =
    new IfConnector(
      httpClient = httpClient,
      appConfig = appConfig,
      ifHeaders = app.injector.instanceOf[IfHeaders]
    )(metrics, ec)

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

}
