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
import uk.gov.hmrc.domain.Nino
object DesStubs
extends DesStubs
trait DesStubs {

  def givenDesReturnsServiceUnavailable(): StubMapping = stubFor(
    any(urlMatching(s"/registration/.*")).willReturn(aResponse().withStatus(503))
  )

  def givenDesReturnsServerError(): StubMapping = stubFor(
    any(urlMatching(s"/registration/.*")).willReturn(aResponse().withStatus(500))
  )

  val someAlienAgent = """{"hasAgent":false,"agentId":"alien"}"""
  val someCeasedAgent = """{"hasAgent":true,"agentId":"ex-agent","agentCeasedDate":"someDate"}"""

  private def request(nino: NinoWithoutSuffix) = get(urlEqualTo(s"/registration/relationship/nino/${nino.rawValue}"))
    .withHeader("Authorization", equalTo("Bearer secret"))
    .withHeader("Environment", equalTo("test"))
    .withHeader("CorrelationId", equalTo("testCorrelationId"))

  def givenClientHasRelationshipWithAgentInCESA(
    nino: NinoWithoutSuffix,
    agentId: String
  ): StubMapping = stubFor(
    request(nino).willReturn(
      aResponse()
        .withStatus(200)
        .withBody(s"""{"agents":[$someCeasedAgent,{"hasAgent":true,"agentId":"$agentId"}, $someAlienAgent]}""")
    )
  )

  def givenClientHasRelationshipWithMultipleAgentsInCESA(
    nino: NinoWithoutSuffix,
    agentIds: Seq[String]
  ): StubMapping = stubFor(
    request(nino).willReturn(
      aResponse()
        .withStatus(200)
        .withBody(s"""{"agents":[${agentIds
            .map(id => s"""{"hasAgent":true,"agentId":"$id"}""")
            .mkString(",")}, $someAlienAgent, $someCeasedAgent ]}""")
    )
  )

  def givenClientRelationshipWithAgentCeasedInCESA(
    nino: NinoWithoutSuffix,
    agentId: String
  ): StubMapping = stubFor(
    request(nino).willReturn(
      aResponse()
        .withStatus(200)
        .withBody(s"""{"agents":[{"hasAgent":true,"agentId":"$agentId","agentCeasedDate":"2010-01-01"}]}""")
    )
  )

  def givenAllClientRelationshipsWithAgentsCeasedInCESA(
    nino: NinoWithoutSuffix,
    agentIds: Seq[String]
  ): StubMapping = stubFor(
    request(nino).willReturn(
      aResponse()
        .withStatus(200)
        .withBody(s"""{"agents":[${agentIds
            .map(id => s"""{"hasAgent":true,"agentId":"$id","agentCeasedDate":"2020-12-31"}""")
            .mkString(",")}]}""")
    )
  )

  def givenClientHasNoActiveRelationshipWithAgentInCESA(nino: NinoWithoutSuffix): StubMapping = stubFor(
    request(nino)
      .willReturn(aResponse().withStatus(200).withBody(s"""{"agents":[$someCeasedAgent, $someAlienAgent]}"""))
  )

  def givenClientHasNoRelationshipWithAnyAgentInCESA(nino: NinoWithoutSuffix): StubMapping = stubFor(
    request(nino)
      .willReturn(aResponse().withStatus(200).withBody(s"""{}"""))
  )

  def givenClientIsUnknownInCESAFor(nino: NinoWithoutSuffix): StubMapping = stubFor(
    request(nino)
      .willReturn(aResponse().withStatus(404).withBody("""{"code":"NOT_FOUND_NINO","message":"NINO not found"}"""))
  )

  def givenClientIsUnknownInCESAForAllVariants(nino: NinoWithoutSuffix): Unit = (Nino.validSuffixes :+ "").map(suffix =>
    NinoWithoutSuffix(nino.value + suffix)
  ).foreach { ninoVariant =>
    stubFor(
      request(ninoVariant)
        .willReturn(aResponse().withStatus(404).withBody("""{"code":"NOT_FOUND_NINO","message":"NINO not found"}"""))
    )
  }

  def givenNinoIsInvalid(nino: NinoWithoutSuffix): StubMapping = stubFor(
    get(urlMatching(s"/registration/relationship/nino/${nino.anySuffixValue}")).willReturn(aResponse().withStatus(400))
  )

  def givenVatCustomerInfoExists(
    vrn: String,
    regDate: String = "2020-01-01",
    isInsolvent: Boolean = false
  ): StubMapping = stubFor(
    get(urlEqualTo(s"/vat/customer/vrn/$vrn/information"))
      .willReturn(
        aResponse()
          .withBody(s"""
                       |{
                       |  "approvedInformation": {
                       |    "customerDetails": {
                       |      "organisationName": "CFG",
                       |      "tradingName": "CFG Solutions",
                       |      "individual": {
                       |        "title": "0001",
                       |        "firstName": "Ilkay",
                       |        "middleName": "Silky",
                       |        "lastName": "Gundo"
                       |      },
                       |      "effectiveRegistrationDate": "$regDate",
                       |      "isInsolvent": $isInsolvent,
                       |      "overseasIndicator": true
                       |    }
                       |  }
                       |}
          """.stripMargin)
      )
  )

  def givenVatCustomerInfoError(
    vrn: String,
    status: Int
  ): StubMapping = stubFor(
    get(urlEqualTo(s"/vat/customer/vrn/$vrn/information"))
      .willReturn(aResponse().withStatus(status))
  )

  def givenCgtDetailsExist(cgtRef: String): StubMapping = stubFor(
    get(urlEqualTo(s"/subscriptions/CGT/ZCGT/$cgtRef"))
      .willReturn(
        aResponse()
          .withBody(s"""
                       |{
                       |  "subscriptionDetails": {
                       |    "typeOfPersonDetails": {
                       |      "typeOfPerson": "Trustee",
                       |      "organisationName": "CFG Solutions"
                       |    },
                       |    "addressDetails": {
                       |      "postalCode": "AA1 1AA",
                       |      "countryCode": "GB"
                       |    }
                       |  }
                       |}
            """.stripMargin)
      )
  )

  def givenCgtDetailsError(
    cgtRef: String,
    status: Int
  ): StubMapping = stubFor(
    get(urlEqualTo(s"/subscriptions/CGT/ZCGT/$cgtRef"))
      .willReturn(aResponse().withStatus(status))
  )

}
