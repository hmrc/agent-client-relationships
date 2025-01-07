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

package uk.gov.hmrc.agentclientrelationships.controllers

import org.mongodb.scala.model.Filters
import play.api.libs.json.{Format, JsObject}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.model.{DeletionCount, EnrolmentKey, MongoLocalDateTimeFormat, TerminationResponse}
import uk.gov.hmrc.agentclientrelationships.repository.{DeleteRecord, RelationshipCopyRecord, SyncStatus}
import uk.gov.hmrc.agentclientrelationships.services.ValidationService
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentmtdidentifiers.model.{Identifier, MtdItId, Service}
import uk.gov.hmrc.domain.TaxIdentifier
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames, HttpResponse}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{LocalDate, LocalDateTime}
import java.util.Base64

class RelationshipsIFControllerISpec extends RelationshipsControllerISpec with RelationshipsBaseIFControllerISpec

class RelationshipsHIPControllerISpec extends RelationshipsControllerISpec with RelationshipsBaseHIPControllerISpec

trait RelationshipsControllerISpec extends RelationshipsBaseControllerISpec {

  val relationshipCopiedSuccessfully: RelationshipCopyRecord = RelationshipCopyRecord(
    arn.value,
    Some(EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001"))),
    syncToETMPStatus = Some(SyncStatus.Success),
    syncToESStatus = Some(SyncStatus.Success)
  )

  case class TestClient(service: String, regime: String, clientId: TaxIdentifier)

  val itsaClient: TestClient = TestClient(Service.MtdIt.id, "ITSA", mtdItId)
  val itsaSupClient: TestClient = TestClient(Service.MtdItSupp.id, "ITSA", mtdItId)
  val vatClient: TestClient = TestClient(Service.Vat.id, "VATC", vrn)
  val trustClient: TestClient = TestClient(Service.Trust.id, "TRS", utr)
  val trustNTClient: TestClient = TestClient(Service.TrustNT.id, "TRS", urn)
  val cgtClient: TestClient = TestClient(Service.CapitalGains.id, "CGT", cgtRef)
  val pptClient: TestClient = TestClient(Service.Ppt.id, "PPT", pptRef)
  val cbcClient: TestClient = TestClient(Service.Cbc.id, "CBC", cbcId)
  val cbcNonUkClient: TestClient = TestClient(Service.CbcNonUk.id, "CBC", cbcId)
  val pillar2Client: TestClient = TestClient(Service.Pillar2.id, "PLRID", plrId)

  // TODO WG -test for Supp
  val individualList = List(itsaClient /*, itsaSupClient*/, vatClient, cgtClient, pptClient)
  val businessList =
    List(vatClient, trustClient, trustNTClient, cgtClient, pptClient, cbcClient, cbcNonUkClient, pillar2Client)

  // TODO WG -test for Supp
  val servicesInIF = List(itsaClient, vatClient, trustClient, trustNTClient, cgtClient, pptClient, pillar2Client)

  val desOnlyWithRelationshipTypeAndAuthProfile = List(vatClient, cgtClient)

  class LoggedInUser(isLoggedInClientStride: Boolean, isLoggedInClientInd: Boolean, isLoggedInClientBusiness: Boolean) {
    if (isLoggedInClientStride) {
      givenUserIsAuthenticatedWithStride(NEW_STRIDE_ROLE, "strideId-1234456")
      givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-1234456")
    } else if (isLoggedInClientInd) givenLoginClientIndAll(mtdItId, vrn, nino, cgtRef, pptRef)
    else if (isLoggedInClientBusiness) givenLoginClientBusinessAll(vrn, utr, urn, cgtRef, pptRef, cbcId, plrId)
    else requestIsNotAuthenticated()
  }

  "GET /client/relationships/service/:service" should {
    individualList.foreach { client =>
      runActiveRelationshipsScenario(client, isLoggedInClientInd = true, isLoggedInBusiness = false)
    }

    businessList.foreach { client =>
      runActiveRelationshipsScenario(client, isLoggedInClientInd = false, isLoggedInBusiness = true)
    }

    individualList.foreach { client =>
      runActiveRelationshipsErrorScenario(client, isLoggedInClientInd = true, isLoggedInBusiness = false)
    }

    businessList.foreach { client =>
      runActiveRelationshipsErrorScenario(client, isLoggedInClientInd = false, isLoggedInBusiness = true)
    }
  }
//  private def getAuthProfile(service: String): String = service match {
//    case HMRCMTDITSUPP => "ALL00002"
//    case _             => "ALL00001"
//  }

  def runActiveRelationshipsScenario(
    testClient: TestClient,
    isLoggedInClientInd: Boolean,
    isLoggedInBusiness: Boolean
  ): Unit = {
    val requestPath: String = s"/agent-client-relationships/client/relationships/service/${testClient.service}"

    def doRequest() = doGetRequest(requestPath)

    s"find relationship for service ${testClient.service} and user ${if (isLoggedInClientInd) "Individual"
    else "Business"}" in
      new LoggedInUser(false, isLoggedInClientInd, isLoggedInBusiness) {

        getActiveRelationshipsViaClient(testClient.clientId, arn)
        // TODO WG - test Supp
        // getActiveRelationshipsViaClient(testClient.clientId, arn, getAuthProfile(testClient.service))

        val result: HttpResponse = doRequest()
        result.status shouldBe 200

        (result.json \ "arn").get.as[String] shouldBe arn.value
        (result.json \ "dateTo").get.as[LocalDate].toString shouldBe "9999-12-31"
      }

    s"find multiple relationships for service ${testClient.service} " +
      s"but filter out active and ended relationships for user ${if (isLoggedInClientInd) "Individual" else "Business"}" in
      new LoggedInUser(false, isLoggedInClientInd, isLoggedInBusiness) {

        getSomeActiveRelationshipsViaClient(testClient.clientId, arn.value, arn2.value, arn3.value)

        val result: HttpResponse = doRequest()
        result.status shouldBe 200
        (result.json \ "arn").get.as[String] shouldBe arn3.value
        (result.json \ "dateTo").get.as[LocalDate].toString shouldBe "9999-12-31"
      }
  }

  def runActiveRelationshipsErrorScenario(
    testClient: TestClient,
    isLoggedInClientInd: Boolean,
    isLoggedInBusiness: Boolean
  ): Unit = {
    val requestPath: String = s"/agent-client-relationships/relationships/service/${testClient.service}"

    def doRequest() = doGetRequest(requestPath)

    "find relationship but filter out if the end date has been changed from 9999-12-31 " +
      s"for service ${testClient.service} and user ${if (isLoggedInClientInd) "Individual" else "Business"}" in
      new LoggedInUser(false, isLoggedInClientInd, isLoggedInBusiness) {

        getInactiveRelationshipViaClient(testClient.clientId, arn.value)

        val result: HttpResponse = doRequest()
        result.status shouldBe 404
      }

    "return 404 when DES returns 404 relationship not found " +
      s"for service ${testClient.service} and user ${if (isLoggedInClientInd) "Individual" else "Business"}" in
      new LoggedInUser(false, isLoggedInClientInd, isLoggedInBusiness) {

        getActiveRelationshipFailsWith(testClient.clientId, 404)

        val result: HttpResponse = doRequest()
        result.status shouldBe 404
      }

    "return 404 when IF returns 400 (treated as relationship not found) " +
      s"for service ${testClient.service} and user ${if (isLoggedInClientInd) "Individual" else "Business"}" in
      new LoggedInUser(false, isLoggedInClientInd, isLoggedInBusiness) {

        getActiveRelationshipFailsWith(testClient.clientId, 400)

        val result: HttpResponse = doRequest()
        result.status shouldBe 404
      }

  }

  "GET /agent/relationships/inactive/service/:service" should {
    servicesInIF.foreach { client =>
      runInactiveRelationshipsScenario(client)
      runInactiveRelationshipsErrorScenario(client)
    }

    desOnlyWithRelationshipTypeAndAuthProfile.foreach { client =>
      runInvalidCallForVatAndCgt(client)
    }
  }

  def runInactiveRelationshipsScenario(testClient: TestClient): Unit = {
    val requestPath: String = s"/agent-client-relationships/agent/relationships/inactive"
    def doRequest() = doGetRequest(requestPath)
    val fakeRequest = FakeRequest("GET", s"/agent-client-relationships/agent/relationships/inactive")

    s"return 200 with list of inactive ${testClient.service} for an Agent" in {
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      val clientType = if (testClient.clientId.isInstanceOf[MtdItId]) "personal" else "business"

      val otherId: TaxIdentifier = otherTaxIdentifier(testClient.clientId)

      getInactiveRelationshipsViaAgent(arn, otherId, testClient.clientId)

      val result = doRequest()
      result.status shouldBe 200

      (result.json \\ "arn").head.as[String] shouldBe arn.value
      (result.json \\ "dateTo").head.as[LocalDate].toString shouldBe "2015-09-21"
      (result.json \\ "clientId").head.as[String] shouldBe otherId.value
      (result.json \\ "clientType").head.as[String] shouldBe clientType
      (result.json \\ "service").head.as[String] shouldBe testClient.service

      (result.json \\ "arn")(1).as[String] shouldBe arn.value
      (result.json \\ "dateTo")(1).as[LocalDate].toString shouldBe LocalDate.now().toString
      (result.json \\ "clientId")(1).as[String] shouldBe testClient.clientId.value
      (result.json \\ "clientType")(1).as[String] shouldBe clientType
      (result.json \\ "service")(1).as[String] shouldBe testClient.service
    }
  }

  def runInactiveRelationshipsErrorScenario(testClient: TestClient): Unit = {
    val requestPath: String = s"/agent-client-relationships/agent/relationships/inactive"
    def doRequest() = doGetRequest(requestPath)
    val fakeRequest = FakeRequest("GET", s"/agent-client-relationships/agent/relationships/inactive")

    s"find relationship but filter out if the relationship is still active for ${testClient.service}" in {
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      getAgentInactiveRelationshipsButActive(arnEncoded, arn.value, testClient.clientId.value)

      val result = doRequest()
      result.status shouldBe 404
    }

    s"return 404 when DES returns not found for ${testClient.service}" in {
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      getFailAgentInactiveRelationships(arnEncoded, 404)

      val result = doRequest()
      result.status shouldBe 404
    }

    s"find relationships and filter out relationships that have no dateTo ${testClient.service}" in {
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      getAgentInactiveRelationshipsNoDateTo(arn, testClient.clientId.value)

      val result = doRequest()
      result.status shouldBe 404
    }

    s"return 404 when DES returns 400 for ${testClient.service}" in {
      givenAuthorisedAsValidAgent(fakeRequest, arn.value)

      getFailAgentInactiveRelationships(arnEncoded, 400)

      val result = doRequest()
      result.status shouldBe 404
    }
  }

  def runInvalidCallForVatAndCgt(testClient: TestClient): Unit = {

    val requestPath: String = s"/agent-client-relationships/agent/relationships/inactive"
    def doRequest() = doGetRequest(requestPath)

    s"return 404 for invalid parameters given for ${testClient.regime}" in {
      getFailWithInvalidAgentInactiveRelationships(arn.value)

      val result = doRequest()
      result.status shouldBe 404
    }
  }

  "GET /client/relationships/active" should {

    val requestPath: String = s"/agent-client-relationships/client/relationships/active"
    def doRequest(): HttpResponse = doGetRequest(requestPath)
    val fakeRequest = FakeRequest("GET", s"/agent-client-relationships/client/relationships/active")

    "return 200 with a map of relationships and filter only on active ones" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)

      // TODO WG - test Supp
      getActiveRelationshipsViaClient(mtdItId, arn)
      getActiveRelationshipsViaClient(vrn, arn2)
      getActiveRelationshipsViaClient(utr, arn)
      getActiveRelationshipsViaClient(urn, arn)
      getActiveRelationshipsViaClient(pptRef, arn)
      getActiveRelationshipsViaClient(cgtRef, arn)

      val result = doRequest()
      result.status shouldBe 200
      val response = result.json.as[JsObject]

      response should havePropertyArrayOf[String]("HMRC-MTD-IT", be(arn.value))
      response should havePropertyArrayOf[String]("HMRC-MTD-VAT", be(arn2.value))
      response should havePropertyArrayOf[String]("HMRC-TERS-ORG", be(arn.value))
      response should havePropertyArrayOf[String]("HMRC-TERSNT-ORG", be(arn.value))
      response should havePropertyArrayOf[String]("HMRC-PPT-ORG", be(arn.value))
      response should havePropertyArrayOf[String]("HMRC-CGT-PD", be(arn.value))
    }

    "return 200 with empty map of active relationships when they are found inactive" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)

      servicesInIF.foreach { notActiveClient =>
        getInactiveRelationshipViaClient(notActiveClient.clientId, arn.value)
      }

      val result = doRequest()
      result.status shouldBe 200
      val response = result.json.as[JsObject]

      servicesInIF.foreach { notActiveClient =>
        response should notHaveProperty(notActiveClient.service)
      }
    }

    "return 200 with empty map of active relationships when not found" in {
      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)

      servicesInIF.foreach { notActiveClient =>
        getActiveRelationshipFailsWith(notActiveClient.clientId, 404)
      }

      val result = doRequest()
      result.status shouldBe 200
      val response = result.json.as[JsObject]

      servicesInIF.foreach { notActiveClient =>
        response should notHaveProperty(notActiveClient.service)
      }
    }
  }

  "DELETE /agent/:arn/terminate" should {

    val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/terminate"
    def basicAuth(string: String): String = Base64.getEncoder.encodeToString(string.getBytes(UTF_8))
    def doRequest() = ws
      .url(s"http://localhost:$port$requestPath")
      .addHttpHeaders(HeaderNames.authorisation -> s"Basic ${basicAuth("username:password")}")
      .delete()

    "return 200 after successful termination" in {

      // insert records first to have some state initially
      // insert delete-record document
      await(
        deleteRecordRepository.create(
          DeleteRecord(
            arn.value,
            Some(EnrolmentKey(Service.MtdIt, MtdItId("ABCDEF0000000001"))),
            dateTime = LocalDateTime.now.minusMinutes(1),
            syncToETMPStatus = Some(SyncStatus.Success),
            syncToESStatus = Some(SyncStatus.Failed)
          )
        )
      )

      // insert copy-relationship document
      await(repo.collection.insertOne(relationshipCopiedSuccessfully).toFuture())

      val result = await(doRequest())
      result.status shouldBe 200
      val response = result.json.as[TerminationResponse]

      response shouldBe TerminationResponse(
        Seq(
          DeletionCount("agent-client-relationships", "delete-record", 1),
          DeletionCount("agent-client-relationships", "relationship-copy-record", 1)
        )
      )

      // verify termination has deleted all record for that agent
      await(deleteRecordRepository.collection.find(Filters.equal("arn", arn.value)).toFuture()) shouldBe empty
      await(repo.collection.find(Filters.equal("arn", arn.value)).toFuture()) shouldBe empty
    }
  }

  "GET /client/relationships/inactive" should {

    val requestPath: String = s"/agent-client-relationships/client/relationships/inactive"

    def doRequest(): HttpResponse = doGetRequest(requestPath)

    val fakeRequest = FakeRequest("GET", s"/agent-client-relationships/client/relationships/inactive")

    "return a sequence of inactive relationships" in {

      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)

      getInactiveRelationshipsForClient(mtdItId)
      getInactiveRelationshipsForClient(vrn)
      getInactiveRelationshipsForClient(utr)
      getInactiveRelationshipsForClient(urn)
      getInactiveRelationshipsForClient(pptRef)
      getInactiveRelationshipsForClient(cgtRef)

      val result = doRequest()

      result.status shouldBe 200

      implicit val localDateFormat: Format[LocalDate] = MongoLocalDateTimeFormat.localDateFormat

      (result.json(0) \ "arn").as[String] shouldBe "ABCDE123456"
      (result.json(0) \ "dateTo").as[LocalDate].toString shouldBe "2018-09-09"
      (result.json(0) \ "clientId").as[String] shouldBe "ABCDEF123456789"
      (result.json(0) \ "clientType").as[String] shouldBe "personal"
      (result.json(0) \ "service").as[String] shouldBe "HMRC-MTD-IT"

      (result.json(1) \ "clientId").as[String] shouldBe "101747641"
      (result.json(1) \ "service").as[String] shouldBe "HMRC-MTD-VAT"

      (result.json(2) \ "clientId").as[String] shouldBe "3087612352"
      (result.json(2) \ "service").as[String] shouldBe "HMRC-TERS-ORG"

      (result.json(3) \ "clientId").as[String] shouldBe "XXTRUST12345678"
      (result.json(3) \ "service").as[String] shouldBe "HMRC-TERSNT-ORG"

      (result.json(4) \ "clientId").as[String] shouldBe "XMCGTP123456789"
      (result.json(4) \ "service").as[String] shouldBe "HMRC-CGT-PD"

      (result.json(5) \ "clientId").as[String] shouldBe "XAPPT0004567890"
      (result.json(5) \ "service").as[String] shouldBe "HMRC-PPT-ORG"

    }

    "return OK with empty body if no inactive relationships found" in {

      givenAuthorisedAsClient(fakeRequest, mtdItId, vrn, utr, urn, pptRef, cgtRef)

      getNoInactiveRelationshipsForClient(mtdItId)
      getNoInactiveRelationshipsForClient(vrn)
      getNoInactiveRelationshipsForClient(utr)
      getNoInactiveRelationshipsForClient(urn)
      getNoInactiveRelationshipsForClient(pptRef)
      getNoInactiveRelationshipsForClient(cgtRef)

      val result = doRequest()
      result.status shouldBe 200

      result.body shouldBe "[]"
    }
  }

  "sanitising a CBC enrolment key" should {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    "work for a HMRC-CBC-ORG enrolment key with a UTR stored in the enrolment store" in {
      val validationService = app.injector.instanceOf[ValidationService]
      givenCbcUkExistsInES(cbcId, utr.value)
      await(validationService.makeSanitisedCbcEnrolmentKey(cbcId)) shouldBe
        Right(EnrolmentKey(Service.Cbc.id, Seq(Identifier("cbcId", cbcId.value), Identifier("UTR", utr.value))))
    }
    "correct the service to HMRC-CBC-NONUK-ORG if the given cbcId corresponds to non-uk in the enrolment store" in {
      val validationService = app.injector.instanceOf[ValidationService]
      givenCbcUkDoesNotExistInES(cbcId)
      givenCbcNonUkExistsInES(cbcId)
      await(validationService.makeSanitisedCbcEnrolmentKey(cbcId)) shouldBe
        Right(EnrolmentKey(Service.CbcNonUk.id, Seq(Identifier("cbcId", cbcId.value))))
    }
    "fail if there is no match in enrolment store for either HMRC-CBC-ORG or HMRC-CBC-NONUK-ORG" in {
      val validationService = app.injector.instanceOf[ValidationService]
      givenCbcUkDoesNotExistInES(cbcId)
      givenCbcNonUkDoesNotExistInES(cbcId)
      await(validationService.makeSanitisedCbcEnrolmentKey(cbcId)) should matchPattern { case Left(_) =>
      }
    }
  }
}
