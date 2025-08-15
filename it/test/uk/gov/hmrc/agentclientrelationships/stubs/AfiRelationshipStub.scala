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
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn

import java.time.LocalDateTime

trait AfiRelationshipStub {

  def givenAfiRelationshipIsActive(
    arn: Arn,
    service: String,
    clientId: String,
    fromCesa: Boolean
  ) = stubFor(
    get(urlEqualTo(s"/agent-fi-relationship/relationships/agent/${arn.value}/service/$service/client/$clientId"))
      .willReturn(
        aResponse()
          .withStatus(200)
          .withBody(s"""
                       |[{
                       |  "arn" : "${arn.value}",
                       |  "service" : "$service",
                       |  "clientId" : "$clientId",
                       |  "relationshipStatus" : "ACTIVE",
                       |  "startDate" : "2017-12-08T15:21:51.040",
                       |  "fromCesa" : $fromCesa
                       |}]""".stripMargin)
      )
  )

  def givenAfiRelationshipForClientIsActive(
    arn: Arn,
    service: String,
    clientId: String,
    fromCesa: Boolean
  ) = stubFor(
    get(urlEqualTo(s"/agent-fi-relationship/relationships/service/PERSONAL-INCOME-RECORD/clientId/$clientId"))
      .willReturn(
        aResponse()
          .withStatus(200)
          .withBody(s"""
                       |[{
                       |  "arn" : "${arn.value}",
                       |  "service" : "$service",
                       |  "clientId" : "$clientId",
                       |  "relationshipStatus" : "ACTIVE",
                       |  "startDate" : "2017-12-08T15:21:51.040",
                       |  "fromCesa" : $fromCesa
                       |}]""".stripMargin)
      )
  )

  def givenAfiRelationshipForClientNotFound(
    clientId: String,
    status: Int = 404
  ): StubMapping = stubFor(
    get(urlEqualTo(s"/agent-fi-relationship/relationships/service/PERSONAL-INCOME-RECORD/clientId/$clientId"))
      .willReturn(aResponse().withStatus(status))
  )

  def givenTerminateAfiRelationshipSucceeds(
    arn: Arn,
    service: String,
    clientId: String
  ) = stubFor(
    delete(urlEqualTo(s"/agent-fi-relationship/relationships/agent/${arn.value}/service/$service/client/$clientId"))
      .willReturn(aResponse().withStatus(200))
  )

  def givenTerminateAfiRelationshipFails(
    arn: Arn,
    service: String,
    clientId: String,
    status: Int = 500
  ) = stubFor(
    delete(urlEqualTo(s"/agent-fi-relationship/relationships/agent/${arn.value}/service/$service/client/$clientId"))
      .willReturn(aResponse().withStatus(status))
  )

  def givenCreateAfiRelationshipSucceeds(
    arn: Arn,
    service: String,
    clientId: String
  ) = stubFor(
    put(urlEqualTo(s"/agent-fi-relationship/relationships/agent/${arn.value}/service/$service/client/$clientId"))
      .willReturn(aResponse().withStatus(201))
  )

  def givenCreateAfiRelationshipFails(
    arn: Arn,
    service: String,
    clientId: String
  ) = stubFor(
    put(urlEqualTo(s"/agent-fi-relationship/relationships/agent/${arn.value}/service/$service/client/$clientId"))
      .willReturn(aResponse().withStatus(500))
  )

  def givenTestOnlyCreateAfiRelationshipFails(
    arn: Arn,
    service: String,
    clientId: String
  ) = stubFor(
    put(
      urlEqualTo(s"/agent-fi-relationship/test-only/relationships/agent/${arn.value}/service/$service/client/$clientId")
    ).willReturn(aResponse().withStatus(500))
  )

  def givenInactiveAfiRelationship(arn: Arn) = stubFor(
    get(urlEqualTo(s"/agent-fi-relationship/relationships/inactive")).willReturn(
      aResponse()
        .withStatus(200)
        .withBody(s"""
                     |[{
                     |   "arn":"${arn.value}",
                     |   "endDate":"2015-09-21T15:21:51.040",
                     |   "clientId":"AB123456A"
                     |},
                     |{  "arn":"${arn.value}",
                     |   "endDate":"2018-09-24T15:21:51.040",
                     |   "clientId":"GZ753451B"
                     |}]""".stripMargin)
    )
  )

  def given2InactiveAfiRelationships(
    endDate1: LocalDateTime,
    endDate2: LocalDateTime
  ) = stubFor(
    get(urlEqualTo(s"/agent-fi-relationship/relationships/inactive")).willReturn(
      aResponse()
        .withStatus(200)
        .withBody(s"""
                     |[{
                     |   "arn":"TARN0000001",
                     |   "endDate":"${endDate1.toString}",
                     |   "clientId":"AB123456A"
                     |},
                     |{  "arn":"TARN0000001",
                     |   "endDate":"${endDate2.toString}",
                     |   "clientId":"GZ753451B"
                     |}]""".stripMargin)
    )
  )

  def givenInactiveAfiRelationshipNotFound = stubFor(
    get(urlEqualTo(s"/agent-fi-relationship/relationships/inactive")).willReturn(aResponse().withStatus(404))
  )

}
