package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.{MatchResult, UrlPattern}
import uk.gov.hmrc.agentclientrelationships.support.TaxIdentifierSupport
import uk.gov.hmrc.domain.TaxIdentifier

trait EnrolmentStoreProxyStubs extends TaxIdentifierSupport {

  private val esBaseUrl = s"/enrolment-store-proxy/enrolment-store/enrolments"
  private val teBaseUrl = s"/tax-enrolments"

  def givenPrincipalGroupIdExistsFor(taxIdentifier: TaxIdentifier, groupId: String) = {
    val enrolmentKey = enrolmentKeyPrefixFor(taxIdentifier) + "~" + taxIdentifier.value
    stubFor(get(urlEqualTo(s"$esBaseUrl/$enrolmentKey/groups?type=principal"))
      .willReturn(aResponse()
        .withBody(
          s"""
             |{
             |    "principalGroupIds":[
             |        "$groupId"
             |    ]
             |}
          """.stripMargin)))
  }

  def givenPrincipalGroupIdNotExistsFor(taxIdentifier: TaxIdentifier) = {
    val enrolmentKey = enrolmentKeyPrefixFor(taxIdentifier) + "~" + taxIdentifier.value
    stubFor(get(urlEqualTo(s"$esBaseUrl/$enrolmentKey/groups?type=principal"))
      .willReturn(aResponse()
        .withBody(
          s"""
             |{
             |    "principalGroupIds":[]
             |}
          """.stripMargin)))
  }

  private def urlContains(str: String): UrlPattern = new UrlPattern(containing(str),false){
    override def `match`(url: String): MatchResult = pattern.`match`(url)
  }

  def givenPrincipalGroupIdRequestFailsWith(status: Int) = {
    stubFor(get(urlContains("/groups?type=principal"))
      .willReturn(aResponse().withStatus(status)))
  }

  def givenDelegatedGroupIdsExistFor(taxIdentifier: TaxIdentifier, groupIds: Set[String]) = {
    val enrolmentKey = enrolmentKeyPrefixFor(taxIdentifier) + "~" + taxIdentifier.value
    givenDelegatedGroupIdsExistForKey(enrolmentKey,groupIds)
  }

  def givenDelegatedGroupIdsExistForKey(enrolmentKey: String, groupIds: Set[String]) = {
    stubFor(get(urlEqualTo(s"$esBaseUrl/$enrolmentKey/groups?type=delegated"))
      .willReturn(aResponse()
        .withBody(
          s"""
             |{
             |    "delegatedGroupIds":[
             |        ${groupIds.map(s => s""""$s\"""").mkString(",")}
             |    ]
             |}
          """.stripMargin)))
  }

  def givenDelegatedGroupIdsNotExistFor(taxIdentifier: TaxIdentifier) = {
    val enrolmentKey = enrolmentKeyPrefixFor(taxIdentifier) + "~" + taxIdentifier.value
    stubFor(get(urlEqualTo(s"$esBaseUrl/$enrolmentKey/groups?type=delegated"))
      .willReturn(aResponse()
        .withBody(
          s"""
             |{
             |    "delegatedGroupIds":[]
             |}
          """.stripMargin)))
  }

  def givenDelegatedGroupIdRequestFailsWith(status: Int) = {
    stubFor(get(urlContains("/groups?type=delegated"))
      .willReturn(aResponse().withStatus(status)))
  }

  def givenPrincipalUserIdExistFor(taxIdentifier: TaxIdentifier, userId: String) = {
    val enrolmentKey = enrolmentKeyPrefixFor(taxIdentifier) + "~" + taxIdentifier.value
    stubFor(get(urlEqualTo(s"$esBaseUrl/$enrolmentKey/users?type=principal"))
      .willReturn(aResponse()
        .withBody(
          s"""
             |{
             |    "principalUserIds":[
             |        "$userId"
             |    ]
             |}
          """.stripMargin)))
  }

  def givenPrincipalUserIdNotExistFor(taxIdentifier: TaxIdentifier) = {
    val enrolmentKey = enrolmentKeyPrefixFor(taxIdentifier) + "~" + taxIdentifier.value
    stubFor(get(urlEqualTo(s"$esBaseUrl/$enrolmentKey/users?type=principal"))
      .willReturn(aResponse()
        .withBody(
          s"""
             |{
             |    "principalUserIds":[]
             |}
          """.stripMargin)))
  }

  def givenEnrolmentAllocationSucceeds(groupId: String, clientUserId: String, key: String, identifier: String, value: String, agentCode: String) = {
    stubFor(
      post(urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/$key~$identifier~$value?legacy-agentCode=$agentCode"))
        .withRequestBody(similarToJson(
          s"""
             |{
             |   "userId":"$clientUserId",
             |   "type":"delegated"
             |}
             |""".stripMargin))
        .willReturn(
          aResponse().withStatus(201)
        )
    )
  }

  def givenEnrolmentAllocationFailsWith(responseStatus: Int)
                                       (groupId: String, clientUserId: String, key: String, identifier: String, value: String, agentCode: String) = {
    stubFor(
      post(urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/$key~$identifier~$value?legacy-agentCode=$agentCode"))
        .withRequestBody(similarToJson(
          s"""
             |{
             |   "userId":"$clientUserId",
             |   "type":"delegated"
             |}
             |""".stripMargin))
        .willReturn(
          aResponse().withStatus(responseStatus)
        )
    )
  }

  def givenEnrolmentDeallocationSucceeds(groupId: String, taxIdentifier: TaxIdentifier, agentCode: String) = {
    val enrolmentKey = enrolmentKeyPrefixFor(taxIdentifier) + "~" + taxIdentifier.value
    stubFor(
      delete(urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/$enrolmentKey?legacy-agentCode=$agentCode"))
        .willReturn(
          aResponse().withStatus(204)
        )
    )
  }

  def givenEnrolmentDeallocationSucceeds(groupId: String, key: String, identifier: String, value: String, agentCode: String) = {
    stubFor(
      delete(urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/$key~$identifier~$value?legacy-agentCode=$agentCode"))
        .willReturn(
          aResponse().withStatus(204)
        )
    )
  }

  def givenEnrolmentDeallocationFailsWith(responseStatus: Int)
                                         (groupId: String, key: String, identifier: String, value: String, agentCode: String) = {
    stubFor(
      delete(urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/$key~$identifier~$value?legacy-agentCode=$agentCode"))
        .willReturn(
          aResponse().withStatus(responseStatus)
        )
    )
  }

  def givenEsIsUnavailable() = {
    stubFor(any(urlMatching(s"$esBaseUrl/.*"))
      .willReturn(aResponse().withStatus(503)))
    stubFor(any(urlMatching(s"$teBaseUrl/.*"))
      .willReturn(aResponse().withStatus(503)))
  }

  private def similarToJson(value: String) = equalToJson(value.stripMargin, true, true)

}