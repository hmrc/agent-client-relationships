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

trait IFStubs {

  def givenNinoIsKnownFor(mtdId: MtdItId, nino: Nino): StubMapping =
    stubFor(
      get(urlEqualTo(s"/registration/business-details/mtdId/${mtdId.value}"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"taxPayerDisplayResponse":{"nino": "${nino.value}" }}""")
        )
    )

  def givenNinoIsUnknownFor(mtdId: MtdItId): StubMapping =
    stubFor(
      get(urlEqualTo(s"/registration/business-details/mtdId/${mtdId.value}"))
        .willReturn(aResponse().withStatus(404))
    )

  def givenmtdIdIsInvalid(mtdId: MtdItId): StubMapping =
    stubFor(
      get(urlMatching(s"/registration/.*?/mtdId/${mtdId.value}"))
        .willReturn(aResponse().withStatus(400))
    )

  def givenMtdItIdIsKnownFor(nino: Nino, mtdId: MtdItId): StubMapping =
    stubFor(
      get(urlEqualTo(s"/registration/business-details/nino/${nino.value}"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""{"taxPayerDisplayResponse":{"mtdId": "${mtdId.value}" }}""")
        )
    )

  def givenMtdItIdIsUnKnownFor(nino: Nino): StubMapping =
    stubFor(
      get(urlEqualTo(s"/registration/business-details/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(404))
    )

  def givenNinoIsInvalid(nino: Nino): StubMapping =
    stubFor(
      get(urlMatching(s"/registration/.*?/nino/${nino.value}"))
        .willReturn(aResponse().withStatus(400))
    )
}
