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

import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.await
import play.api.test.Helpers.defaultAwaitTimeout
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdIt
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.MtdItSupp
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.Vat
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus.Success
import uk.gov.hmrc.agentclientrelationships.services.CheckRelationshipsOrchestratorService
import uk.gov.hmrc.agentclientrelationships.services.ClientDetailsService
import uk.gov.hmrc.agentclientrelationships.stubs.ClientDetailsStub
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.agentclientrelationships.stubs.MappingStubs
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.domain.SaAgentReference

import java.time.Instant
import java.time.LocalDate
import scala.concurrent.ExecutionContext

class ClientDetailsControllerISpec
extends BaseControllerISpec
with ClientDetailsStub
with HipStub
with MappingStubs {

  val clientDetailsService: ClientDetailsService = app.injector.instanceOf[ClientDetailsService]
  val authConnector: AuthConnector = app.injector.instanceOf[AuthConnector]
  val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val invitationsRepo: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val partialAuthRepo: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]
  val checkRelationshipsService: CheckRelationshipsOrchestratorService = app.injector
    .instanceOf[CheckRelationshipsOrchestratorService]

  val controller: ClientDetailsController = app.injector.instanceOf[ClientDetailsController]

  def setupCommonStubs(request: FakeRequest[?]): Unit = {
    givenAuthorisedAsValidAgent(request, "XARN1234567")
    givenAuditConnector()
    givenAgentGroupExistsFor("foo")
    givenAdminUser("foo", "bar")
    givenPrincipalGroupIdExistsFor(agentEnrolmentKey(Arn("XARN1234567")), "foo")
  }

  ".findClientDetails" should {
    "return 200 status and the expected JSON body" when {
      "the service supports multiple agents" when {
        "there are no pending invitations or existing relationship and no mapping info or legacy relationship for SA" in {
          val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-IT/details/AA000001B")
          setupCommonStubs(request)
          givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT", MtdItId("XAIT0000111122")))
          givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT-SUPP", MtdItId("XAIT0000111122")))
          givenMtdItsaBusinessDetailsExists(NinoWithoutSuffix("AA000001B"), MtdItId("XAIT0000111122"))
          givenNinoItsaBusinessDetailsExists(MtdItId("XAIT0000111122"), NinoWithoutSuffix("AA000001B"))
          givenClientHasNoRelationshipWithAnyAgentInCESA(nino = NinoWithoutSuffix("AA000001B"))
          givenArnIsUnknownFor(Arn("XARN1234567"))

          val result = doGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name" -> "Erling Haal",
            "isOverseas" -> false,
            "knownFacts" -> Json.arr("AA11AA"),
            "knownFactType" -> "PostalCode",
            "hasPendingInvitation" -> false,
            "isMapped" -> false,
            "clientsLegacyRelationships" -> Json.arr()
          )
        }

        "there are no pending invitations or existing relationship and no mapping info but there is a legacy relationship for SA" in {
          val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-IT/details/AA000001B")
          setupCommonStubs(request)
          givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT", MtdItId("XAIT0000111122")))
          givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT-SUPP", MtdItId("XAIT0000111122")))
          givenMtdItsaBusinessDetailsExists(NinoWithoutSuffix("AA000001B"), MtdItId("XAIT0000111122"))
          givenNinoItsaBusinessDetailsExists(MtdItId("XAIT0000111122"), NinoWithoutSuffix("AA000001B"))
          givenArnIsUnknownFor(Arn("XARN1234567"))
          givenClientHasRelationshipWithAgentInCESA(NinoWithoutSuffix("AA000001B"), "A12345")

          val result = doGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name" -> "Erling Haal",
            "isOverseas" -> false,
            "knownFacts" -> Json.arr("AA11AA"),
            "knownFactType" -> "PostalCode",
            "hasPendingInvitation" -> false,
            "isMapped" -> false,
            "clientsLegacyRelationships" -> Json.arr("A12345")
          )
        }

        "there are no pending invitations or existing relationship and there is an existing mapping for a non signed-up SA client" in {
          val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-IT/details/AA000001B")
          setupCommonStubs(request)
          givenMtdItIdIsUnKnownFor(NinoWithoutSuffix("AA000001B"))
          givenItsaCitizenDetailsExists("AA000001B")
          givenItsaDesignatoryDetailsExists("AA000001B")
          givenArnIsKnownFor(Arn("XARN1234567"), SaAgentReference("A12345"))
          givenClientHasRelationshipWithAgentInCESA(NinoWithoutSuffix("AA000001B"), "A12345")

          val result = doGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name" -> "Matthew Kovacic",
            "isOverseas" -> false,
            "knownFacts" -> Json.arr("AA11AA"),
            "knownFactType" -> "PostalCode",
            "hasPendingInvitation" -> false,
            "isMapped" -> true,
            "clientsLegacyRelationships" -> Json.arr("A12345")
          )
        }

        "there are no pending invitations or existing relationship and there is an existing mapping for a signed-up SA client (relationship is created)" in {
          val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-IT/details/AA000001B")
          setupCommonStubs(request)
          givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT", MtdItId("XAIT0000111122")))
          givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT-SUPP", MtdItId("XAIT0000111122")))
          givenMtdItsaBusinessDetailsExists(NinoWithoutSuffix("AA000001B"), MtdItId("XAIT0000111122"))
          givenNinoItsaBusinessDetailsExists(MtdItId("XAIT0000111122"), NinoWithoutSuffix("AA000001B"))
          givenArnIsKnownFor(Arn("XARN1234567"), SaAgentReference("A12345"))
          givenClientHasRelationshipWithAgentInCESA(NinoWithoutSuffix("AA000001B"), "A12345")
          givenAgentCanBeAllocated(MtdItId("XAIT0000111122"), Arn("XARN1234567"))
          givenAgentGroupExistsFor("foo")
          givenAdminUser("foo", "bar")
          givenEnrolmentAllocationSucceeds(
            "foo",
            "bar",
            EnrolmentKey(Service.MtdIt, MtdItId("XAIT0000111122")),
            "NQJUEJCWT14"
          )
          givenCacheRefresh(Arn("XARN1234567"))

          val result = doGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name" -> "Erling Haal",
            "isOverseas" -> false,
            "knownFacts" -> Json.arr("AA11AA"),
            "knownFactType" -> "PostalCode",
            "hasPendingInvitation" -> false,
            "hasExistingRelationshipFor" -> "HMRC-MTD-IT"
          )
        }

        "there are no pending invitations or existing relationship and" +
          " there is an existing mapping for a signed-up SA client but the relationship was copied before (mapping ignored)" in {
            val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-IT/details/AA000001B")
            setupCommonStubs(request)
            givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT", MtdItId("XAIT0000111122")))
            givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT-SUPP", MtdItId("XAIT0000111122")))
            givenMtdItsaBusinessDetailsExists(NinoWithoutSuffix("AA000001B"), MtdItId("XAIT0000111122"))
            givenNinoItsaBusinessDetailsExists(MtdItId("XAIT0000111122"), NinoWithoutSuffix("AA000001B"))
            givenArnIsKnownFor(Arn("XARN1234567"), SaAgentReference("A12345"))
            givenClientHasRelationshipWithAgentInCESA(NinoWithoutSuffix("AA000001B"), "A12345")
            relationshipCopyRecordRepository.collection.insertOne(
              RelationshipCopyRecord(
                "XARN1234567",
                EnrolmentKey(Service.MtdIt, MtdItId("XAIT0000111122")),
                syncToETMPStatus = Some(Success),
                syncToESStatus = Some(Success)
              )
            ).toFuture().futureValue

            val result = doGetRequest(request.uri)
            result.status shouldBe 200
            result.json shouldBe Json.obj(
              "name" -> "Erling Haal",
              "isOverseas" -> false,
              "knownFacts" -> Json.arr("AA11AA"),
              "knownFactType" -> "PostalCode",
              "hasPendingInvitation" -> false
            )
          }

        "there is a pending invitation" in {
          val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-IT/details/AA000001B")
          setupCommonStubs(request)
          givenMtdItsaBusinessDetailsExists(NinoWithoutSuffix("AA000001B"), MtdItId("XAIT0000111122"))
          givenNinoItsaBusinessDetailsExists(MtdItId("XAIT0000111122"), NinoWithoutSuffix("AA000001B"))
          givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT", MtdItId("XAIT0000111122")))
          givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT-SUPP", MtdItId("XAIT0000111122")))
          givenClientHasNoRelationshipWithAnyAgentInCESA(nino = NinoWithoutSuffix("AA000001B"))

          await(
            invitationsRepo.create(
              "XARN1234567",
              MtdIt,
              NinoWithoutSuffix("AA000001B"),
              NinoWithoutSuffix("AA000001B"),
              "Erling Haal",
              "testAgentName",
              "agent@email.com",
              LocalDate.now(),
              Some("personal")
            )
          )

          val result = doGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name" -> "Erling Haal",
            "isOverseas" -> false,
            "knownFacts" -> Json.arr("AA11AA"),
            "knownFactType" -> "PostalCode",
            "hasPendingInvitation" -> true
          )
        }

        "there is a pending supporting ITSA invitation" in {
          val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-IT/details/AA000001B")
          setupCommonStubs(request)
          givenMtdItsaBusinessDetailsExists(NinoWithoutSuffix("AA000001B"), MtdItId("XAIT0000111122"))
          givenNinoItsaBusinessDetailsExists(MtdItId("XAIT0000111122"), NinoWithoutSuffix("AA000001B"))
          givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT", MtdItId("XAIT0000111122")))
          givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT-SUPP", MtdItId("XAIT0000111122")))
          givenClientHasNoRelationshipWithAnyAgentInCESA(nino = NinoWithoutSuffix("AA000001B"))
          await(
            invitationsRepo.create(
              "XARN1234567",
              MtdItSupp,
              NinoWithoutSuffix("AA000001B"),
              NinoWithoutSuffix("AA000001B"),
              "Erling Haal",
              "testAgentName",
              "agent@email.com",
              LocalDate.now(),
              Some("personal")
            )
          )

          val result = doGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name" -> "Erling Haal",
            "isOverseas" -> false,
            "knownFacts" -> Json.arr("AA11AA"),
            "knownFactType" -> "PostalCode",
            "hasPendingInvitation" -> true
          )
        }

        "there is an existing relationship in a main role" in {
          val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-IT/details/AA000001B")
          setupCommonStubs(request)
          givenMtdItsaBusinessDetailsExists(NinoWithoutSuffix("AA000001B"), MtdItId("XAIT0000111122"))
          givenNinoItsaBusinessDetailsExists(MtdItId("XAIT0000111122"), NinoWithoutSuffix("AA000001B"))
          givenDelegatedGroupIdsExistFor(EnrolmentKey("HMRC-MTD-IT", MtdItId("XAIT0000111122")), Set("foo"))
          givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT-SUPP", MtdItId("XAIT0000111122")))

          val result = doGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name" -> "Erling Haal",
            "isOverseas" -> false,
            "knownFacts" -> Json.arr("AA11AA"),
            "knownFactType" -> "PostalCode",
            "hasPendingInvitation" -> false,
            "hasExistingRelationshipFor" -> "HMRC-MTD-IT"
          )
        }

        "there is an existing relationship in a supporting role" in {
          val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-IT/details/AA000001B")
          setupCommonStubs(request)
          givenMtdItsaBusinessDetailsExists(NinoWithoutSuffix("AA000001B"), MtdItId("XAIT0000111122"))
          givenNinoItsaBusinessDetailsExists(MtdItId("XAIT0000111122"), NinoWithoutSuffix("AA000001B"))
          givenDelegatedGroupIdsExistFor(EnrolmentKey("HMRC-MTD-IT-SUPP", MtdItId("XAIT0000111122")), Set("foo"))
          givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT", MtdItId("XAIT0000111122")))
          givenClientHasNoRelationshipWithAnyAgentInCESA(nino = NinoWithoutSuffix("AA000001B"))

          val result = doGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name" -> "Erling Haal",
            "isOverseas" -> false,
            "knownFacts" -> Json.arr("AA11AA"),
            "knownFactType" -> "PostalCode",
            "hasPendingInvitation" -> false,
            "hasExistingRelationshipFor" -> "HMRC-MTD-IT-SUPP"
          )
        }

        "there is an existing relationship for an overseas client" in {
          // we call using CBC uk service then we discover the client is overseas
          val request = FakeRequest("GET", s"/agent-client-relationships/client/HMRC-CBC-ORG/details/${cbcId.value}")
          setupCommonStubs(request)
          givenCbcDetailsExist(isGBUser = false)
          givenDelegatedGroupIdsExistFor(EnrolmentKey("HMRC-CBC-NONUK-ORG", cbcId), Set("foo"))

          val result = doGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name" -> "CFG Solutions",
            "isOverseas" -> true,
            "knownFacts" -> Json.arr("test@email.com", "test2@email.com"),
            "knownFactType" -> "Email",
            "hasPendingInvitation" -> false,
            "hasExistingRelationshipFor" -> "HMRC-CBC-NONUK-ORG"
          )
        }

        "there is an existing relationship in the form of a PartialAuth invitation (alt-itsa), in a main role" in {
          val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-IT/details/AA000001B")
          setupCommonStubs(request)
          givenMtdItIdIsUnKnownFor(NinoWithoutSuffix("AA000001B"))
          givenItsaCitizenDetailsExists("AA000001B")
          givenItsaDesignatoryDetailsExists("AA000001B")
          await(
            partialAuthRepo.create(
              Instant.parse("2020-01-01T00:00:00.000Z"),
              Arn("XARN1234567"),
              MtdIt.toString(),
              NinoWithoutSuffix("AA000001B")
            )
          )

          val result = doGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name" -> "Matthew Kovacic",
            "isOverseas" -> false,
            "knownFacts" -> Json.arr("AA11AA"),
            "knownFactType" -> "PostalCode",
            "hasPendingInvitation" -> false,
            "hasExistingRelationshipFor" -> "HMRC-MTD-IT"
          )
        }

        "there is an existing relationship in the form of a PartialAuth invitation (alt-itsa), in a supporting role" in {
          val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-IT/details/AA000001B")
          setupCommonStubs(request)
          givenMtdItIdIsUnKnownFor(NinoWithoutSuffix("AA000001B"))
          givenItsaCitizenDetailsExists("AA000001B")
          givenItsaDesignatoryDetailsExists("AA000001B")
          await(
            partialAuthRepo.create(
              Instant.now(),
              Arn("XARN1234567"),
              MtdItSupp.toString(),
              NinoWithoutSuffix("AA000001B")
            )
          )

          val result = doGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name" -> "Matthew Kovacic",
            "isOverseas" -> false,
            "knownFacts" -> Json.arr("AA11AA"),
            "knownFactType" -> "PostalCode",
            "hasPendingInvitation" -> false,
            "hasExistingRelationshipFor" -> "HMRC-MTD-IT-SUPP"
          )
        }

        "there is both a pending invitation and an existing relationship" in {
          val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-IT/details/AA000001B")
          setupCommonStubs(request)
          givenMtdItsaBusinessDetailsExists(NinoWithoutSuffix("AA000001B"), MtdItId("XAIT0000111122"))
          givenNinoItsaBusinessDetailsExists(MtdItId("XAIT0000111122"), NinoWithoutSuffix("AA000001B"))
          givenDelegatedGroupIdsExistFor(EnrolmentKey("HMRC-MTD-IT", MtdItId("XAIT0000111122")), Set("foo"))
          givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT-SUPP", MtdItId("XAIT0000111122")))
          await(
            invitationsRepo.create(
              "XARN1234567",
              MtdIt,
              NinoWithoutSuffix("AA000001B"),
              NinoWithoutSuffix("AA000001B"),
              "Erling Haal",
              "testAgentName",
              "agent@email.com",
              LocalDate.now(),
              Some("personal")
            )
          )

          val result = doGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name" -> "Erling Haal",
            "isOverseas" -> false,
            "knownFacts" -> Json.arr("AA11AA"),
            "knownFactType" -> "PostalCode",
            "hasPendingInvitation" -> true,
            "hasExistingRelationshipFor" -> "HMRC-MTD-IT"
          )
        }
      }

      "the service does not support multiple agents" when {

        "there are no pending invitations or existing relationship" in {
          val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-VAT/details/101747641")
          setupCommonStubs(request)
          givenVatCustomerInfoExists("101747641")
          givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-VAT", Vrn("101747641")))

          val result = doGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name" -> "CFG Solutions",
            "knownFacts" -> Json.arr("2020-01-01"),
            "knownFactType" -> "Date",
            "hasPendingInvitation" -> false
          )
        }

        "there is a pending invitation" in {
          val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-VAT/details/101747641")
          setupCommonStubs(request)
          givenVatCustomerInfoExists("101747641")
          givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-VAT", Vrn("101747641")))
          await(
            invitationsRepo.create(
              "XARN1234567",
              Vat,
              Vrn("101747641"),
              Vrn("101747641"),
              "My Name",
              "testAgentName",
              "agent@email.com",
              LocalDate.now(),
              Some("personal")
            )
          )

          val result = doGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name" -> "CFG Solutions",
            "knownFacts" -> Json.arr("2020-01-01"),
            "knownFactType" -> "Date",
            "hasPendingInvitation" -> true
          )
        }

        "there is an existing relationship" in {
          val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-VAT/details/101747641")
          setupCommonStubs(request)
          givenVatCustomerInfoExists("101747641")
          givenDelegatedGroupIdsExistFor(EnrolmentKey("HMRC-MTD-VAT", Vrn("101747641")), Set("foo"))

          val result = doGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name" -> "CFG Solutions",
            "knownFacts" -> Json.arr("2020-01-01"),
            "knownFactType" -> "Date",
            "hasPendingInvitation" -> false,
            "hasExistingRelationshipFor" -> "HMRC-MTD-VAT"
          )
        }

        "there is both a pending invitation and an existing relationship" in {
          val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-VAT/details/101747641")
          setupCommonStubs(request)
          givenVatCustomerInfoExists("101747641")
          givenDelegatedGroupIdsExistFor(EnrolmentKey("HMRC-MTD-VAT", Vrn("101747641")), Set("foo"))
          await(
            invitationsRepo.create(
              "XARN1234567",
              Vat,
              Vrn("101747641"),
              Vrn("101747641"),
              "My Name",
              "testAgentName",
              "agent@email.com",
              LocalDate.now(),
              Some("personal")
            )
          )

          val result = doGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name" -> "CFG Solutions",
            "knownFacts" -> Json.arr("2020-01-01"),
            "knownFactType" -> "Date",
            "hasPendingInvitation" -> true,
            "hasExistingRelationshipFor" -> "HMRC-MTD-VAT"
          )
        }
      }
    }

    "return 404 status when client details were not found" in {
      val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-VAT/details/101747641")
      setupCommonStubs(request)
      givenVatCustomerInfoError("101747641", NOT_FOUND)
      givenDelegatedGroupIdsExistFor(EnrolmentKey("HMRC-MTD-VAT", Vrn("101747641")), Set("foo"))

      val result = doGetRequest(request.uri)
      result.status shouldBe 404
      result.body shouldBe empty
    }

    "return 500 status" when {

      "there was an unexpected failure retrieving client details" in {
        val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-VAT/details/101747641")
        setupCommonStubs(request)
        givenVatCustomerInfoError("101747641", INTERNAL_SERVER_ERROR)
        givenDelegatedGroupIdsExistFor(EnrolmentKey("HMRC-MTD-VAT", Vrn("101747641")), Set("foo"))

        val result = doGetRequest(request.uri)
        result.status shouldBe 500
      }

      "there was an unexpected failure retrieving the existing relationships" in {
        val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-VAT/details/101747641")
        givenAuthorisedAsValidAgent(request, "XARN1234567")
        givenAuditConnector()
        givenVatCustomerInfoExists("101747641")
        givenAgentGroupExistsFor("foo")
        givenAdminUser("foo", "bar")
        givenPrincipalGroupIdRequestFailsWith(INTERNAL_SERVER_ERROR)
        givenDelegatedGroupIdsExistFor(EnrolmentKey("HMRC-MTD-VAT", Vrn("101747641")), Set("foo"))

        val result = doGetRequest(request.uri)
        result.status shouldBe 500
      }
    }

    "return 401 status if the user is not authorised" in {
      val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-VAT/details/101747641")
      requestIsNotAuthenticated()
      givenAuditConnector()
      givenVatCustomerInfoExists("101747641")

      val result = doGetRequest(request.uri)
      result.status shouldBe 401
    }
  }

}
