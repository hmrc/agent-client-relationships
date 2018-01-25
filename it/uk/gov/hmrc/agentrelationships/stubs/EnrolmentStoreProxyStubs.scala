package uk.gov.hmrc.agentrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, stubFor, urlEqualTo}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId}

trait EnrolmentStoreProxyStubs {

  private val esBaseUrl = s"/enrolment-store-proxy/enrolment-store/enrolments"

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

  def givenGroupIdsExistForMTDITID(mtdItId: MtdItId, groupIds:Set[String]) = {
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
}
