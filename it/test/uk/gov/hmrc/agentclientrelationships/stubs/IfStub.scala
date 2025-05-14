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

package uk.gov.hmrc.agentclientrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.Nino

trait IfStub {

  def givenReturnsServerError(): StubMapping = stubFor(
    any(urlMatching(s"/registration/.*")).willReturn(aResponse().withStatus(500))
  )

  def givenReturnsServiceUnavailable(): StubMapping = stubFor(
    any(urlMatching(s"/registration/.*")).willReturn(aResponse().withStatus(503))
  )

  def givenNinoIsKnownFor(
    mtdId: MtdItId,
    nino: Nino
  ): StubMapping = stubFor(
    get(urlEqualTo(s"/registration/business-details/mtdId/${mtdId.value}"))
      .willReturn(aResponse().withStatus(200).withBody(s"""{"taxPayerDisplayResponse":{"nino": "${nino.value}" }}"""))
  )

  def givenNinoIsUnknownFor(mtdId: MtdItId): StubMapping = stubFor(
    get(urlEqualTo(s"/registration/business-details/mtdId/${mtdId.value}")).willReturn(aResponse().withStatus(404))
  )

  def givenmtdIdIsInvalid(mtdId: MtdItId): StubMapping = stubFor(
    get(urlMatching(s"/registration/.*?/mtdId/${mtdId.value}")).willReturn(aResponse().withStatus(400))
  )

  def givenMtdItIdIsKnownFor(
    nino: Nino,
    mtdId: MtdItId
  ): StubMapping = stubFor(
    get(urlEqualTo(s"/registration/business-details/nino/${nino.value}"))
      .willReturn(aResponse().withStatus(200).withBody(s"""{"taxPayerDisplayResponse":{"mtdId": "${mtdId.value}" }}"""))
  )

  def givenMtdItIdIsUnKnownFor(nino: Nino): StubMapping = stubFor(
    get(urlEqualTo(s"/registration/business-details/nino/${nino.value}")).willReturn(aResponse().withStatus(404))
  )

  def givenNinoIsInvalid(nino: Nino): StubMapping = stubFor(
    get(urlMatching(s"/registration/.*?/nino/${nino.value}")).willReturn(aResponse().withStatus(400))
  )

  def givenItsaBusinessDetailsExists(
    idType: String,
    id: String,
    mtdId: String = "XAIT0000111122"
  ): StubMapping = stubFor(
    get(urlEqualTo(s"/registration/business-details/$idType/$id")).willReturn(aResponse().withBody(s"""
                                                                                                      |{
                                                                                                      |  "taxPayerDisplayResponse": {
                                                                                                      |    "businessData": [
                                                                                                      |      {
                                                                                                      |        "tradingName": "Erling Haal",
                                                                                                      |        "businessAddressDetails": {
                                                                                                      |          "postalCode": "AA1 1AA",
                                                                                                      |          "countryCode": "GB"
                                                                                                      |        }
                                                                                                      |      }
                                                                                                      |    ],
                                                                                                      |    "mtdId": "$mtdId"
                                                                                                      |  }
                                                                                                      |}
          """.stripMargin))
  )

  def givenMultipleItsaBusinessDetailsExists(nino: String): StubMapping = stubFor(
    get(urlEqualTo(s"/registration/business-details/nino/$nino")).willReturn(aResponse().withBody(s"""
                                                                                                     |{
                                                                                                     |  "taxPayerDisplayResponse": {
                                                                                                     |    "businessData": [
                                                                                                     |      {
                                                                                                     |        "tradingName": "Erling Haal",
                                                                                                     |        "businessAddressDetails": {
                                                                                                     |          "postalCode": "AA1 1AA",
                                                                                                     |          "countryCode": "GB"
                                                                                                     |        }
                                                                                                     |      },
                                                                                                     |      {
                                                                                                     |        "tradingName": "Bernard Silver",
                                                                                                     |        "businessAddressDetails": {
                                                                                                     |          "postalCode": "BB1 1BB",
                                                                                                     |          "countryCode": "PT"
                                                                                                     |        }
                                                                                                     |      }
                                                                                                     |    ]
                                                                                                     |  }
                                                                                                     |}
          """.stripMargin))
  )

  def givenEmptyItsaBusinessDetailsExists(nino: String): StubMapping = stubFor(
    get(urlEqualTo(s"/registration/business-details/nino/$nino")).willReturn(aResponse().withBody(s"""
                                                                                                     |{
                                                                                                     |  "taxPayerDisplayResponse": {
                                                                                                     |    "businessData": []
                                                                                                     |  }
                                                                                                     |}
          """.stripMargin))
  )

  def givenItsaBusinessDetailsError(
    nino: String,
    status: Int
  ): StubMapping = stubFor(
    get(urlEqualTo(s"/registration/business-details/nino/$nino")).willReturn(aResponse().withStatus(status))
  )

}
