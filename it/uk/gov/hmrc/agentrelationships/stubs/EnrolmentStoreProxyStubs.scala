package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
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

  def givenDelegatedGroupIdsExistFor(taxIdentifier: TaxIdentifier, groupIds: Set[String]) = {
    val enrolmentKey = enrolmentKeyPrefixFor(taxIdentifier) + "~" + taxIdentifier.value
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

  def givenPrincipalUserIdsExistFor(taxIdentifier: TaxIdentifier, userId: String) = {
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

  def givenPrincipalUserIdsNotExistFor(taxIdentifier: TaxIdentifier) = {
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

  private def similarToJson(value: String) = equalToJson(value.stripMargin, true, true)

}
