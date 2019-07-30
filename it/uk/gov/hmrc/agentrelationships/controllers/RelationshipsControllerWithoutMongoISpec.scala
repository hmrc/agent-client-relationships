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

package uk.gov.hmrc.agentrelationships.controllers

import javax.inject.Inject
import com.google.inject.AbstractModule
import org.scalatestplus.play.OneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.repository.{MongoRelationshipCopyRecordRepository, RelationshipCopyRecord, RelationshipCopyRecordRepository}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.agentrelationships.stubs.{DataStreamStub, DesStubs, MappingStubs, RelationshipStubs}
import uk.gov.hmrc.agentrelationships.support.{MongoApp, Resource, WireMockSupport}
import uk.gov.hmrc.domain.{AgentCode, Nino, SaAgentReference, TaxIdentifier}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class TestRelationshipCopyRecordRepository @Inject()(moduleComponent: ReactiveMongoComponent)
    extends MongoRelationshipCopyRecordRepository(moduleComponent) {
  override def create(record: RelationshipCopyRecord)(implicit ec: ExecutionContext): Future[Int] =
    Future.failed(new Exception("Could not connect the mongo db."))
}

class RelationshipsControllerWithoutMongoISpec
    extends UnitSpec
    with MongoApp
    with OneServerPerSuite
    with WireMockSupport
    with RelationshipStubs
    with DesStubs
    with MappingStubs
    with DataStreamStub {

  override implicit lazy val app: Application = appBuilder
    .build()

  protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
        "microservice.services.tax-enrolments.port"        -> wireMockPort,
        "microservice.services.users-groups-search.port"   -> wireMockPort,
        "microservice.services.des.port"                   -> wireMockPort,
        "microservice.services.auth.port"                  -> wireMockPort,
        "microservice.services.agent-mapping.port"         -> wireMockPort,
        "auditing.consumer.baseUri.host"                   -> wireMockHost,
        "auditing.consumer.baseUri.port"                   -> wireMockPort,
        "features.recovery-enable"                         -> false
      )
      .configure(mongoConfiguration)
      .overrides(new AbstractModule {
        override def configure(): Unit =
          bind(classOf[RelationshipCopyRecordRepository]).to(classOf[TestRelationshipCopyRecordRepository])
      })

  implicit lazy val ws: WSClient = app.injector.instanceOf[WSClient]
  implicit val hc: HeaderCarrier = HeaderCarrier()

  def repo = app.injector.instanceOf[MongoRelationshipCopyRecordRepository]

  override def beforeEach() {
    super.beforeEach()
    await(repo.ensureIndexes)
  }

  val arn = Arn("AARN0000002")
  val mtditid = MtdItId("ABCDEF123456789")
  val nino = Nino("AB123456C")
  val vrn = Vrn("101747641")
  val oldAgentCode = "oldAgentCode"
  val mtdVatIdType = "VRN"

  "GET /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:identifierValue" should {

    val requestPath =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT/client/MTDITID/${mtditid.value}"

    //val identifier: TaxIdentifier = mtditid
    val identifierType: String = "MTDITID"

    "return 200 when relationship exists only in cesa and relationship copy attempt fails because of mongo" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtditid)
      givenNinoIsKnownFor(mtditid, nino)
      givenMtdItIdIsKnownFor(nino, mtditid)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenAgentCanBeAllocatedInDes(mtditid, arn)
      givenMTDITEnrolmentAllocationSucceeds(mtditid, "bar")
      givenAuditConnector()
      givenUserIdIsAdmin("any")

      def query =
        repo.find("arn" -> arn.value, "clientIdentifier" -> mtditid.value, "clientIdentifierType" -> identifierType)

      await(query) shouldBe empty

      val result = await(doAgentRequest(requestPath))
      result.status shouldBe 200

      await(query) shouldBe empty

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn"                     -> arn.value,
          "credId"                  -> "any",
          "agentCode"               -> "bar",
          "nino"                    -> nino.value,
          "saAgentRef"              -> "foo",
          "service"                 -> "mtd-it",
          "clientId"                -> mtditid.value,
          "clientIdType"            -> "mtditid",
          "CESARelationship"        -> "true",
          "etmpRelationshipCreated" -> "false",
          "enrolmentDelegated"      -> "false",
          "AgentDBRecord"           -> "false",
          "Journey"                 -> "CopyExistingCESARelationship"
        ),
        tags = Map("transactionName" -> "create-relationship", "path" -> requestPath)
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map(
          "arn"                      -> arn.value,
          "credId"                   -> "any",
          "agentCode"                -> "bar",
          "nino"                     -> nino.value,
          "saAgentRef"               -> "foo",
          "CESARelationship"         -> "true"),
        tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
      )
    }
  }

  "GET /agent/:arn/service/HMRC-MTD-VAT/client/VRN/:vrn" should {

    val requestPath = s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-VAT/client/VRN/${vrn.value}"

    "return 200 when relationship exists mapping and es and relationship copy attempt fails because of mongo" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
      givenAgentCanBeAllocatedInDes(vrn, arn)
      givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")
      givenAuditConnector()
      givenUserIdIsAdmin("any")

      def query = repo.find("arn" -> arn.value, "clientIdentifier" -> vrn, "clientIdentifierType" -> mtdVatIdType)

      await(query) shouldBe empty

      val result = await(doAgentRequest(requestPath))
      result.status shouldBe 200

      await(query) shouldBe empty

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn"                     -> arn.value,
          "credId"                  -> "any",
          "agentCode"               -> "bar",
          "service"                 -> "mtd-vat",
          "vrn"                     -> vrn.value,
          "oldAgentCodes"           -> oldAgentCode,
          "ESRelationship"          -> "true",
          "etmpRelationshipCreated" -> "false",
          "enrolmentDelegated"      -> "false",
          "AgentDBRecord"           -> "false",
          "Journey"                 -> "CopyExistingESRelationship"
        ),
        tags = Map("transactionName" -> "create-relationship", "path" -> requestPath)
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckES,
        detail = Map(
          "arn"                      -> arn.value,
          "credId"                   -> "any",
          "agentCode"                -> "bar",
          "ESRelationship"           -> "true",
          "vrn"                      -> vrn.value,
          "oldAgentCodes"            -> oldAgentCode),
        tags = Map("transactionName" -> "check-es", "path" -> requestPath)
      )
    }
  }

  "GET /agent/:arn/service/IR-SA/client/ni/:identifierValue" should {

    val requestPath = s"/agent-client-relationships/agent/${arn.value}/service/IR-SA/client/ni/$nino"

    val identifier: TaxIdentifier = nino
    val identifierType: String = "NINO"

    "return 200 when relationship exists only in cesa and relationship copy is never attempted" in {
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenAuditConnector()

      def query =
        repo.find("arn" -> arn.value, "clientIdentifier" -> identifier.value, "clientIdentifierType" -> identifierType)

      await(query) shouldBe empty

      val result = await(doAgentRequest(requestPath))
      result.status shouldBe 200

      await(query) shouldBe empty

      verifyAuditRequestNotSent(event = AgentClientRelationshipEvent.CreateRelationship)

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckCESA,
        detail = Map("arn"           -> arn.value, "nino"    -> nino.value, "saAgentRef" -> "foo", "CESARelationship" -> "true"),
        tags = Map("transactionName" -> "check-cesa", "path" -> requestPath)
      )
    }
  }

  private def doAgentRequest(route: String) = new Resource(route, port).get()

}
