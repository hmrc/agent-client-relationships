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
import com.github.tomakehurst.wiremock.matching.MatchResult
import com.github.tomakehurst.wiremock.matching.UrlPattern
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import org.scalatest.concurrent.Eventually
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import play.api.http.Status
import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.domain.TaxIdentifier

trait EnrolmentStoreProxyStubs
extends Eventually {

  private implicit val patience: PatienceConfig = PatienceConfig(scaled(Span(2, Seconds)), scaled(Span(50, Millis)))

  private val esBaseUrl = s"/enrolment-store-proxy/enrolment-store"
  private val teBaseUrl = s"/tax-enrolments"

  def agentEnrolmentKey(arn: Arn): EnrolmentKey = EnrolmentKey(s"HMRC-AS-AGENT~AgentReferenceNumber~${arn.value}")

  // ES1
  def givenPrincipalGroupIdExistsFor(
    enrolmentKey: EnrolmentKey,
    groupId: String
  ): StubMapping = stubFor(
    get(urlEqualTo(s"$esBaseUrl/enrolments/${enrolmentKey.tag}/groups?type=principal")).willReturn(
      aResponse().withBody(s"""
                              |{
                              |    "principalGroupIds":[
                              |        "$groupId"
                              |    ]
                              |}
          """.stripMargin)
    )
  )

  def givenPrincipalGroupIdNotExistsFor(enrolmentKey: EnrolmentKey): StubMapping = stubFor(
    get(urlEqualTo(s"$esBaseUrl/enrolments/${enrolmentKey.tag}/groups?type=principal")).willReturn(
      aResponse().withStatus(204)
    )
  )

  private def urlContains(str: String): UrlPattern =
    new UrlPattern(containing(str), false) {
      override def `match`(url: String): MatchResult = pattern.`match`(url)
    }

  def givenPrincipalGroupIdRequestFailsWith(status: Int): StubMapping = stubFor(
    get(urlContains("/groups?type=principal")).willReturn(aResponse().withStatus(status).withBody("FAILED_ESP"))
  )

  def givenDelegatedGroupIdsExistFor(
    enrolmentKey: EnrolmentKey,
    groupIds: Set[String]
  ): StubMapping = stubFor(
    get(
      urlEqualTo(
        s"$esBaseUrl/enrolments/${enrolmentKey.tag}/groups?type=delegated"
      )
    ).willReturn(
      aResponse().withBody(s"""
                              |{
                              |    "delegatedGroupIds":[
                              |        ${groupIds.map(s => s""""$s\"""").mkString(",")}
                              |    ]
                              |}
          """.stripMargin)
    )
  )

  def givenDelegatedGroupIdsNotExistFor(enrolmentKey: EnrolmentKey): StubMapping = stubFor(
    get(urlEqualTo(s"$esBaseUrl/enrolments/${enrolmentKey.tag}/groups?type=delegated")).willReturn(
      aResponse().withStatus(204)
    )
  )

  // this stub seems to match completely unrelated requests
  def givenDelegatedGroupIdRequestFailsWith(status: Int): StubMapping = stubFor(
    get(urlContains("/groups?type=delegated")).willReturn(aResponse().withStatus(status))
  )

  def givenDelegatedGroupIdRequestFailsWith(
    enrolmentKey: EnrolmentKey,
    status: Int
  ): StubMapping = stubFor(
    get(urlEqualTo(s"$esBaseUrl/enrolments/${enrolmentKey.tag}/groups?type=delegated")).willReturn(aResponse().withStatus(status))
  )

  def givenPrincipalUserIdExistFor(
    enrolmentKey: EnrolmentKey,
    userId: String
  ): StubMapping = givenPrincipalUserIdsExistFor(enrolmentKey, List(userId))

  def givenPrincipalUserIdsExistFor(
    enrolmentKey: EnrolmentKey,
    userIds: Seq[String]
  ): StubMapping = stubFor(
    get(urlEqualTo(s"$esBaseUrl/enrolments/${enrolmentKey.tag}/users?type=principal")).willReturn(
      aResponse().withBody(s"""
                              |{
                              |    "principalUserIds":[
                              |        ${userIds.map(x => s"\"$x\"").mkString(", ")}
                              |    ]
                              |}
          """.stripMargin)
    )
  )

  def givenPrincipalUserIdNotExistFor(enrolmentKey: EnrolmentKey): StubMapping = stubFor(
    get(urlEqualTo(s"$esBaseUrl/enrolments/${enrolmentKey.tag}/users?type=principal")).willReturn(
      aResponse().withStatus(204)
    )
  )

  def givenEnrolmentAllocationSucceeds(
    groupId: String,
    clientUserId: String,
    enrolmentKey: EnrolmentKey,
    agentCode: String
  ): StubMapping = stubFor(
    post(urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/${enrolmentKey.tag}?legacy-agentCode=$agentCode"))
      .withRequestBody(similarToJson(s"""
                                        |{
                                        |   "userId":"$clientUserId",
                                        |   "type":"delegated"
                                        |}
                                        |""".stripMargin))
      .withHeader("Content-Type", containing("application/json"))
      .willReturn(aResponse().withStatus(201))
  )

  def verifyEnrolmentAllocationAttempt(
    groupId: String,
    clientUserId: String,
    enrolmentKey: EnrolmentKey,
    agentCode: String,
    count: Int = 1
  ): Unit = eventually {
    verify(
      count,
      postRequestedFor(
        urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/${enrolmentKey.tag}?legacy-agentCode=$agentCode")
      ).withRequestBody(similarToJson(s"""
                                         |{
                                         |   "userId":"$clientUserId",
                                         |   "type":"delegated"
                                         |}
                                         |""".stripMargin))
    )
  }

  def verifyNoEnrolmentHasBeenAllocated(): Unit = eventually {
    verify(0, postRequestedFor(urlContains(s"$teBaseUrl/groups/")))
  }

  def givenEnrolmentAllocationFailsWith(
    responseStatus: Int
  )(
    groupId: String,
    clientUserId: String,
    enrolmentKey: EnrolmentKey,
    agentCode: String
  ): StubMapping = stubFor(
    post(urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/${enrolmentKey.tag}?legacy-agentCode=$agentCode"))
      .withRequestBody(similarToJson(s"""
                                        |{
                                        |   "userId":"$clientUserId",
                                        |   "type":"delegated"
                                        |}
                                        |""".stripMargin))
      .willReturn(aResponse().withStatus(responseStatus))
  )

  def verifyEnrolmentDeallocationAttempt(
    groupId: String,
    enrolmentKey: EnrolmentKey
  ): Unit = eventually {
    verify(1, deleteRequestedFor(urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/${enrolmentKey.tag}")))
  }

  def verifyNoEnrolmentHasBeenDeallocated(): Unit = eventually {
    verify(0, deleteRequestedFor(urlContains(s"$teBaseUrl/groups/")))
  }

  def givenEnrolmentDeallocationSucceeds(
    groupId: String,
    enrolmentKey: EnrolmentKey
  ): StubMapping = stubFor(
    delete(urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/${enrolmentKey.tag}"))
      .willReturn(aResponse().withStatus(204))
  )

  def givenEnrolmentDeallocationFailsWith(
    responseStatus: Int
  )(
    groupId: String,
    enrolmentKey: EnrolmentKey
  ): StubMapping = stubFor(
    delete(urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/${enrolmentKey.tag}"))
      .willReturn(aResponse().withStatus(responseStatus))
  )

  def givenEsIsUnavailable(): StubMapping = {
    stubFor(
      any(urlMatching(s"$esBaseUrl/.*")).willReturn(aResponse().withStatus(503))
    )
    stubFor(
      any(urlMatching(s"$teBaseUrl/.*")).willReturn(aResponse().withStatus(503))
    )
  }

  def givenEnrolmentExistsForGroupId(
    groupId: String,
    enrolmentKey: EnrolmentKey
  ): StubMapping = stubFor(
    get(urlEqualTo(s"$esBaseUrl/groups/$groupId/enrolments?type=principal")).willReturn(
      aResponse()
        .withStatus(200)
        .withBody(s"""
                     |{
                     |    "startRecord": 1,
                     |    "totalRecords": 1,
                     |    "enrolments": [
                     |        {
                     |           "service": "${enrolmentKey.service}",
                     |           "state": "active",
                     |           "friendlyName": "My First Client's PAYE Enrolment",
                     |           "enrolmentDate": "2018-10-05T14:48:00.000Z",
                     |           "failedActivationCount": 1,
                     |           "activationDate": "2018-10-13T17:36:00.000Z",
                     |           "identifiers": [
                     |              {
                     |                 "key": "${enrolmentKey.oneIdentifier().key}",
                     |                 "value": "${enrolmentKey.oneIdentifier().value}"
                     |              }
                     |           ]
                     |        },
                     |        {
                     |           "service": "HMCE-VAT-AGNT",
                     |           "state": "active",
                     |           "friendlyName": "My Client's VAT Enrolment",
                     |           "enrolmentDate": "2018-10-05T14:48:00.000Z",
                     |           "failedActivationCount": 1,
                     |           "activationDate": "2018-10-13T17:36:00.000Z",
                     |           "identifiers": [
                     |              {
                     |                 "key": "HMCE-VAT-AGNT~AgentRefNo~123456789",
                     |                 "value": "123456789"
                     |              }
                     |           ]
                     |        }
                     |    ]
                     |}
             """.stripMargin)
    )
  )

  def givenEnrolmentNotExistsForGroupId(groupId: String): StubMapping = stubFor(
    get(urlEqualTo(s"$esBaseUrl/groups/$groupId/enrolments?type=principal")).willReturn(
      aResponse().withStatus(204)
        .withBody("")
    )
  )

  // ES19 - updateEnrolmentFriendlyName
  def givenUpdateEnrolmentFriendlyNameResponse(
    groupId: String,
    enrolment: String,
    responseStatus: Int
  ): StubMapping = stubFor(
    put(urlEqualTo(s"$esBaseUrl/groups/$groupId/enrolments/$enrolment/friendly_name")).willReturn(
      aResponse().withStatus(responseStatus)
    )
  )

  // ES20 - QueryKnownFactsByVerifiersAndIdentifiers
  def givenKnownFactsQuery(
    service: Service,
    taxIdentifier: TaxIdentifier,
    expectedIdentifiers: Option[Seq[Identifier]]
  ): StubMapping = {
    val response =
      expectedIdentifiers match {
        case None => aResponse().withStatus(Status.NO_CONTENT)
        case Some(identifiers) =>
          aResponse()
            .withStatus(Status.OK)
            .withBody(s"""
                         |{
                         |    "service": "${service.id}",
                         |    "enrolments": [
                         |        {
                         |            "identifiers": ${Json.prettyPrint(Json.toJson(identifiers))},
                         |            "verifiers": [
                         |                {
                         |                    "key": "Email",
                         |                    "value": "placeholder.email@gov.uk"
                         |                }
                         |            ]
                         |        }
                         |    ]
                         |}
                         |""".stripMargin)
      }
    stubFor(
      post(urlEqualTo(s"$esBaseUrl/enrolments"))
        .withRequestBody(similarToJson(s"""
                                          |{
                                          |    "service": "${service.id}",
                                          |    "knownFacts": [
                                          |        {
                                          |            "key": "${ClientIdentifier(taxIdentifier).enrolmentId}",
                                          |            "value": "${taxIdentifier.value}"
                                          |        }
                                          |    ]
                                          |}
             """.stripMargin))
        .willReturn(response)
    )
  }

  def givenCbcUkExistsInES(
    cbcId: CbcId,
    expectedUtr: String
  ): StubMapping = givenKnownFactsQuery(
    Service.Cbc,
    cbcId,
    Some(Seq(Identifier("cbcId", cbcId.value), Identifier("UTR", expectedUtr)))
  )
  def givenCbcUkDoesNotExistInES(cbcId: CbcId): StubMapping = givenKnownFactsQuery(
    Service.Cbc,
    cbcId,
    None
  )
  def givenCbcNonUkExistsInES(cbcId: CbcId): StubMapping = givenKnownFactsQuery(
    Service.CbcNonUk,
    cbcId,
    Some(Seq(Identifier("cbcId", cbcId.value)))
  )
  def givenCbcNonUkDoesNotExistInES(cbcId: CbcId): StubMapping = givenKnownFactsQuery(
    Service.CbcNonUk,
    cbcId,
    None
  )

  def givenGetAgentReferenceNumberFor(
    groupId: String,
    arn: String
  ): StubMapping = stubFor(
    get(
      urlEqualTo(
        s"$esBaseUrl/groups/$groupId/enrolments?type=principal"
      )
    ).willReturn(
      aResponse()
        .withStatus(200)
        .withBody(s"""
                     |{
                     |    "startRecord": 1,
                     |    "totalRecords": 1,
                     |    "enrolments": [
                     |        {
                     |           "service": "HMRC-AS-AGENT",
                     |           "state": "active",
                     |           "friendlyName": "anyName",
                     |           "enrolmentDate": "2018-10-05T14:48:00.000Z",
                     |           "failedActivationCount": 1,
                     |           "activationDate": "2018-10-13T17:36:00.000Z",
                     |           "identifiers": [
                     |              {
                     |                 "key": "AgentReferenceNumber",
                     |                 "value": "$arn"
                     |              }
                     |           ]
                     |        }
                     |    ]
                     |}
             """.stripMargin)
    )
  )

  private def similarToJson(value: String) = equalToJson(
    value.stripMargin,
    true,
    true
  )

}
