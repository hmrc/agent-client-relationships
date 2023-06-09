package uk.gov.hmrc.agentclientrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.{MatchResult, UrlPattern}
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentmtdidentifiers.model.Arn

trait EnrolmentStoreProxyStubs extends Eventually {

  private implicit val patience = PatienceConfig(scaled(Span(2, Seconds)), scaled(Span(500, Millis)))

  private val esBaseUrl = s"/enrolment-store-proxy/enrolment-store"
  private val teBaseUrl = s"/tax-enrolments"

  def agentEnrolmentKey(arn: Arn): EnrolmentKey = EnrolmentKey(s"HMRC-AS-AGENT~AgentReferenceNumber~${arn.value}")

  // ES1
  def givenPrincipalGroupIdExistsFor(enrolmentKey: EnrolmentKey, groupId: String) = {
    stubFor(
      get(urlEqualTo(s"$esBaseUrl/enrolments/${enrolmentKey.tag}/groups?type=principal"))
        .willReturn(aResponse()
          .withBody(s"""
                       |{
                       |    "principalGroupIds":[
                       |        "$groupId"
                       |    ]
                       |}
          """.stripMargin)))
  }

  def givenPrincipalGroupIdNotExistsFor(enrolmentKey: EnrolmentKey) = {
    stubFor(
      get(urlEqualTo(s"$esBaseUrl/enrolments/${enrolmentKey.tag}/groups?type=principal"))
        .willReturn(aResponse()
          .withStatus(204)))
  }

  private def urlContains(str: String): UrlPattern = new UrlPattern(containing(str), false) {
    override def `match`(url: String): MatchResult = pattern.`match`(url)
  }

  def givenPrincipalGroupIdRequestFailsWith(status: Int) =
    stubFor(
      get(urlContains("/groups?type=principal"))
        .willReturn(aResponse().withStatus(status).withBody("FAILED_ESP")))

  def givenDelegatedGroupIdsExistFor(enrolmentKey: EnrolmentKey, groupIds: Set[String]) =
    stubFor(
      get(urlEqualTo(s"$esBaseUrl/enrolments/${enrolmentKey.tag}/groups?type=delegated"))
        .willReturn(aResponse()
          .withBody(s"""
                       |{
                       |    "delegatedGroupIds":[
                       |        ${groupIds.map(s => s""""$s\"""").mkString(",")}
                       |    ]
                       |}
          """.stripMargin)))

  def givenDelegatedGroupIdsNotExistFor(enrolmentKey: EnrolmentKey) =
    stubFor(
      get(urlEqualTo(s"$esBaseUrl/enrolments/${enrolmentKey.tag}/groups?type=delegated"))
        .willReturn(aResponse()
          .withStatus(204)))

  def givenDelegatedGroupIdRequestFailsWith(status: Int) =
    stubFor(
      get(urlContains("/groups?type=delegated"))
        .willReturn(aResponse().withStatus(status)))

  def givenPrincipalUserIdExistFor(enrolmentKey: EnrolmentKey, userId: String) = {
    givenPrincipalUserIdsExistFor(enrolmentKey, List(userId))
  }

  def givenPrincipalUserIdsExistFor(enrolmentKey: EnrolmentKey, userIds: Seq[String]) = {
    stubFor(
      get(urlEqualTo(s"$esBaseUrl/enrolments/${enrolmentKey.tag}/users?type=principal"))
        .willReturn(aResponse()
          .withBody(s"""
                       |{
                       |    "principalUserIds":[
                       |        ${userIds.map('"' + _ + '"').mkString(", ")}
                       |    ]
                       |}
          """.stripMargin)))
  }

  def givenPrincipalUserIdNotExistFor(enrolmentKey: EnrolmentKey) = {
    stubFor(
      get(urlEqualTo(s"$esBaseUrl/enrolments/${enrolmentKey.tag}/users?type=principal"))
        .willReturn(aResponse()
          .withStatus(204)))
  }

  def givenEnrolmentAllocationSucceeds(
    groupId: String,
    clientUserId: String,
    enrolmentKey: EnrolmentKey,
    agentCode: String) =
    stubFor(
      post(urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/${enrolmentKey.tag}?legacy-agentCode=$agentCode"))
        .withRequestBody(similarToJson(s"""
                                          |{
                                          |   "userId":"$clientUserId",
                                          |   "type":"delegated"
                                          |}
                                          |""".stripMargin))
        .withHeader("Content-Type", containing("application/json"))
        .willReturn(aResponse().withStatus(201)))

  def verifyEnrolmentAllocationAttempt(groupId: String, clientUserId: String, enrolmentKey: EnrolmentKey, agentCode: String) =
    eventually {
      verify(
        1,
        postRequestedFor(urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/${enrolmentKey.tag}?legacy-agentCode=$agentCode"))
          .withRequestBody(similarToJson(s"""
                                            |{
                                            |   "userId":"$clientUserId",
                                            |   "type":"delegated"
                                            |}
                                            |""".stripMargin))
      )
    }

  def verifyNoEnrolmentHasBeenAllocated() =
    eventually {
      verify(0, postRequestedFor(urlContains(s"$teBaseUrl/groups/")))
    }

  def givenEnrolmentAllocationFailsWith(responseStatus: Int)(
    groupId: String,
    clientUserId: String,
    enrolmentKey: EnrolmentKey,
    agentCode: String) =
    stubFor(
      post(urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/${enrolmentKey.tag}?legacy-agentCode=$agentCode"))
        .withRequestBody(similarToJson(s"""
                                          |{
                                          |   "userId":"$clientUserId",
                                          |   "type":"delegated"
                                          |}
                                          |""".stripMargin))
        .willReturn(aResponse().withStatus(responseStatus)))

  def verifyEnrolmentDeallocationAttempt(groupId: String, enrolmentKey: EnrolmentKey) =
    eventually {
      verify(1, deleteRequestedFor(urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/${enrolmentKey.tag}")))
    }

  def verifyNoEnrolmentHasBeenDeallocated() =
    eventually {
      verify(0, deleteRequestedFor(urlContains(s"$teBaseUrl/groups/")))
    }

  def givenEnrolmentDeallocationSucceeds(groupId: String, enrolmentKey: EnrolmentKey) =
    stubFor(
      delete(urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/${enrolmentKey.tag}"))
        .willReturn(aResponse().withStatus(204)))
  
  def givenEnrolmentDeallocationFailsWith(
    responseStatus: Int)(groupId: String, enrolmentKey: EnrolmentKey) =
    stubFor(
      delete(urlEqualTo(s"$teBaseUrl/groups/$groupId/enrolments/${enrolmentKey.tag}"))
        .willReturn(aResponse().withStatus(responseStatus)))

  def givenEsIsUnavailable() = {
    stubFor(
      any(urlMatching(s"$esBaseUrl/.*"))
        .willReturn(aResponse().withStatus(503)))
    stubFor(
      any(urlMatching(s"$teBaseUrl/.*"))
        .willReturn(aResponse().withStatus(503)))
  }

  def givenEnrolmentExistsForGroupId(groupId: String, enrolmentKey: EnrolmentKey): StubMapping = {
    stubFor(
      get(urlEqualTo(s"$esBaseUrl/groups/$groupId/enrolments?type=principal&service=HMRC-AS-AGENT"))
        .willReturn(
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
                         |                 "key": "${enrolmentKey.singleIdentifier.key}",
                         |                 "value": "${enrolmentKey.singleIdentifier.value}"
                         |              }
                         |           ]
                         |        }
                         |    ]
                         |}
             """.stripMargin)))
  }

  def givenEnrolmentNotExistsForGroupId(groupId: String): StubMapping =
    stubFor(
      get(urlEqualTo(s"$esBaseUrl/groups/$groupId/enrolments?type=principal&service=HMRC-AS-AGENT"))
        .willReturn(aResponse()
          .withStatus(204)))

  private def similarToJson(value: String) = equalToJson(value.stripMargin, true, true)

}
