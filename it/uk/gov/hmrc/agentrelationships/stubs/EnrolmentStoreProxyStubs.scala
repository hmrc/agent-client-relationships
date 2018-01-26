package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}

trait EnrolmentStoreProxyStubs {

  private val esBaseUrl = s"/enrolment-store-proxy/enrolment-store/enrolments"
  private val teBaseUrl = s"/tax-enrolments"

  def givenGroupIdExistsForArn(arn: Arn, groupId: String) = {
    stubFor(get(urlEqualTo(s"$esBaseUrl/HMRC-AS-AGENT~AgentReferenceNumber~${arn.value}/groups?type=principal"))
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

  def givenGroupIdNotExistsForArn(arn: Arn) = {
    stubFor(get(urlEqualTo(s"$esBaseUrl/HMRC-AS-AGENT~AgentReferenceNumber~${arn.value}/groups?type=principal"))
      .willReturn(aResponse()
        .withBody(
          s"""
             |{
             |    "principalGroupIds":[]
             |}
          """.stripMargin)))
  }

  def givenGroupIdsExistForMTDITID(mtdItId: MtdItId, groupIds: Set[String]) = {
    stubFor(get(urlEqualTo(s"$esBaseUrl/HMRC-MTD-IT~MTDITID~${mtdItId.value}/groups?type=delegated"))
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

  def givenGroupIdsNotExistForMTDITID(mtdItId: MtdItId) = {
    stubFor(get(urlEqualTo(s"$esBaseUrl/HMRC-MTD-IT~MTDITID~${mtdItId.value}/groups?type=delegated"))
      .willReturn(aResponse()
        .withBody(
          s"""
             |{
             |    "delegatedGroupIds":[]
             |}
          """.stripMargin)))
  }

  def givenUserIdsExistForMTDITID(mtdItId: MtdItId, userId: String) = {
    stubFor(get(urlEqualTo(s"$esBaseUrl/HMRC-MTD-IT~MTDITID~${mtdItId.value}/users?type=principal"))
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

  def givenUserIdsNotExistForMTDITID(mtdItId: MtdItId) = {
    stubFor(get(urlEqualTo(s"$esBaseUrl/HMRC-MTD-IT~MTDITID~${mtdItId.value}/users?type=principal"))
      .willReturn(aResponse()
        .withBody(
          s"""
             |{
             |    "principalUserIds":[]
             |}
          """.stripMargin)))
  }

  def givenEnrolmentDelegationSucceeds(clientGroupId: String, clientUserId: String, key: String, identifier: String, value: String, agentCode: String) = {
    stubFor(
      post(urlEqualTo(s"$teBaseUrl/groups/$clientGroupId/enrolments/$key~$identifier~$value?legacy-agentCode=$agentCode"))
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

  def givenEnrolmentDelegationFailsWith404(clientGroupId: String, clientUserId: String, key: String, identifier: String, value: String, agentCode: String) = {
    stubFor(
      post(urlEqualTo(s"$teBaseUrl/groups/$clientGroupId/enrolments/$key~$identifier~$value?legacy-agentCode=$agentCode"))
        .withRequestBody(similarToJson(
          s"""
             |{
             |   "userId":"$clientUserId",
             |   "type":"delegated"
             |}
             |""".stripMargin))
        .willReturn(
          aResponse().withStatus(404)
        )
    )
  }

  private def similarToJson(value: String) = equalToJson(value.stripMargin, true, true)

}
