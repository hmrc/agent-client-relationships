/*
 * Copyright 2017 HM Revenue & Customs
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

import com.google.inject.AbstractModule
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthRelationship
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecordRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecordRepository
import uk.gov.hmrc.agentclientrelationships.stubs._
import uk.gov.hmrc.agentclientrelationships.support.Resource
import uk.gov.hmrc.agentclientrelationships.support.TestData
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentclientrelationships.support.WireMockSupport
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDIT
import uk.gov.hmrc.agentmtdidentifiers.model.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.domain.SaAgentReference
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.test.MongoSupport

import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class TestRelationshipCopyRecordRepository @Inject() (moduleComponent: MongoComponent)
extends RelationshipCopyRecordRepository(moduleComponent) {
  override def create(record: RelationshipCopyRecord): Future[Int] = Future
    .failed(new Exception("Could not connect the mongo db."))
}

class RelationshipsControllerWithoutMongoISpec
extends UnitSpec
with MongoSupport
with GuiceOneServerPerSuite
with WireMockSupport
with RelationshipStubs
with DesStubs
with HipStub
with DesStubsGet
with MappingStubs
with DataStreamStub
with AgentAssuranceStubs
with AuthStub {

  override implicit lazy val app: Application = appBuilder.build()

  protected def appBuilder: GuiceApplicationBuilder = new GuiceApplicationBuilder()
    .configure(
      "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
      "microservice.services.tax-enrolments.port" -> wireMockPort,
      "microservice.services.users-groups-search.port" -> wireMockPort,
      "microservice.services.des.port" -> wireMockPort,
      "microservice.services.if.port" -> wireMockPort,
      "microservice.services.hip.port" -> wireMockPort,
      "microservice.services.auth.port" -> wireMockPort,
      "microservice.services.agent-mapping.port" -> wireMockPort,
      "microservice.services.agent-client-authorisation.port" -> wireMockPort,
      "microservice.services.agent-assurance.port" -> wireMockPort,
      "internal-auth.token" -> "internalAuthToken",
      "auditing.consumer.baseUri.host" -> wireMockHost,
      "auditing.consumer.baseUri.port" -> wireMockPort,
      "features.copy-relationship.mtd-vat" -> true,
      "features.recovery-enable" -> false,
      "agent.cache.expires" -> "1 millis",
      "agent.cache.enabled" -> true,
      "mongodb.uri" -> mongoUri,
      "hip.BusinessDetails.enabled" -> true
    )
    .overrides(new AbstractModule {
      override def configure(): Unit = {
        bind(classOf[RelationshipCopyRecordRepository]).to(classOf[TestRelationshipCopyRecordRepository])
        ()
      }
    })

  implicit lazy val ws: WSClient = app.injector.instanceOf[WSClient]
  implicit val request: RequestHeader = FakeRequest()

  def repo: RelationshipCopyRecordRepository = app.injector.instanceOf[RelationshipCopyRecordRepository]

  val partialAuthRepo: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]

  override def beforeEach() = {
    super.beforeEach()
    prepareDatabase()
    ()
  }

  val arn = Arn("AARN0000002")
  val mtditid = MtdItId("ABCDEF123456789")
  val mtdItEnrolmentKey: EnrolmentKey = EnrolmentKey(Service.MtdIt, mtditid)
  val nino = Nino("AB123456C")
  val vrn = Vrn("101747641")
  val vatEnrolmentKey: EnrolmentKey = EnrolmentKey(Service.Vat, vrn)
  val oldAgentCode = "oldAgentCode"
  val mtdVatIdType = "VRN"

  def partialAuthRelationship(service: String): PartialAuthRelationship = PartialAuthRelationship(
    Instant.now().truncatedTo(ChronoUnit.SECONDS),
    arn.value,
    service,
    nino.value,
    active = true,
    lastUpdated = Instant.now().truncatedTo(ChronoUnit.SECONDS)
  )

  "GET /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:identifierValue" should {

    val requestPath = s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT/client/MTDITID/${mtditid.value}"

    "return 200 when relationship exists only in cesa and relationship copy attempt fails because of mongo" in {
      givenPrincipalAgentUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtditid)
      givenNinoIsKnownFor(mtditid, nino)
      givenMtdItIdIsKnownFor(nino, mtditid)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenAgentCanBeAllocated(mtditid, arn)
      givenMTDITEnrolmentAllocationSucceeds(mtditid, "bar")
      givenAuditConnector()
      givenAdminUser("foo", "any")

      await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None

      val result = doAgentRequest(requestPath)
      result.status shouldBe 200

      await(repo.findBy(arn, mtdItEnrolmentKey)) shouldBe None

      verifyAuditRequestNotSent(AgentClientRelationshipEvent.CreateRelationship)

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "agentReferenceNumber" -> arn.value,
          "nino" -> nino.value,
          "saAgentRef" -> "foo",
          "cesaRelationship" -> "true"
        ),
        tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
      )
    }
  }

  "GET /agent/:arn/service/HMRC-MTD-VAT/client/VRN/:vrn" should {

    val requestPath = s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-VAT/client/VRN/${vrn.value}"

    "return 200 when relationship exists mapping and es and relationship copy attempt fails because of mongo" in {
      givenPrincipalAgentUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
      givenAgentCanBeAllocated(vrn, arn)
      givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")
      givenAuditConnector()
      givenAdminUser("foo", "any")
      getVrnIsKnownInETMPFor(vrn)

      await(repo.findBy(arn, vatEnrolmentKey)) shouldBe empty

      val result = doAgentRequest(requestPath)
      result.status shouldBe 200

      await(repo.findBy(arn, vatEnrolmentKey)) shouldBe empty

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "credId" -> "any",
          "agentCode" -> "bar",
          "agentReferenceNumber" -> arn.value,
          "service" -> "mtd-vat",
          "vrn" -> vrn.value,
          "oldAgentCodes" -> oldAgentCode,
          "ESRelationship" -> "true",
          "etmpRelationshipCreated" -> "false",
          "enrolmentDelegated" -> "false",
          "howRelationshipCreated" -> "CopyExistingESRelationship",
          "vrnExistsInEtmp" -> "true"
        ),
        tags = Map("transactionName" -> "create-relationship", "path" -> requestPath)
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckES,
        detail = Map(
          "agentReferenceNumber" -> arn.value,
          "ESRelationship" -> "true",
          "vrn" -> vrn.value,
          "oldAgentCodes" -> oldAgentCode
        ),
        tags = Map("transactionName" -> "check-es", "path" -> requestPath)
      )
    }
  }

  "GET /agent/:arn/service/IR-SA/client/ni/:identifierValue" should {

    val requestPath = s"/agent-client-relationships/agent/${arn.value}/service/IR-SA/client/ni/$nino"

    val enrolmentKey: EnrolmentKey = EnrolmentKey("IR-SA", Seq(Identifier("NINO", nino.value)))

    "return 200 when relationship exists only in cesa and relationship copy is never attempted" in {
      givenAgentRecordFound(arn, agentRecordResponse)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenAuditConnector()

      await(repo.findBy(arn, enrolmentKey)) shouldBe None

      val result = doAgentRequest(requestPath)
      result.status shouldBe 200

      await(repo.findBy(arn, enrolmentKey)) shouldBe None

      verifyAuditRequestNotSent(event = AgentClientRelationshipEvent.CreateRelationship)

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "agentReferenceNumber" -> arn.value,
          "nino" -> nino.value,
          "saAgentRef" -> "foo",
          "cesaRelationship" -> "true"
        ),
        tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
      )
    }

    "return 200 when relationship does not exist in CESA but there is a PartialAuth for main agent type" in {
      givenAgentRecordFound(arn, agentRecordResponse)
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
      givenAuditConnector()
      await(partialAuthRepo.collection.insertOne(partialAuthRelationship(HMRCMTDIT)).toFuture())

      await(repo.findBy(arn, enrolmentKey)) shouldBe None

      val result = doAgentRequest(requestPath)
      result.status shouldBe 200

      await(repo.findBy(arn, enrolmentKey)) shouldBe None

      verifyAuditRequestNotSent(event = AgentClientRelationshipEvent.CreateRelationship)

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "agentReferenceNumber" -> arn.value,
          "nino" -> nino.value,
          "cesaRelationship" -> "false",
          "partialAuth" -> "true"
        ),
        tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
      )
    }

    "return 200 when relationship does not exist in CESA but there is a PartialAuth for supporting agent type" in {
      givenAgentRecordFound(arn, agentRecordResponse)
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
      givenAuditConnector()
      await(partialAuthRepo.collection.insertOne(partialAuthRelationship(HMRCMTDITSUPP)).toFuture())

      await(repo.findBy(arn, enrolmentKey)) shouldBe None

      val result = doAgentRequest(requestPath)
      result.status shouldBe 200

      await(repo.findBy(arn, enrolmentKey)) shouldBe None

      verifyAuditRequestNotSent(event = AgentClientRelationshipEvent.CreateRelationship)

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "agentReferenceNumber" -> arn.value,
          "nino" -> nino.value,
          "cesaRelationship" -> "false",
          "partialAuth" -> "true"
        ),
        tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
      )
    }

    "return 404 when relationship does not exist in CESA and there is no PartialAuth" in {
      givenAgentRecordFound(arn, agentRecordResponse)
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
      givenAuditConnector()

      await(repo.findBy(arn, enrolmentKey)) shouldBe empty

      val result = doAgentRequest(requestPath)
      result.status shouldBe 404

      await(repo.findBy(arn, enrolmentKey)) shouldBe empty

      verifyAuditRequestNotSent(event = AgentClientRelationshipEvent.CreateRelationship)

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "agentReferenceNumber" -> arn.value,
          "nino" -> nino.value,
          "cesaRelationship" -> "false",
          "partialAuth" -> "false"
        ),
        tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
      )
    }
  }

  private def doAgentRequest(route: String) = new Resource(route, port).get()

}
