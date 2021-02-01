package uk.gov.hmrc.agentrelationships.controllers

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import org.joda.time.{DateTime, LocalDate}
import play.api.libs.json.JodaReads._
import play.api.libs.json.JsObject
import play.api.test.FakeRequest
import uk.gov.hmrc.agentclientrelationships.model.{DeletionCount, TerminationResponse}
import uk.gov.hmrc.agentclientrelationships.repository.{DeleteRecord, RelationshipCopyRecord, SyncStatus}
import uk.gov.hmrc.agentmtdidentifiers.model.MtdItId
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.{HeaderNames, HttpResponse}

import scala.concurrent.ExecutionContext.Implicits.global

class RelationshipsControllerISpec extends RelationshipsBaseControllerISpec {

  val relationshipCopiedSuccessfully = RelationshipCopyRecord(
    arn.value,
    mtdItId.value,
    mtdItIdType,
    syncToETMPStatus = Some(SyncStatus.Success),
    syncToESStatus = Some(SyncStatus.Success))

  case class TestClient(
                         service: String,
                         regime: String,
                         clientId: TaxIdentifier)

  val itsaClient = TestClient(HMRCMTDIT, "ITSA", mtdItId)
  val vatClient = TestClient(HMRCMTDVAT, "VATC", vrn)
  val trustClient = TestClient(HMRCTERSORG, "TRS", utr)
  val cgtClient = TestClient(HMRCCGTPD, "CGT", cgtRef)

  val individualList = List(itsaClient, vatClient, cgtClient)
  val businessList = List(vatClient, trustClient, cgtClient)

  val desOnlyList = List(itsaClient, vatClient, trustClient, cgtClient)

  val desOnlyWithRelationshipTypeAndAuthProfile = List(vatClient, cgtClient)

  class LoggedInUser(isLoggedInClientStride: Boolean, isLoggedInClientInd: Boolean, isLoggedInClientBusiness: Boolean) {
    if(isLoggedInClientStride) {
      givenUserIsAuthenticatedWithStride(NEW_STRIDE_ROLE,"strideId-1234456")
      givenUserIsAuthenticatedWithStride(STRIDE_ROLE,"strideId-1234456")
    }
    else if(isLoggedInClientInd) givenLoginClientIndAll(mtdItId, vrn, nino, cgtRef)
    else if(isLoggedInClientBusiness) givenLoginClientBusinessAll(vrn, utr, cgtRef)
    else requestIsNotAuthenticated()
  }

  "GET /client/relationships/service/:service" should {
    individualList.foreach { client =>
      runActiveRelationshipsScenario(client, true, false)
    }

    businessList.foreach { client =>
      runActiveRelationshipsScenario(client, false, true)
    }

    individualList.foreach { client =>
      runActiveRelationshipsErrorScenario(client, true, false)
    }

    businessList.foreach { client =>
      runActiveRelationshipsErrorScenario(client, false, true)
    }
  }

  def runActiveRelationshipsScenario(testClient: TestClient, isLoggedInClientInd: Boolean, isLoggedInBusiness: Boolean) = {
    val requestPath: String = s"/agent-client-relationships/client/relationships/service/${testClient.service}"

    def doRequest = doAgentGetRequest(requestPath)

    s"find relationship for service ${testClient.service} and user ${if(isLoggedInClientInd) "Individual" else "Business"}" in
      new LoggedInUser(false, isLoggedInClientInd, isLoggedInBusiness) {

        getActiveRelationshipsViaClient(testClient.clientId, arn)

        val result: HttpResponse = await(doRequest)
        result.status shouldBe 200

        (result.json \ "arn").get.as[String] shouldBe arn.value
        (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
    }

    s"find multiple relationships for service ${testClient.service} " +
      s"but filter out active and ended relationships for user ${if(isLoggedInClientInd) "Individual" else "Business"}"in
      new LoggedInUser(false, isLoggedInClientInd, isLoggedInBusiness) {

      getSomeActiveRelationshipsViaClient(testClient.clientId, arn.value, arn2.value, arn3.value)

      val result: HttpResponse = await(doRequest)
      result.status shouldBe 200
      (result.json \ "arn").get.as[String] shouldBe arn3.value
      (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
    }
  }

  def runActiveRelationshipsErrorScenario(testClient: TestClient, isLoggedInClientInd: Boolean, isLoggedInBusiness: Boolean) = {
    val requestPath: String = s"/agent-client-relationships/relationships/service/${testClient.service}"

    def doRequest = doAgentGetRequest(requestPath)

    "find relationship but filter out if the end date has been changed from 9999-12-31 " +
    s"for service ${testClient.service} and user ${if(isLoggedInClientInd) "Individual" else "Business"}" in
      new LoggedInUser(false, isLoggedInClientInd, isLoggedInBusiness) {

      getInactiveRelationshipViaClient(testClient.clientId, arn.value)

      val result: HttpResponse = await(doRequest)
      result.status shouldBe 404
    }

    "return 404 when DES returns 404 relationship not found " +
      s"for service ${testClient.service} and user ${if(isLoggedInClientInd) "Individual" else "Business"}" in
      new LoggedInUser(false, isLoggedInClientInd, isLoggedInBusiness) {

      getActiveRelationshipFailsWith(testClient.clientId, 404)

      val result = await(doRequest)
      result.status shouldBe 404
    }


    "return 404 when DES returns 400 (treated as relationship not found) " +
      s"for service ${testClient.service} and user ${if(isLoggedInClientInd) "Individual" else "Business"}" in
      new LoggedInUser(false, isLoggedInClientInd, isLoggedInBusiness) {

      getActiveRelationshipFailsWith(testClient.clientId, 400)

      val result = await(doRequest)
      result.status shouldBe 404
    }

  }

  "GET /agent/relationships/inactive/service/:service" should {
    desOnlyList.foreach { client =>
      runInactiveRelationshipsScenario(client)
      runInactiveRelationshipsErrorScenario(client)
    }

    desOnlyWithRelationshipTypeAndAuthProfile.foreach { client =>
      runInvalidCallForVatAndCgt(client)
    }
  }

  def runInactiveRelationshipsScenario(testClient: TestClient) = {
    val requestPath: String = s"/agent-client-relationships/agent/relationships/inactive"
    def doRequest = doAgentGetRequest(requestPath)
    val fakeRequest = FakeRequest("GET", s"/agent-client-relationships/agent/relationships/inactive")

    s"return 200 with list of inactive ${testClient.service} for an Agent" in {
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      val clientType = if(testClient.clientId.isInstanceOf[MtdItId]) "personal" else "business"

      val otherId: TaxIdentifier = otherTaxIdentifier(testClient.clientId)

      getInactiveRelationshipsViaAgent(arn, otherId, testClient.clientId)

      val result = await(doRequest)
      result.status shouldBe 200

      (result.json \\ "arn").head.as[String] shouldBe arn.value
      (result.json \\ "dateTo").head.as[LocalDate].toString() shouldBe "2015-09-21"
      (result.json \\ "clientId").head.as[String] shouldBe otherId.value
      (result.json \\ "clientType").head.as[String] shouldBe clientType
      (result.json \\ "service").head.as[String] shouldBe testClient.service

      (result.json \\ "arn")(1).as[String] shouldBe arn.value
      (result.json \\ "dateTo")(1).as[LocalDate].toString() shouldBe LocalDate.now().toString
      (result.json \\ "clientId")(1).as[String] shouldBe testClient.clientId.value
      (result.json \\ "clientType")(1).as[String] shouldBe clientType
      (result.json \\ "service")(1).as[String] shouldBe testClient.service
    }
  }

  def runInactiveRelationshipsErrorScenario(testClient: TestClient) = {
    val requestPath: String = s"/agent-client-relationships/agent/relationships/inactive"
    def doRequest = doAgentGetRequest(requestPath)
    val fakeRequest = FakeRequest("GET", s"/agent-client-relationships/agent/relationships/inactive")

    s"find relationship but filter out if the relationship is still active for ${testClient.service}" in {
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      getAgentInactiveRelationshipsButActive(arnEncoded, arn.value, testClient.clientId.value)

      val result = await(doRequest)
      result.status shouldBe 404
    }

    s"return 404 when DES returns not found for ${testClient.service}" in {
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      getFailAgentInactiveRelationships(arnEncoded, 404)

      val result = await(doRequest)
      result.status shouldBe 404
    }

    s"find relationships and filter out relationships that have no dateTo ${testClient.service}"  in {
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      getAgentInactiveRelationshipsNoDateTo(arn, testClient.clientId.value)

      val result = await(doRequest)
      result.status shouldBe 404
    }

    s"return 404 when DES returns 400 for ${testClient.service}" in {
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      getFailAgentInactiveRelationships(arnEncoded, 400)

      val result = await(doRequest)
      result.status shouldBe 404
    }
  }

  def runInvalidCallForVatAndCgt(testClient: TestClient) = {

    val requestPath: String = s"/agent-client-relationships/agent/relationships/inactive"
    def doRequest = doAgentGetRequest(requestPath)

    s"return 404 for invalid parameters given for ${testClient.regime}" in {
      getFailWithInvalidAgentInactiveRelationships(arn.value)

      val result = await(doRequest)
      result.status shouldBe 404
    }
  }

  "GET /client/relationships/active" should {

    val requestPath: String = s"/agent-client-relationships/client/relationships/active"
    def doRequest: HttpResponse = doAgentGetRequest(requestPath)
    val fakeRequest = FakeRequest("GET", s"/agent-client-relationships/client/relationships/active")


    "return 200 with a map of relationships and filter only on active ones" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr)

      getActiveRelationshipsViaClient(mtdItId, arn)
      getActiveRelationshipsViaClient(vrn, arn2)
      getActiveRelationshipsViaClient(utr, arn)


      val result = await(doRequest)
      result.status shouldBe 200
      val response = result.json.as[JsObject]

      response should havePropertyArrayOf[String]("HMRC-MTD-IT", be(arn.value))
      response should havePropertyArrayOf[String]("HMRC-MTD-VAT", be(arn2.value))
      response should havePropertyArrayOf[String]("HMRC-TERS-ORG", be(arn.value))
    }

    "return 200 with empty map of active relationships when they are found inactive" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr)

      desOnlyList.foreach { notActiveClient =>
        getInactiveRelationshipViaClient(notActiveClient.clientId, arn.value)
      }

      val result = await(doRequest)
      result.status shouldBe 200
      val response = result.json.as[JsObject]

      desOnlyList.foreach { notActiveClient =>
        response should notHaveProperty(notActiveClient.service)
      }
    }

    "return 200 with empty map of active relationships when not found" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr)

      desOnlyList.foreach { notActiveClient =>
        getActiveRelationshipFailsWith(notActiveClient.clientId, 404)
      }

      val result = await(doRequest)
      result.status shouldBe 200
      val response = result.json.as[JsObject]

      desOnlyList.foreach { notActiveClient =>
        response should notHaveProperty(notActiveClient.service)
      }
    }
  }

  "DELETE /agent/:arn/terminate" should {

    val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/terminate"
    def basicAuth(string: String): String = Base64.getEncoder.encodeToString(string.getBytes(UTF_8))
    def doRequest = ws.url(s"http://localhost:$port$requestPath")
      .addHttpHeaders(HeaderNames.authorisation -> s"Basic ${basicAuth("username:password")}").delete()

    "return 200 after successful termination" in {

      //insert records first to have some state initially
      //insert delete-record document
      await(
        deleteRecordRepository.create(
          DeleteRecord(
            arn.value,
            mtdItId.value,
            mtdItIdType,
            DateTime.now.minusMinutes(1),
            Some(SyncStatus.Success),
            Some(SyncStatus.Failed))))

      //insert copy-relationship document
       await(repo.insert(relationshipCopiedSuccessfully))

      val result = await(doRequest)
      result.status shouldBe 200
      val response = result.json.as[TerminationResponse]

      response shouldBe TerminationResponse(
        Seq(
          DeletionCount("agent-client-relationships", "delete-record", 1),
          DeletionCount("agent-client-relationships", "relationship-copy-record", 1)))

      //verify termination has deleted all record for that agent
      await(deleteRecordRepository.find("arn" -> arn.value)) shouldBe empty
      await(repo.find("arn" -> arn.value)) shouldBe empty
    }
  }

  "GET /client/relationships/inactive" should {

    val requestPath: String = s"/agent-client-relationships/client/relationships/inactive"

    def doRequest: HttpResponse = doAgentGetRequest(requestPath)

    val fakeRequest = FakeRequest("GET", s"/agent-client-relationships/client/relationships/inactive")

    "return a sequence of inactive relationships" in {

      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr)

      getInactiveRelationshipsForClient(mtdItId)
      getInactiveRelationshipsForClient(vrn)
      getInactiveRelationshipsForClient(utr)

      val result = await(doRequest)

      result.status shouldBe 200

      (result.json \\ "arn").head.as[String] shouldBe "ABCDE123456"
      (result.json \\ "dateTo").head.as[LocalDate].toString() shouldBe "2018-09-09"
      (result.json \\ "clientId").head.as[String] shouldBe "ABCDEF123456789"
      (result.json \\ "clientType").head.as[String] shouldBe "personal"
      (result.json \\ "service").head.as[String] shouldBe "HMRC-MTD-IT"
    }

    "return OK with empty body if no inactive relationships found" in {

      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr)

      getNoInactiveRelationshipsForClient(mtdItId)
      getNoInactiveRelationshipsForClient(vrn)
      getNoInactiveRelationshipsForClient(utr)

      val result = await(doRequest)
      result.status shouldBe 200

      result.body shouldBe "[]"
    }
  }
}
