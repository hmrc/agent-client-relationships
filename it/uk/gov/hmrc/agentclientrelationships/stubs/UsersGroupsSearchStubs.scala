package uk.gov.hmrc.agentclientrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.{MatchResult, UrlPattern}
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import uk.gov.hmrc.agentclientrelationships.connectors.{GroupInfo, UserDetails}
import uk.gov.hmrc.domain.AgentCode

trait UsersGroupsSearchStubs {

  private val ugsBaseUrl = s"/users-groups-search"

  def givenGroupInfo(groupId: String, agentCode: String) = {
    val groupInfo = GroupInfo(groupId, Some("Agent"), Some(AgentCode(agentCode)))
    stubFor(
      get(urlEqualTo(s"$ugsBaseUrl/groups/$groupId"))
        .willReturn(
          aResponse()
            .withBody(GroupInfo.formats.writes(groupInfo).toString())
        )
    )
  }

  def givenGroupInfoNoAgentCode(groupId: String) = {
    val groupInfo = GroupInfo(groupId, Some("Agent"), None)
    stubFor(
      get(urlEqualTo(s"$ugsBaseUrl/groups/$groupId"))
        .willReturn(
          aResponse()
            .withBody(GroupInfo.formats.writes(groupInfo).toString())
        )
    )
  }

  def givenGroupInfoNotExists(groupId: String) =
    stubFor(
      get(urlEqualTo(s"$ugsBaseUrl/groups/$groupId"))
        .willReturn(aResponse().withStatus(404))
    )

  private def urlContains(str: String): UrlPattern = new UrlPattern(containing(str), false) {
    override def `match`(url: String): MatchResult = pattern.`match`(url)
  }

  def givenGroupInfoFailsWith(status: Int) =
    stubFor(
      get(urlContains(s"$ugsBaseUrl/groups/"))
        .willReturn(aResponse().withStatus(status))
    )

  def givenAgentGroupExistsFor(groupId: String): StubMapping =
    stubFor(
      get(urlEqualTo(s"/users-groups-search/groups/$groupId"))
        .willReturn(
          aResponse()
            .withBody(s"""
                         |{
                         |  "_links": [
                         |    { "rel": "users", "link": "/groups/$groupId/users" }
                         |  ],
                         |  "groupId": "foo",
                         |  "affinityGroup": "Agent",
                         |  "agentCode": "NQJUEJCWT14",
                         |  "agentFriendlyName": "JoeBloggs"
                         |}
          """.stripMargin)
        )
    )

  def givenNonAgentGroupExistsFor(groupId: String): StubMapping =
    stubFor(
      get(urlEqualTo(s"/users-groups-search/groups/$groupId"))
        .willReturn(
          aResponse()
            .withBody(s"""
                         |{
                         |  "_links": [
                         |    { "rel": "users", "link": "/groups/$groupId/users" }
                         |  ],
                         |  "groupId": "foo",
                         |  "affinityGroup": "Organisation"
                         |}
          """.stripMargin)
        )
    )

  def givenGroupNotExistsFor(groupId: String) =
    stubFor(
      get(urlEqualTo(s"/users-groups-search/groups/$groupId"))
        .willReturn(aResponse().withStatus(404))
    )

  def givenAdminUser(groupId: String, userId: String) =
    givenAgentGroupWithUsers(groupId, List(UserDetails(userId = Some(userId), credentialRole = Some("Admin"))))

  def givenAgentGroupWithUsers(groupId: String, users: Seq[UserDetails]): StubMapping =
    stubFor(
      get(urlEqualTo(s"/users-groups-search/groups/$groupId/users"))
        .willReturn(
          aResponse()
            .withBody(s"""
                         |[
                         |    ${users.map(UserDetails.formats.writes).mkString(",")}
                         |]
          """.stripMargin)
        )
    )

  def givenUserIdNotExistsFor(userId: String) =
    stubFor(
      get(urlEqualTo(s"/users-groups-search/users/$userId"))
        .willReturn(aResponse().withStatus(404))
    )

}
