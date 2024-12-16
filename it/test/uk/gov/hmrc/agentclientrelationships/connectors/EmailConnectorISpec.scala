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

import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, equalToJson, post, stubFor, urlEqualTo}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test.Helpers.await
import uk.gov.hmrc.agentclientrelationships.model.EmailInformation
import uk.gov.hmrc.agentclientrelationships.support.{UnitSpec, WireMockSupport}
import play.api.test.Helpers._
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global

class EmailConnectorISpec extends UnitSpec with GuiceOneServerPerSuite with WireMockSupport {

  override lazy val app: Application = appBuilder.build()

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.email.port" -> wireMockPort,
        "auditing.consumer.baseUri.host"   -> wireMockHost,
        "auditing.consumer.baseUri.port"   -> wireMockPort,
        "internal-auth.token"              -> "internalAuthToken"
      )

  val connector = app.injector.instanceOf[EmailConnector]

  val arn = "TARN0000001"
  val nino = "AB123456A"
  val mtdItId = "LC762757D"
  val vrn = "101747641"

  def givenEmailSent(emailInformation: EmailInformation) = {
    val emailInformationJson = Json.toJson(emailInformation).toString()

    stubFor(
      post(urlEqualTo("/hmrc/email"))
        .withRequestBody(similarToJson(emailInformationJson))
        .willReturn(aResponse().withStatus(202))
    )
  }

  def givenEmailReturns500 =
    stubFor(
      post(urlEqualTo("/hmrc/email"))
        .willReturn(aResponse().withStatus(500))
    )

  private def similarToJson(value: String) = equalToJson(value.stripMargin, true, true)

  implicit val hc: HeaderCarrier = HeaderCarrier()

  "sendEmail" should {
    val emailInfo = EmailInformation(Seq("abc@xyz.com"), "template-id", Map("param1" -> "foo", "param2" -> "bar"))

    "return Unit when the email service responds" in {

      givenEmailSent(emailInfo)

      val result = await(connector.sendEmail(emailInfo))

      result shouldBe ()
    }
    "not throw an Exception when the email service throws an Exception" in {
      givenEmailReturns500

      val result = await(connector.sendEmail(emailInfo))

      result shouldBe ()
    }
  }
}
