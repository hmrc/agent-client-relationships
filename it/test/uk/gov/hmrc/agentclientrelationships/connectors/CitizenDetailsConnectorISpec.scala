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
import play.api.test.Helpers.await
import play.api.test.Helpers.defaultAwaitTimeout
import uk.gov.hmrc.agentclientrelationships.model.CitizenDetails
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix
import uk.gov.hmrc.agentclientrelationships.stubs.CitizenDetailsStub
import uk.gov.hmrc.agentclientrelationships.stubs.DataStreamStub
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentclientrelationships.support.WireMockSupport

import java.time.LocalDate

class CitizenDetailsConnectorISpec
extends UnitSpec
with GuiceOneServerPerSuite
with WireMockSupport
with DataStreamStub
with CitizenDetailsStub {

  override lazy val app: Application = appBuilder.build()

  protected def appBuilder: GuiceApplicationBuilder = new GuiceApplicationBuilder().configure(
    "microservice.services.citizen-details.port" -> wireMockPort,
    "auditing.consumer.baseUri.host" -> wireMockHost,
    "auditing.consumer.baseUri.port" -> wireMockPort
  )

  implicit val request: RequestHeader = FakeRequest()

  val connector: CitizenDetailsConnector = app.injector.instanceOf[CitizenDetailsConnector]
  val testNino: NinoWithoutSuffix = NinoWithoutSuffix("AA000001B")

  ".getCitizenDetails" should {

    "return citizen details when receiving a 200 status" in {
      givenAuditConnector()
      givenCitizenDetailsExists(testNino)
      val expectedModel = CitizenDetails(
        Some("Matthew"),
        Some("Kovacic"),
        Some(LocalDate.parse("2000-01-01")),
        Some("11223344")
      )
      await(connector.getCitizenDetails(testNino)) shouldBe expectedModel
    }
  }

}
