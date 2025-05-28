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
import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.support.WireMockSupport
import uk.gov.hmrc.agentmtdidentifiers.model._

trait DesStubsGet {

  me: WireMockSupport =>

  def getVrnIsKnownInETMPFor(vrn: Vrn): StubMapping = stubFor(
    get(urlEqualTo(s"/vat/customer/vrn/${vrn.value}/information"))
      .willReturn(aResponse().withBody(s"""{ "vrn": "${vrn.value}"}""").withStatus(200))
  )

  def getVrnIsKnownInETMPFor2(vrn: Vrn): StubMapping = stubFor(
    get(urlEqualTo(s"/vat/customer/vrn/${vrn.value}/information")).willReturn(
      aResponse()
        .withBody(
          Json
            .obj(
              "approvedInformation" -> Json.obj(
                "customerDetails" -> Json.obj(
                  "organisationName" -> "CFG",
                  "tradingName" -> "CFG Solutions",
                  "individual" -> Json
                    .obj(
                      "title" -> "0001",
                      "firstName" -> "Ilkay",
                      "middleName" -> "Silky",
                      "lastName" -> "Gundo"
                    ),
                  "effectiveRegistrationDate" -> "2020-01-01",
                  "isInsolvent" -> false
                )
              )
            )
            .toString()
        )
        .withStatus(200)
    )
  )

  def getVrnIsNotKnownInETMPFor(vrn: Vrn): StubMapping = stubFor(
    get(urlEqualTo(s"/vat/customer/vrn/${vrn.value}/information"))
      .willReturn(aResponse().withBody(s"""{}""").withStatus(200))
  )

  def givenDESRespondsWithStatusForVrn(
    vrn: Vrn,
    status: Int
  ): StubMapping = stubFor(
    get(urlEqualTo(s"/vat/customer/vrn/${vrn.value}/information")).willReturn(aResponse().withStatus(status))
  )

}
