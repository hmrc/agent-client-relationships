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
import play.api.http.Status.{INTERNAL_SERVER_ERROR, NOT_FOUND}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors.helpers.HIPHeaders
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.cbc.SimpleCbcSubscription
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.cgt.CgtSubscriptionDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.itsa.{ItsaBusinessDetails, ItsaCitizenDetails, ItsaDesignatoryDetails}
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.pillar2.Pillar2Record
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.ppt.PptSubscriptionDetails
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.vat.{VatCustomerDetails, VatIndividual}
import uk.gov.hmrc.agentclientrelationships.model.clientDetails.{ClientDetailsNotFound, ErrorRetrievingClientDetails}
import uk.gov.hmrc.agentclientrelationships.services.AgentCacheProvider
import uk.gov.hmrc.agentclientrelationships.stubs.{ClientDetailsStub, DataStreamStub, IfStub}
import uk.gov.hmrc.agentclientrelationships.support.{UnitSpec, WireMockSupport}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

class ClientDetailsConnectorIfISpec
    extends UnitSpec
    with GuiceOneServerPerSuite
    with WireMockSupport
    with DataStreamStub
    with ClientDetailsStub
    with IfStub {

  override lazy val app: Application = appBuilder.build()

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.citizen-details.port" -> wireMockPort,
        "microservice.services.if.port"              -> wireMockPort,
        "microservice.services.eis.port"             -> wireMockPort,
        "microservice.services.des.port"             -> wireMockPort,
        "auditing.consumer.baseUri.host"             -> wireMockHost,
        "auditing.consumer.baseUri.port"             -> wireMockPort,
        "hip.BusinessDetails.enabled"                -> false
      )

  implicit val hc: HeaderCarrier = HeaderCarrier()

  val httpClient: HttpClient = app.injector.instanceOf[HttpClient]
  val httpClient2: HttpClientV2 = app.injector.instanceOf[HttpClientV2]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  val agentCacheProvider: AgentCacheProvider = app.injector.instanceOf[AgentCacheProvider]
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  val metrics: Metrics = app.injector.instanceOf[Metrics]
  val hipHeaders: HIPHeaders = app.injector.instanceOf[HIPHeaders]

  val connector = new ClientDetailsConnector(appConfig, httpClient, app.injector.instanceOf[Metrics])

  val iFconnector = new IfConnector(httpClient, ec)(metrics, appConfig)
  val hipConnector = new HipConnector(httpClient2, agentCacheProvider, hipHeaders, ec)(metrics, appConfig)

  val ifOrHipConnector = new IfOrHipConnector(hipConnector, iFconnector)(appConfig)

  ".getItsaDesignatoryDetails" should {

    "return designatory details when receiving a 200 status" in {
      givenAuditConnector()
      givenItsaDesignatoryDetailsExists("AA000001B")
      await(connector.getItsaDesignatoryDetails("AA000001B")) shouldBe Right(ItsaDesignatoryDetails(Some("AA1 1AA")))
    }

    "return a ClientDetailsNotFound error when receiving a 404 status" in {
      givenAuditConnector()
      givenItsaDesignatoryDetailsError("AA000001B", NOT_FOUND)
      await(connector.getItsaDesignatoryDetails("AA000001B")) shouldBe Left(ClientDetailsNotFound)
    }

    "return an ErrorRetrievingClientDetails error when receiving an unexpected status" in {
      givenAuditConnector()
      givenItsaDesignatoryDetailsError("AA000001B", INTERNAL_SERVER_ERROR)
      await(connector.getItsaDesignatoryDetails("AA000001B")) shouldBe
        Left(ErrorRetrievingClientDetails(INTERNAL_SERVER_ERROR, "Unexpected error during 'getItsaDesignatoryDetails'"))
    }
  }

  ".getItsaCitizenDetails" should {

    "return citizen details when receiving a 200 status" in {
      givenAuditConnector()
      givenItsaCitizenDetailsExists("AA000001B")
      val expectedModel =
        ItsaCitizenDetails(Some("Matthew"), Some("Kovacic"), Some(LocalDate.parse("2000-01-01")), Some("11223344"))
      await(connector.getItsaCitizenDetails("AA000001B")) shouldBe Right(expectedModel)
    }

    "return a ClientDetailsNotFound error when receiving a 404 status" in {
      givenAuditConnector()
      givenItsaCitizenDetailsError("AA000001B", NOT_FOUND)
      await(connector.getItsaCitizenDetails("AA000001B")) shouldBe Left(ClientDetailsNotFound)
    }

    "return an ErrorRetrievingClientDetails error when receiving an unexpected status" in {
      givenAuditConnector()
      givenItsaCitizenDetailsError("AA000001B", INTERNAL_SERVER_ERROR)
      await(connector.getItsaCitizenDetails("AA000001B")) shouldBe
        Left(ErrorRetrievingClientDetails(INTERNAL_SERVER_ERROR, "Unexpected error during 'getItsaCitizenDetails'"))
    }
  }

  ".getItsaBusinessDetails" should {

    "return business details when receiving a 200 status" in {
      givenAuditConnector()
      givenItsaBusinessDetailsExists("nino", "AA000001B")
      await(ifOrHipConnector.getItsaBusinessDetails("AA000001B")) shouldBe Right(
        ItsaBusinessDetails("Erling Haal", Some("AA1 1AA"), "GB")
      )
    }

    "return the first set of business details when receiving multiple" in {
      givenAuditConnector()
      givenMultipleItsaBusinessDetailsExists("AA000001B")
      await(ifOrHipConnector.getItsaBusinessDetails("AA000001B")) shouldBe Right(
        ItsaBusinessDetails("Erling Haal", Some("AA1 1AA"), "GB")
      )
    }

    "return a ClientDetailsNotFound error when no items are returned in the businessData array" in {
      givenAuditConnector()
      givenEmptyItsaBusinessDetailsExists("AA000001B")
      await(ifOrHipConnector.getItsaBusinessDetails("AA000001B")) shouldBe Left(ClientDetailsNotFound)
    }

    "return a ClientDetailsNotFound error when receiving a 404 status" in {
      givenAuditConnector()
      givenItsaBusinessDetailsError("AA000001B", NOT_FOUND)
      await(ifOrHipConnector.getItsaBusinessDetails("AA000001B")) shouldBe Left(ClientDetailsNotFound)
    }

    "return an ErrorRetrievingClientDetails error when receiving an unexpected status" in {
      givenAuditConnector()
      givenItsaBusinessDetailsError("AA000001B", INTERNAL_SERVER_ERROR)
      await(ifOrHipConnector.getItsaBusinessDetails("AA000001B")) shouldBe
        Left(ErrorRetrievingClientDetails(INTERNAL_SERVER_ERROR, "Unexpected error during 'getItsaBusinessDetails'"))
    }
  }

  ".getVatCustomerInfo" should {

    "return customer info when receiving a 200 status" in {
      givenAuditConnector()
      givenVatCustomerInfoExists("123456789")
      val expectedModel = VatCustomerDetails(
        Some("CFG"),
        Some(VatIndividual(Some("Mr"), Some("Ilkay"), Some("Silky"), Some("Gundo"))),
        Some("CFG Solutions"),
        Some(LocalDate.parse("2020-01-01")),
        isInsolvent = false
      )
      await(connector.getVatCustomerInfo("123456789")) shouldBe Right(expectedModel)
    }

    "return a ClientDetailsNotFound error when receiving a 404 status" in {
      givenAuditConnector()
      givenVatCustomerInfoError("123456789", NOT_FOUND)
      await(connector.getVatCustomerInfo("123456789")) shouldBe Left(ClientDetailsNotFound)
    }

    "return an ErrorRetrievingClientDetails error when receiving an unexpected status" in {
      givenAuditConnector()
      givenVatCustomerInfoError("123456789", INTERNAL_SERVER_ERROR)
      await(connector.getVatCustomerInfo("123456789")) shouldBe
        Left(ErrorRetrievingClientDetails(INTERNAL_SERVER_ERROR, "Unexpected error during 'getVatCustomerInfo'"))
    }
  }

  ".getTrustName" should {

    "return a trust name when receiving a 200 status (UTR identifier)" in {
      givenAuditConnector()
      givenTrustDetailsExist("1234567890", "UTR")
      await(connector.getTrustName("1234567890")) shouldBe Right("The Safety Trust")
    }

    "return a trust name when receiving a 200 status (URN identifier)" in {
      givenAuditConnector()
      givenTrustDetailsExist("1234567890ABCDE", "URN")
      await(connector.getTrustName("1234567890ABCDE")) shouldBe Right("The Safety Trust")
    }

    "return a ClientDetailsNotFound error when receiving a 404 status" in {
      givenAuditConnector()
      givenTrustDetailsError("1234567890", "UTR", NOT_FOUND)
      await(connector.getTrustName("1234567890")) shouldBe Left(ClientDetailsNotFound)
    }

    "return an ErrorRetrievingClientDetails error when receiving an unexpected status" in {
      givenAuditConnector()
      givenTrustDetailsError("1234567890", "UTR", INTERNAL_SERVER_ERROR)
      await(connector.getTrustName("1234567890")) shouldBe
        Left(ErrorRetrievingClientDetails(INTERNAL_SERVER_ERROR, "Unexpected error during 'getTrustName'"))
    }
  }

  ".getCgtSubscriptionDetails" should {

    "return CGT subscription details when receiving a 200 status" in {
      givenAuditConnector()
      givenCgtDetailsExist("XACGTP123456789")
      await(connector.getCgtSubscriptionDetails("XACGTP123456789")) shouldBe
        Right(CgtSubscriptionDetails("CFG Solutions", Some("AA1 1AA"), "GB"))
    }

    "return a ClientDetailsNotFound error when receiving a 404 status" in {
      givenAuditConnector()
      givenCgtDetailsError("XACGTP123456789", NOT_FOUND)
      await(connector.getCgtSubscriptionDetails("XACGTP123456789")) shouldBe Left(ClientDetailsNotFound)
    }

    "return an ErrorRetrievingClientDetails error when receiving an unexpected status" in {
      givenAuditConnector()
      givenCgtDetailsError("XACGTP123456789", INTERNAL_SERVER_ERROR)
      await(connector.getCgtSubscriptionDetails("XACGTP123456789")) shouldBe
        Left(ErrorRetrievingClientDetails(INTERNAL_SERVER_ERROR, "Unexpected error during 'getCgtSubscriptionDetails'"))
    }
  }

  ".getPptSubscriptionDetails" should {

    "return PPT subscription details when receiving a 200 status" in {
      givenAuditConnector()
      givenPptDetailsExist("XAPPT0004567890")
      await(connector.getPptSubscriptionDetails("XAPPT0004567890")) shouldBe
        Right(
          PptSubscriptionDetails("CFG Solutions", LocalDate.parse("2020-01-01"), Some(LocalDate.parse("2030-01-01")))
        )
    }

    "return a ClientDetailsNotFound error when receiving a 404 status" in {
      givenAuditConnector()
      givenPptDetailsError("XAPPT0004567890", NOT_FOUND)
      await(connector.getPptSubscriptionDetails("XAPPT0004567890")) shouldBe Left(ClientDetailsNotFound)
    }

    "return an ErrorRetrievingClientDetails error when receiving an unexpected status" in {
      givenAuditConnector()
      givenPptDetailsError("XAPPT0004567890", INTERNAL_SERVER_ERROR)
      await(connector.getPptSubscriptionDetails("XAPPT0004567890")) shouldBe
        Left(ErrorRetrievingClientDetails(INTERNAL_SERVER_ERROR, "Unexpected error during 'getPptSubscriptionDetails'"))
    }
  }

  ".getCbcSubscriptionDetails" should {

    "return CBC subscription details when receiving a 200 status" in {
      givenAuditConnector()
      givenCbcDetailsExist()
      await(connector.getCbcSubscriptionDetails("XACBC1234567890")) shouldBe
        Right(
          SimpleCbcSubscription(
            Some("CFG Solutions"),
            Seq("Erling Haal", "Kevin De Burner"),
            isGBUser = true,
            Seq("test@email.com", "test2@email.com")
          )
        )
    }

    "return a ClientDetailsNotFound error when receiving a 404 status" in {
      givenAuditConnector()
      givenCbcDetailsError(NOT_FOUND)
      await(connector.getCbcSubscriptionDetails("XACBC1234567890")) shouldBe Left(ClientDetailsNotFound)
    }

    "return an ErrorRetrievingClientDetails error when receiving an unexpected status" in {
      givenAuditConnector()
      givenCbcDetailsError(INTERNAL_SERVER_ERROR)
      await(connector.getCbcSubscriptionDetails("XACBC1234567890")) shouldBe
        Left(ErrorRetrievingClientDetails(INTERNAL_SERVER_ERROR, "Unexpected error during 'getCbcSubscriptionDetails'"))
    }
  }

  ".getPillar2SubscriptionDetails" should {

    "return Pillar2 record details when receiving a 200 status" in {
      givenAuditConnector()
      givenPillar2DetailsExist("XAPLR2222222222")
      await(connector.getPillar2SubscriptionDetails("XAPLR2222222222")) shouldBe
        Right(Pillar2Record("CFG Solutions", "2020-01-01", "GB", inactive = true))
    }

    "return a ClientDetailsNotFound error when receiving a 404 status" in {
      givenAuditConnector()
      givenPillar2DetailsError("XAPLR2222222222", NOT_FOUND)
      await(connector.getPillar2SubscriptionDetails("XAPLR2222222222")) shouldBe Left(ClientDetailsNotFound)
    }

    "return an ErrorRetrievingClientDetails error when receiving an unexpected status" in {
      givenAuditConnector()
      givenPillar2DetailsError("XAPLR2222222222", INTERNAL_SERVER_ERROR)
      await(connector.getPillar2SubscriptionDetails("XAPLR2222222222")) shouldBe
        Left(
          ErrorRetrievingClientDetails(INTERNAL_SERVER_ERROR, "Unexpected error during 'getPillar2SubscriptionDetails'")
        )
    }
  }
}
