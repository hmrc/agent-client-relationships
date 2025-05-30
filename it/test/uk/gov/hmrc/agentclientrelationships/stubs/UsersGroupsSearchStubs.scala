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
import uk.gov.hmrc.agentclientrelationships.connectors.GroupInfo
import uk.gov.hmrc.agentclientrelationships.connectors.UserDetails
import uk.gov.hmrc.domain.AgentCode

trait UsersGroupsSearchStubs {

  private val ugsBaseUrl = s"/users-groups-search"

  def givenGroupInfo(
    groupId: String,
    agentCode: String
  ) = {
    val groupInfo = GroupInfo(
      groupId,
      Some("Agent"),
      Some(AgentCode(agentCode))
    )
    stubFor(
      get(urlEqualTo(s"$ugsBaseUrl/groups/$groupId"))
        .willReturn(aResponse().withBody(GroupInfo.formats.writes(groupInfo).toString()))
    )
  }

  def givenGroupInfoNoAgentCode(groupId: String) = {
    val groupInfo = GroupInfo(
      groupId,
      Some("Agent"),
      None
    )
    stubFor(
      get(urlEqualTo(s"$ugsBaseUrl/groups/$groupId"))
        .willReturn(aResponse().withBody(GroupInfo.formats.writes(groupInfo).toString()))
    )
  }

  def givenGroupInfoNotExists(groupId: String) = stubFor(
    get(urlEqualTo(s"$ugsBaseUrl/groups/$groupId")).willReturn(aResponse().withStatus(404))
  )

  private def urlContains(str: String): UrlPattern =
    new UrlPattern(containing(str), false) {
      override def `match`(url: String): MatchResult = pattern.`match`(url)
    }

  def givenGroupInfoFailsWith(status: Int) = stubFor(
    get(urlContains(s"$ugsBaseUrl/groups/")).willReturn(aResponse().withStatus(status))
  )

  def givenAgentGroupExistsFor(groupId: String): StubMapping = stubFor(
    get(urlEqualTo(s"/users-groups-search/groups/$groupId")).willReturn(aResponse().withBody(s"""
                                                                                                |{
                                                                                                |  "_links": [
                                                                                                |    { "rel": "users", "link": "/groups/$groupId/users" }
                                                                                                |  ],
                                                                                                |  "groupId": "foo",
                                                                                                |  "affinityGroup": "Agent",
                                                                                                |  "agentCode": "NQJUEJCWT14",
                                                                                                |  "agentFriendlyName": "JoeBloggs"
                                                                                                |}
          """.stripMargin))
  )

  def givenNonAgentGroupExistsFor(groupId: String): StubMapping = stubFor(
    get(urlEqualTo(s"/users-groups-search/groups/$groupId")).willReturn(aResponse().withBody(s"""
                                                                                                |{
                                                                                                |  "_links": [
                                                                                                |    { "rel": "users", "link": "/groups/$groupId/users" }
                                                                                                |  ],
                                                                                                |  "groupId": "foo",
                                                                                                |  "affinityGroup": "Organisation"
                                                                                                |}
          """.stripMargin))
  )

  def givenGroupNotExistsFor(groupId: String) = stubFor(
    get(urlEqualTo(s"/users-groups-search/groups/$groupId")).willReturn(aResponse().withStatus(404))
  )

  def givenAdminUser(
    groupId: String,
    userId: String
  ) = givenAgentGroupWithUsers(
    groupId,
    List(UserDetails(userId = Some(userId), credentialRole = Some("Admin")))
  )

  def givenAgentGroupWithUsers(
    groupId: String,
    users: Seq[UserDetails]
  ): StubMapping = stubFor(
    get(urlEqualTo(s"/users-groups-search/groups/$groupId/users")).willReturn(aResponse().withBody(s"""
                                                                                                      |[
                                                                                                      |    ${users
                                                                                                       .map(UserDetails.formats.writes)
                                                                                                       .mkString(",")}
                                                                                                      |]
          """.stripMargin))
  )

  def givenUserIdNotExistsFor(userId: String) = stubFor(
    get(urlEqualTo(s"/users-groups-search/users/$userId")).willReturn(aResponse().withStatus(404))
  )

}
