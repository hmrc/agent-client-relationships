package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.{ MatchResult, UrlPattern }
import uk.gov.hmrc.agentclientrelationships.connectors.GroupInfo
import uk.gov.hmrc.agentclientrelationships.support.TaxIdentifierSupport
import uk.gov.hmrc.domain.AgentCode

trait UsersGroupsSearchStubs extends TaxIdentifierSupport {

  private val ugsBaseUrl = s"/users-groups-search"

  def givenGroupInfo(groupId: String, agentCode: String) = {
    val groupInfo = GroupInfo(groupId, Some("Agent"), Some(AgentCode(agentCode)))
    stubFor(get(urlEqualTo(s"$ugsBaseUrl/groups/$groupId"))
      .willReturn(aResponse()
        .withBody(GroupInfo.formats.writes(groupInfo).toString())))
  }

  def givenGroupInfoNotExists(groupId: String) = {
    stubFor(get(urlEqualTo(s"$ugsBaseUrl/groups/$groupId"))
      .willReturn(aResponse().withStatus(404)))
  }

  private def urlContains(str: String): UrlPattern = new UrlPattern(containing(str), false) {
    override def `match`(url: String): MatchResult = pattern.`match`(url)
  }

  def givenGroupInfoFailsWith(status: Int) = {
    stubFor(get(urlContains(s"$ugsBaseUrl/groups/"))
      .willReturn(aResponse().withStatus(status)))
  }

}
