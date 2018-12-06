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

import org.joda.time.LocalDate
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.OneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.api.test.FakeRequest
import play.utils.UriEncoding
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipReference.VatRef
import uk.gov.hmrc.agentclientrelationships.repository.{MongoRelationshipCopyRecordRepository, RelationshipCopyRecord, SyncStatus}
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.agentrelationships.stubs._
import uk.gov.hmrc.agentrelationships.support._
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.domain.AgentCode
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

//noinspection ScalaStyle
class RelationshipsControllerVATISpec
    extends UnitSpec
    with MongoApp
    with OneServerPerSuite
    with WireMockSupport
    with RelationshipStubs
    with DesStubs
    with DesStubsGet
    with MappingStubs
    with DataStreamStub
    with AuthStub
    with MockitoSugar {

  override lazy val port: Int = Random.nextInt(1000) + 19000

  lazy val mockAuthConnector = mock[PlayAuthConnector]
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
        "features.copy-relationship.mtd-it"                -> true,
        "features.copy-relationship.mtd-vat"               -> true,
        "features.recovery-enable"                         -> false
      )
      .configure(mongoConfiguration)

  implicit lazy val ws: WSClient = app.injector.instanceOf[WSClient]
  implicit val hc: HeaderCarrier = HeaderCarrier()

  def repo = app.injector.instanceOf[MongoRelationshipCopyRecordRepository]

  override def beforeEach() {
    super.beforeEach()
    givenAuditConnector()
    await(repo.ensureIndexes)
  }

  val arn = Arn("AARN0000002")
  val arn2 = Arn("AARN0000004")
  val arn3 = Arn("AARN0000006")
  val vrn = Vrn("101747641")
  val mtdVatIdType = "VRN"
  val oldAgentCode = "oldAgentCode"
  val testAgentUser = "testAgentUser"
  val testAgentGroup = "testAgentGroup"

  val STRIDE_ROLE = "Maintain Agent client relationships"

  val relationshipCopiedSuccessfullyForMtdVat = RelationshipCopyRecord(
    arn.value,
    vrn.value,
    mtdVatIdType,
    syncToETMPStatus = Some(SyncStatus.Success),
    syncToESStatus = Some(SyncStatus.Success))

  "GET /agent/:arn/service/HMRC-MTD-VAT/client/VRN/:vrn" should {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-VAT/client/VRN/${vrn.value}"

    def doRequest = doAgentGetRequest(requestPath)

    //HAPPY PATH :-)

    "return 200 when relationship exists in es" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

      await(query()) shouldBe empty
      val result = await(doRequest)
      result.status shouldBe 200
      await(query()) shouldBe empty
    }

    //UNHAPPY PATHS

    "return 404 when credentials are not found in es" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")
      givenDelegatedGroupIdsNotExistForKey(s"HMCE-VATDEC-ORG~VATRegNo~${vrn.value}")

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent code is not found in ugs" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoNotExists("foo")
      givenDelegatedGroupIdsExistFor(vrn, Set("foo"))
      givenDelegatedGroupIdsNotExistForKey(s"HMCE-VATDEC-ORG~VATRegNo~${vrn.value}")

      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    //FAILURE CASES

    "return 502 when ES1/principal returns 5xx" in {
      givenPrincipalGroupIdRequestFailsWith(500)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")

      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 502 when UGS returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(500)
      givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")

      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 502 when ES1/delegated returns 5xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")

      givenDelegatedGroupIdRequestFailsWith(500)
      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 400 when ES1/principal returns 4xx" in {
      givenPrincipalGroupIdRequestFailsWith(400)
      givenGroupInfo("foo", "bar")
      givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")

      val result = await(doRequest)
      result.status shouldBe 400
    }

    "return 400 when UGS returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoFailsWith(400)
      givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")

      val result = await(doRequest)
      result.status shouldBe 400
    }

    "return 400 when ES1/delegated returns 4xx" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdRequestFailsWith(400)

      val result = await(doRequest)
      result.status shouldBe 400
    }

    //HAPPY PATHS WHEN CHECKING HMRC-VATDEC-ORG

    "return 200 when agent not allocated to client in es but relationship exists in HMCE-VATDEC-ORG" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
      givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
      givenAgentCanBeAllocatedInDes(vrn, arn)
      givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (vrn.value),
        'clientIdentifierType (mtdVatIdType),
        'references (Some(Set(VatRef(AgentCode(oldAgentCode))))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToESStatus (Some(SyncStatus.Success))
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn"                     -> arn.value,
          "credId"                  -> "any",
          "agentCode"               -> "bar",
          "oldAgentCodes"           -> oldAgentCode,
          "service"                 -> "mtd-vat",
          "vrn"                     -> vrn.value,
          "ESRelationship"          -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated"      -> "true",
          "AgentDBRecord"           -> "true",
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
          "oldAgentCodes"            -> oldAgentCode,
          "vrn"                      -> vrn.value,
          "ESRelationship"           -> "true"),
        tags = Map("transactionName" -> "check-es", "path" -> requestPath)
      )
    }

    "return 200 when agent credentials unknown but relationship exists in HMCE-VATDEC-ORG" in {
      givenPrincipalGroupIdNotExistsFor(arn)
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
      givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
      givenAgentCanBeAllocatedInDes(vrn, arn)

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (vrn.value),
        'clientIdentifierType (mtdVatIdType),
        'references (Some(Set(VatRef(AgentCode(oldAgentCode))))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToESStatus (Some(SyncStatus.IncompleteInputParams))
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn"                     -> arn.value,
          "credId"                  -> "",
          "agentCode"               -> "",
          "service"                 -> "mtd-vat",
          "vrn"                     -> vrn.value,
          "oldAgentCodes"           -> oldAgentCode,
          "ESRelationship"          -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated"      -> "false",
          "AgentDBRecord"           -> "true",
          "Journey"                 -> "CopyExistingESRelationship"
        ),
        tags = Map("transactionName" -> "create-relationship", "path" -> requestPath)
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckES,
        detail = Map(
          "arn"                      -> arn.value,
          "vrn"                      -> vrn.value,
          "oldAgentCodes"            -> oldAgentCode,
          "credId"                   -> "",
          "agentCode"                -> "",
          "ESRelationship"           -> "true"),
        tags = Map("transactionName" -> "check-es", "path" -> requestPath)
      )
    }

    "return 200 when agent code unknown but relationship exists in HMCE-VATDEC-ORG" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfoNotExists("foo")
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
      givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
      givenAgentCanBeAllocatedInDes(vrn, arn)
      givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (vrn.value),
        'clientIdentifierType (mtdVatIdType),
        'references (Some(Set(VatRef(AgentCode(oldAgentCode))))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToESStatus (Some(SyncStatus.IncompleteInputParams))
      )

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CreateRelationship,
        detail = Map(
          "arn"                     -> arn.value,
          "credId"                  -> "any",
          "agentCode"               -> "",
          "service"                 -> "mtd-vat",
          "vrn"                     -> vrn.value,
          "oldAgentCodes"           -> oldAgentCode,
          "ESRelationship"          -> "true",
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated"      -> "false",
          "AgentDBRecord"           -> "true",
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
          "vrn"                      -> vrn.value,
          "oldAgentCodes"            -> oldAgentCode,
          "agentCode"                -> "",
          "ESRelationship"           -> "true"),
        tags = Map("transactionName" -> "check-es", "path" -> requestPath)
      )

    }

    //HAPPY PATHS WHEN RELATIONSHIP COPY ATTEMPT FAILS

    "return 200 when relationship exists only in HMCE-VATDEC-ORG and relationship copy attempt fails because of etmp" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
      givenAgentCanNotBeAllocatedInDes(status = 404)
      givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (vrn.value),
        'clientIdentifierType (mtdVatIdType),
        'references (Some(Set(VatRef(AgentCode(oldAgentCode))))),
        'syncToETMPStatus (Some(SyncStatus.Failed)),
        'syncToESStatus (None)
      )
    }

    "return 200 when relationship exists only in HMCE-VATDEC-ORG and relationship copy attempt fails because of es" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
      givenAgentCanBeAllocatedInDes(vrn, arn)
      givenEnrolmentAllocationFailsWith(404)("foo", "any", "HMRC-MTD-VAT", "VRN", vrn.value, "bar")

      def query() =
        repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

      await(query()) shouldBe empty

      val result = await(doRequest)
      result.status shouldBe 200

      await(query()).head should have(
        'arn (arn.value),
        'clientIdentifier (vrn.value),
        'clientIdentifierType (mtdVatIdType),
        'references (Some(Set(VatRef(AgentCode(oldAgentCode))))),
        'syncToETMPStatus (Some(SyncStatus.Success)),
        'syncToESStatus (Some(SyncStatus.Failed))
      )

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
          "etmpRelationshipCreated" -> "true",
          "enrolmentDelegated"      -> "false",
          "AgentDBRecord"           -> "true",
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

    "return 404 when relationship is not found in es but relationship copy was made before" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)

      await(repo.insert(relationshipCopiedSuccessfullyForMtdVat))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when relationship was previously copied from HMCE-VATDEC-ORG to ETMP & ES but has since been deleted from ETMP & ES " +
      "(even though the relationship upon which the copy was based still exists in HMCE-VATDEC-ORG)" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)

      givenAgentCanBeAllocatedInDes(vrn, arn)
      givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")

      await(repo.insert(relationshipCopiedSuccessfullyForMtdVat))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when credentials are not found but relationship copy was made before" in {
      givenPrincipalGroupIdNotExistsFor(arn)

      await(repo.insert(relationshipCopiedSuccessfullyForMtdVat))
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "UNKNOWN_ARN"
    }

    "return 502 when mapping service is unavailable" in {
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
      givenServiceReturnsServiceUnavailable()

      val result = await(doRequest)
      result.status shouldBe 502
    }
  }

  "GET /agent/:arn/service/HMCE-VATDEC-ORG/client/vrn/:vrn" should {

    val requestPath = s"/agent-client-relationships/agent/${arn.value}/service/HMCE-VATDEC-ORG/client/vrn/${vrn.value}"

    def doRequest = doAgentGetRequest(requestPath)

    "return 404 when agent not allocated to client in es" in {
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenDelegatedGroupIdsNotExistForKey(s"HMCE-VATDEC-ORG~VATRegNo~${vrn.value}")
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 404 when agent not allocated to client in agent-mapping but allocated in es" in {
      givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")
      givenArnIsUnknownFor(arn)
      givenDelegatedGroupIdsNotExistForKey(s"HMCE-VATDEC-ORG~VATRegNo~${vrn.value}")
      val result = await(doRequest)
      result.status shouldBe 404
      (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
    }

    "return 5xx mapping is unavailable" in {
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, "foo")
      givenServiceReturnsServiceUnavailable()

      val result = await(doRequest)
      result.status shouldBe 502
    }

    "return 200 when agent credentials unknown but relationship exists in mapping" in {
      givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
      givenArnIsKnownFor(arn, AgentCode(oldAgentCode))

      val result = await(doRequest)
      result.status shouldBe 200

      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.CheckES,
        detail = Map(
          "arn"                      -> arn.value,
          "vrn"                      -> vrn.value,
          "oldAgentCodes"            -> oldAgentCode,
          "credId"                   -> "",
          "agentCode"                -> "",
          "ESRelationship"           -> "true"),
        tags = Map("transactionName" -> "check-es", "path" -> requestPath)
      )
    }
  }

  "DELETE /agent/:arn/service/HMRC-MTD-VAT/client/VRN/:vrn" when {

    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-VAT/client/VRN/${vrn.value}"

    def verifyClientRemovedAgentServiceAuthorisationAuditSent(
      arn: String,
      clientId: String,
      clientIdType: String,
      service: String,
      currentUserAffinityGroup: String,
      authProviderId: String,
      authProviderIdType: String) =
      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation,
        detail = Map(
          "agentReferenceNumber"     -> arn,
          "clientId"                 -> clientId,
          "clientIdType"             -> clientIdType,
          "service"                  -> service,
          "currentUserAffinityGroup" -> currentUserAffinityGroup,
          "authProviderId"           -> authProviderId,
          "authProviderIdType"       -> authProviderIdType
        ),
        tags = Map("transactionName" -> "client terminated agent:service authorisation", "path" -> requestPath)
      )

    def verifyHmrcRemovedAgentServiceAuthorisation(
      arn: String,
      clientId: String,
      service: String,
      authProviderId: String,
      authProviderIdType: String) =
      verifyAuditRequestSent(
        1,
        event = AgentClientRelationshipEvent.HmrcRemovedAgentServiceAuthorisation,
        detail = Map(
          "authProviderId"       -> authProviderId,
          "authProviderIdType"   -> authProviderIdType,
          "agentReferenceNumber" -> arn,
          "clientId"             -> clientId,
          "service"              -> service
        ),
        tags = Map("transactionName" -> "hmrc remove agent:service authorisation", "path" -> requestPath)
      )

    "the relationship exists and the Arn matches that of current Agent user" should {

      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn, withThisGgUserId = "ggUserId-agent")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")
        givenAgentCanBeDeallocatedInDes(vrn, arn)
        givenEnrolmentDeallocationSucceeds("foo", vrn)
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send an audit event called ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          vrn.value,
          "Vrn",
          "HMRC-MTD-VAT",
          "Agent",
          "ggUserId-agent",
          "GovernmentGateway")
      }
    }

    "the relationship exists and the MtdItId matches that of current Client user" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(vrn, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")
        givenAgentCanBeDeallocatedInDes(vrn, arn)
        givenEnrolmentDeallocationSucceeds("foo", vrn)
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          vrn.value,
          "Vrn",
          "HMRC-MTD-VAT",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship exists and the user is authenticated with Stride" should {
      trait StubsForThisScenario {
        givenUserIsAuthenticatedWithStride(STRIDE_ROLE, "strideId-1234456")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")
        givenAgentCanBeDeallocatedInDes(vrn, arn)
        givenEnrolmentDeallocationSucceeds("foo", vrn)
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send the audit event HmrcRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyHmrcRemovedAgentServiceAuthorisation(
          arn.value,
          vrn.value,
          "HMRC-MTD-VAT",
          "strideId-1234456",
          "PrivilegedApplication")
      }
    }

    "the relationship exists in ETMP and not exist in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(vrn, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(vrn, "clientGroupId")
        givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
        givenAgentCanBeDeallocatedInDes(vrn, arn)
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          vrn.value,
          "Vrn",
          "HMRC-MTD-VAT",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship does not exist in either ETMP or in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(vrn, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(vrn, "clientGroupId")
        givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
        givenAgentHasNoActiveRelationshipInDes(vrn, arn)
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          vrn.value,
          "Vrn",
          "HMRC-MTD-VAT",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    "the relationship does not exist in ETMP but does exist in ES" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedClient(vrn, withThisGgUserId = "ggUserId-client")
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")
        givenAgentHasNoActiveRelationshipInDes(vrn, arn)
        givenEnrolmentDeallocationSucceeds("foo", vrn)
      }

      "return 204" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 204
      }

      "send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyClientRemovedAgentServiceAuthorisationAuditSent(
          arn.value,
          vrn.value,
          "Vrn",
          "HMRC-MTD-VAT",
          "Individual",
          "ggUserId-client",
          "GovernmentGateway")
      }
    }

    /**
      * Agent's Unhappy paths
      */
    "agent has a mismatched arn" should {
      "return 403" in {
        givenUserIsSubscribedAgent(Arn("unmatched"))
        await(doAgentDeleteRequest(requestPath)).status shouldBe 403
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserIsSubscribedAgent(Arn("unmatched"))
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "agent has no agent enrolments" should {
      "return 403" in {
        givenUserHasNoAgentEnrolments(arn)
        await(doAgentDeleteRequest(requestPath)).status shouldBe 403
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserHasNoAgentEnrolments(arn)
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "es is unavailable" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenEsIsUnavailable()
        givenAgentCanBeDeallocatedInDes(vrn, arn)
      }

      "return 502" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 502
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "DES is unavailable" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(vrn, "clientGroupId")
        givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")
        givenDesReturnsServiceUnavailable()
      }

      "return 502" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 502
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "DES responds with 404" should {
      trait StubsForThisScenario {
        givenUserIsSubscribedAgent(arn)
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenPrincipalGroupIdExistsFor(vrn, "clientGroupId")
        givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")
        givenAgentCanNotBeDeallocatedInDes(status = 404)
      }

      "return 404" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 404
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForThisScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    /**
      * Client's Unhappy paths
      */
    "client has a mismatched MtdItId" should {

      "return 403" in {
        givenUserIsSubscribedClient(MtdItId("unmatched"))
        await(doAgentDeleteRequest(requestPath)).status shouldBe 403
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserIsSubscribedClient(MtdItId("unmatched"))
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "client has no client enrolments" should {
      "return 403" in {
        givenUserHasNoClientEnrolments
        await(doAgentDeleteRequest(requestPath)).status shouldBe 403
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in {
        givenUserHasNoClientEnrolments
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestNotSent(AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }

    "client has no groupId" should {
      trait StubsForScenario {
        givenUserIsSubscribedClient(vrn)
        givenPrincipalGroupIdNotExistsFor(vrn)
      }

      "return 404" in new StubsForScenario {
        await(doAgentDeleteRequest(requestPath)).status shouldBe 404
      }

      "not send the audit event ClientRemovedAgentServiceAuthorisation" in new StubsForScenario {
        await(doAgentDeleteRequest(requestPath))
        verifyAuditRequestSent(1, AgentClientRelationshipEvent.ClientTerminatedAgentServiceAuthorisation)
      }
    }
  }

  "DELETE /test-only/db/agent/:arn/service/HMRC-MTD-VAT/client/VRN/:vrn" should {

    val requestPath: String = s"/test-only/db/agent/${arn.value}/service/HMRC-MTD-VAT/client/VRN/$vrn"

    "return 404 for any call" in {

      await(repo.create(RelationshipCopyRecord(arn.value, vrn.value, mtdVatIdType))) shouldBe 1
      val result = await(doAgentDeleteRequest(requestPath))
      result.status shouldBe 404
    }
  }

  "PUT /agent/:arn/service/HMRC-MTD-VAT/client/VRN/:vrn" should {
    val vrn = Vrn("101747641")
    val requestPath: String =
      s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-VAT/client/VRN/${vrn.value}"

    "return 201 when the relationship exists and the Arn matches that of current Agent user" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsExistForMtdVatId(vrn)
      givenAgentCanBeAllocatedInDes(vrn, arn)
      givenEnrolmentExistsForGroupId("bar", Arn("barArn"))
      givenEnrolmentExistsForGroupId("foo", Arn("fooArn"))
      givenEnrolmentDeallocationSucceeds("foo", vrn)
      givenEnrolmentDeallocationSucceeds("bar", vrn)
      givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and the Vrn matches that of current Client user" in {
      givenUserIsSubscribedClient(vrn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenAgentCanBeAllocatedInDes(vrn, arn)
      givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when the relationship exists and previous relationships too but ARNs not found" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsExistForMtdVatId(vrn)
      givenAgentCanBeAllocatedInDes(vrn, arn)
      givenEnrolmentNotExistsForGroupId("bar")
      givenEnrolmentNotExistsForGroupId("foo")
      givenEnrolmentDeallocationSucceeds("foo", vrn)
      givenEnrolmentDeallocationSucceeds("bar", vrn)
      givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    "return 201 when there are no previous relationships to deallocate" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenAgentCanBeAllocatedInDes(vrn, arn)
      givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 201
    }

    /**
      * Agent's Unhappy paths
      */
    "return 403 for an agent with a mismatched arn" in {
      givenUserIsSubscribedAgent(Arn("unmatched"))

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }

    "return 403 for an agent with no agent enrolments" in {
      givenUserHasNoAgentEnrolments(arn)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }

    "return 502 when ES1 is unavailable" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdRequestFailsWith(503)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 502
    }

    "return 502 when ES8 is unavailable" in {
      givenUserIsSubscribedClient(vrn)
      givenPrincipalUser(arn, "foo", userId = "user1")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenAgentCanBeAllocatedInDes(vrn, arn)

      givenEnrolmentAllocationFailsWith(503)(
        groupId = "foo",
        clientUserId = "user1",
        key = "HMRC-MTD-VAT",
        identifier = "VRN",
        value = vrn.value,
        agentCode = "bar")

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 502
      (result.json \ "message").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_ES")
    }

    "return 502 when DES is unavailable" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo", userId = "user1")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenDesReturnsServiceUnavailable()

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 502
      (result.json \ "message").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_DES")
    }

    "return 404 if DES returns 404" in {
      givenUserIsSubscribedAgent(arn)
      givenPrincipalUser(arn, "foo", userId = "user1")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
      givenAgentCanNotBeAllocatedInDes(status = 404)

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 404
      (result.json \ "code").asOpt[String] shouldBe Some("RELATIONSHIP_CREATE_FAILED_DES")
    }

    /**
      * Client's Unhappy paths
      */
    "return 403 for a client with a mismatched Vrn" in {
      givenUserIsSubscribedClient(Vrn("unmatched"))

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }

    "return 403 for a client with no client enrolments" in {
      givenUserHasNoClientEnrolments

      val result = await(doAgentPutRequest(requestPath))
      result.status shouldBe 403
    }
  }

  "GET /relationships/service/HMRC-MTD-VAT" should {
    val vrnEncoded = UriEncoding.encodePathSegment(vrn.value, "UTF-8")
    val requestPath: String = s"/agent-client-relationships/relationships/service/HMRC-MTD-VAT"

    def doRequest = doAgentGetRequest(requestPath)
    val req = FakeRequest()

    "find relationship and send back Json" in {
      authorisedAsClientVat(req, vrn.value)
      givenAuditConnector()
      getClientActiveAgentRelationshipsVat(vrnEncoded, arn.value)

      val result = await(doRequest)
      result.status shouldBe 200

      val b = result.json
      (result.json \ "arn").get.as[String] shouldBe arn.value
      (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
    }

    "find relationship but filter out if the end date has been changed from 9999-12-31" in {
      authorisedAsClientVat(req, vrn.value)
      givenAuditConnector()
      getClientActiveButEndedAgentRelationshipsVat(vrnEncoded, arn.value)

      val result = await(doRequest)
      result.status shouldBe 404
    }

    "find multiple relationships but filter out active and ended relationships" in {
      authorisedAsClientVat(req, vrn.value)
      givenAuditConnector()
      getClientActiveButSomeEndedAgentRelationshipsVat(vrnEncoded, arn.value, arn2.value, arn3.value)

      val result = await(doRequest)
      result.status shouldBe 200
      (result.json \ "arn").get.as[String] shouldBe arn3.value
      (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
    }

    "return 404 when DES returns 404 relationship not found" in {
      authorisedAsClientVat(req, vrn.value)
      givenAuditConnector()
      getFailClientActiveAgentRelationshipsVat(vrnEncoded, status = 404)

      val result = await(doRequest)
      result.status shouldBe 404
    }

    "return 404 when DES returns 400 (treated as relationship not found)" in {
      authorisedAsClientVat(req, vrn.value)
      givenAuditConnector()
      getFailClientActiveAgentRelationshipsVat(vrnEncoded, status = 400)

      val result = await(doRequest)
      result.status shouldBe 404
    }
  }

  "GET /relationships/inactive/service/HMRC-MTD-VAT" should {

    val arnEncoded = UriEncoding.encodePathSegment(arn.value, "UTF-8")
    val requestPath: String = s"/agent-client-relationships/relationships/inactive/service/HMRC-MTD-VAT"

    def doRequest = doAgentGetRequest(requestPath)
    val req = FakeRequest()

    "find relationship and send it back as Json" in {
      authorisedAsValidAgent(req, arn.value)
      givenAuditConnector()
      getAgentInactiveRelationships(arnEncoded, arn.value, "VATC")

      val result = await(doRequest)
      result.status shouldBe 200

      val b = result.json
      (result.json \\ "arn").head.as[String] shouldBe arn.value
      (result.json \\ "dateTo").head.as[LocalDate].toString() shouldBe "2015-09-21"
      (result.json \\ "referenceNumber").head.as[String] shouldBe "ABCDE1234567890"
    }

    "find relationship but filter out if the relationship is still active" in {
      authorisedAsValidAgent(req, arn.value)
      givenAuditConnector()
      getAgentInactiveRelationshipsButActive(arnEncoded, arn.value, "VATC")

      val result = await(doRequest)
      result.status shouldBe 404
    }

    "find relationships and filter out relationships that have no dateTo" in {
      authorisedAsValidAgent(req, arn.value)
      givenAuditConnector()
      getAgentInactiveRelationshipsNoDateTo(arnEncoded, arn.value, "ITSA")

      val result = await(doRequest)
      result.status shouldBe 404
    }

    "return 404 when DES returns not found" in {
      authorisedAsValidAgent(req, arn.value)
      givenAuditConnector()
      getFailAgentInactiveRelationships(arnEncoded, "VATC", 404)

      val result = await(doRequest)
      result.status shouldBe 404
    }

    "return 404 when DES returns 400" in {
      authorisedAsValidAgent(req, arn.value)
      givenAuditConnector()
      getFailAgentInactiveRelationships(arnEncoded, "VATC", 400)

      val result = await(doRequest)
      result.status shouldBe 404
    }
  }

  "GET /relationships/service/HMRC-MTD-VAT/client/VRN/:vrn" should {
    val vrnEncoded = UriEncoding.encodePathSegment(vrn.value, "UTF-8")
    val requestPath: String = s"/agent-client-relationships/relationships/service/HMRC-MTD-VAT/client/VRN/${vrn.value}"

    def doRequest = doAgentGetRequest(requestPath)
    val req = FakeRequest()

    "find relationship and send back Json" in {
      authorisedAsStrideUser(req, "someStrideId")
      givenAuditConnector()
      getClientActiveAgentRelationshipsVat(vrnEncoded, arn.value)

      val result = await(doRequest)
      result.status shouldBe 200

      val b = result.json
      (result.json \ "arn").get.as[String] shouldBe arn.value
      (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
    }

    "find relationship but filter out if the end date has been changed from 9999-12-31" in {
      authorisedAsStrideUser(req, "someStrideId")
      givenAuditConnector()
      getClientActiveButEndedAgentRelationshipsVat(vrnEncoded, arn.value)

      val result = await(doRequest)
      result.status shouldBe 404
    }

    "find multiple relationships but filter out active and ended relationships" in {
      authorisedAsStrideUser(req, "someStrideId")
      givenAuditConnector()
      getClientActiveButSomeEndedAgentRelationshipsVat(vrnEncoded, arn.value, arn2.value, arn3.value)

      val result = await(doRequest)
      result.status shouldBe 200
      (result.json \ "arn").get.as[String] shouldBe arn3.value
      (result.json \ "dateTo").get.as[LocalDate].toString() shouldBe "9999-12-31"
    }

    "return 404 when DES returns 404 relationship not found" in {
      authorisedAsStrideUser(req, "someStrideId")
      givenAuditConnector()
      getFailClientActiveAgentRelationshipsVat(vrnEncoded, status = 404)

      val result = await(doRequest)
      result.status shouldBe 404
    }

    "return 404 when DES returns 400 (treated as relationship not found)" in {
      authorisedAsStrideUser(req, "someStrideId")
      givenAuditConnector()
      getFailClientActiveAgentRelationshipsVat(vrnEncoded, status = 400)

      val result = await(doRequest)
      result.status shouldBe 404
    }
  }

  private def doAgentGetRequest(route: String) = new Resource(route, port).get()

  private def doAgentPutRequest(route: String) = Http.putEmpty(s"http://localhost:$port$route")

  private def doAgentDeleteRequest(route: String) = Http.delete(s"http://localhost:$port$route")
}
