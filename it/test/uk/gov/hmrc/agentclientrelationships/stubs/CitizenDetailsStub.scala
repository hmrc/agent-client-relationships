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
import uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix

trait CitizenDetailsStub {

  def givenCitizenDetailsExists(nino: NinoWithoutSuffix): StubMapping = stubFor(
    get(urlEqualTo(s"/citizen-details/nino-no-suffix/${nino.value}"))
      .willReturn(
        aResponse()
          .withBody(
            // language=JSON
            s"""
             {
               "name": {
                 "current": {
                   "firstName": "Matthew",
                   "lastName": "Kovacic"
                 }
               },
               "dateOfBirth": "01012000",
               "ids": {
                 "sautr": "11223344"
               }
             }
            """.stripMargin
          )
      )
  )

  def givenCitizenDetailsHasNoName(nino: NinoWithoutSuffix): StubMapping = stubFor(
    get(urlEqualTo(s"/citizen-details/nino-no-suffix/${nino.value}"))
      .willReturn(
        aResponse()
          .withBody(
            // language=JSON
            s"""
            {
              "dateOfBirth": "01012000",
              "ids": {
                "sautr": "11223344"
              }
            }
            """.stripMargin
          )
      )
  )

  def givenCitizenDetailsError(
    nino: NinoWithoutSuffix,
    status: Int
  ): StubMapping = stubFor(
    get(urlEqualTo(s"/citizen-details/nino-no-suffix/${nino.value}"))
      .willReturn(aResponse().withStatus(status))
  )

}
