/*
 * Copyright 2025 HM Revenue & Customs
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
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.model.EmailInformation
import uk.gov.hmrc.agentclientrelationships.stubs.EmailStubs
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentclientrelationships.support.WireMockSupport
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest

class EmailConnectorISpec
extends UnitSpec
with GuiceOneServerPerSuite
with WireMockSupport
with EmailStubs {

  override lazy val app: Application = appBuilder.build()

  protected def appBuilder: GuiceApplicationBuilder = new GuiceApplicationBuilder().configure(
    "microservice.services.email.port" -> wireMockPort,
    "auditing.consumer.baseUri.host" -> wireMockHost,
    "auditing.consumer.baseUri.port" -> wireMockPort,
    "internal-auth.token" -> "internalAuthToken"
  )

  val connector: EmailConnector = app.injector.instanceOf[EmailConnector]

  implicit val request: RequestHeader = FakeRequest()

  "sendEmail" should {
    val emailInfo = EmailInformation(
      Seq("abc@xyz.com"),
      "template-id",
      Map("param1" -> "foo", "param2" -> "bar")
    )

    "return true when the email service responds with a 202" in {
      givenEmailSent(emailInfo)

      val result = await(connector.sendEmail(emailInfo))

      result shouldBe true
    }

    "return false when the email service responds with an unexpected status" in {
      givenEmailSent(emailInfo, SERVICE_UNAVAILABLE)

      val result = await(connector.sendEmail(emailInfo))

      result shouldBe false
    }
  }

}
