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

import play.api.http.Status.{INTERNAL_SERVER_ERROR, NOT_FOUND}
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.{await, defaultAwaitTimeout, stubControllerComponents}
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.InvitationsRepository
import uk.gov.hmrc.agentclientrelationships.services.ClientDetailsService
import uk.gov.hmrc.agentclientrelationships.stubs.ClientDetailsStub
import uk.gov.hmrc.agentmtdidentifiers.model.Service.Vat
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.auth.core.AuthConnector

import java.time.LocalDate
import scala.concurrent.ExecutionContext

class ClientDetailsControllerISpec extends RelationshipsBaseControllerISpec with ClientDetailsStub {

  val clientDetailsService: ClientDetailsService = app.injector.instanceOf[ClientDetailsService]
  val authConnector: AuthConnector = app.injector.instanceOf[AuthConnector]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
  val repository: InvitationsRepository = app.injector.instanceOf[InvitationsRepository]
  val relationshipsController: RelationshipsController = app.injector.instanceOf[RelationshipsController]

  val controller = new ClientDetailsController(
    clientDetailsService,
    relationshipsController,
    repository,
    authConnector,
    stubControllerComponents()
  )

  ".findClientDetails" should {

    "return 200 status and the expected JSON body" when {

      "the client has no pending invitations or existing relationship with this agent & service" in {
        val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-VAT/details/101747641")
        givenAuthorisedAsValidAgent(request, "XARN1234567")
        givenAuditConnector()
        givenVatCustomerInfoExists("101747641")
        givenAgentGroupExistsFor("foo")
        givenAdminUser("foo", "bar")
        givenPrincipalGroupIdExistsFor(agentEnrolmentKey(Arn("XARN1234567")), "foo")
        givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-VAT", Vrn("101747641")))
        givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMCE-VATDEC-ORG~VATRegNo~101747641"))

        val result = doAgentGetRequest(request.uri)
        result.status shouldBe 200
        result.json shouldBe Json.obj(
          "name"                 -> "CFG Solutions",
          "knownFacts"           -> Json.arr("2020-01-01"),
          "knownFactType"        -> "Date",
          "hasPendingInvitation" -> false
        )
      }

      "the client has a pending invitation" in {
        val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-VAT/details/101747641")
        givenAuthorisedAsValidAgent(request, "XARN1234567")
        givenAuditConnector()
        givenVatCustomerInfoExists("101747641")
        givenAgentGroupExistsFor("foo")
        givenAdminUser("foo", "bar")
        givenPrincipalGroupIdExistsFor(agentEnrolmentKey(Arn("XARN1234567")), "foo")
        givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-VAT", Vrn("101747641")))
        givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMCE-VATDEC-ORG~VATRegNo~101747641"))
        await(repository.create("XARN1234567", Vat, Vrn("101747641"), Vrn("101747641"), "My Name", LocalDate.now()))

        val result = doAgentGetRequest(request.uri)
        result.status shouldBe 200
        result.json shouldBe Json.obj(
          "name"                 -> "CFG Solutions",
          "knownFacts"           -> Json.arr("2020-01-01"),
          "knownFactType"        -> "Date",
          "hasPendingInvitation" -> true
        )
      }

      "the client has an existing relationship for this agent & service" when {

        "the service supports multiple agents" when {

          "the existing relationship is in a main role" in {
            val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-IT/details/AA000001B")
            givenAuthorisedAsValidAgent(request, "XARN1234567")
            givenAuditConnector()
            givenItsaBusinessDetailsExists("nino", "AA000001B")
            givenItsaBusinessDetailsExists("mtdId", "XAIT0000111122")
            givenAgentGroupExistsFor("foo")
            givenAdminUser("foo", "bar")
            givenPrincipalGroupIdExistsFor(agentEnrolmentKey(Arn("XARN1234567")), "foo")
            givenDelegatedGroupIdsExistFor(EnrolmentKey("HMRC-MTD-IT", MtdItId("XAIT0000111122")), Set("foo"))
            givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT-SUPP", MtdItId("XAIT0000111122")))

            val result = doAgentGetRequest(request.uri)
            result.status shouldBe 200
            result.json shouldBe Json.obj(
              "name"                       -> "Erling Haal",
              "isOverseas"                 -> false,
              "knownFacts"                 -> Json.arr("AA1 1AA"),
              "knownFactType"              -> "PostalCode",
              "hasPendingInvitation"       -> false,
              "hasExistingRelationshipFor" -> "HMRC-MTD-IT"
            )
          }

          "the existing relationship is in a supporting role" in {
            val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-IT/details/AA000001B")
            givenAuthorisedAsValidAgent(request, "XARN1234567")
            givenAuditConnector()
            givenItsaBusinessDetailsExists("nino", "AA000001B")
            givenItsaBusinessDetailsExists("mtdId", "XAIT0000111122")
            givenAgentGroupExistsFor("foo")
            givenAdminUser("foo", "bar")
            givenPrincipalGroupIdExistsFor(agentEnrolmentKey(Arn("XARN1234567")), "foo")
            givenDelegatedGroupIdsExistFor(EnrolmentKey("HMRC-MTD-IT-SUPP", MtdItId("XAIT0000111122")), Set("foo"))
            givenDelegatedGroupIdsNotExistFor(EnrolmentKey("HMRC-MTD-IT", MtdItId("XAIT0000111122")))

            val result = doAgentGetRequest(request.uri)
            result.status shouldBe 200
            result.json shouldBe Json.obj(
              "name"                       -> "Erling Haal",
              "isOverseas"                 -> false,
              "knownFacts"                 -> Json.arr("AA1 1AA"),
              "knownFactType"              -> "PostalCode",
              "hasPendingInvitation"       -> false,
              "hasExistingRelationshipFor" -> "HMRC-MTD-IT-SUPP"
            )
          }
        }

        "the service does not support multiple agents" in {
          val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-VAT/details/101747641")
          givenAuthorisedAsValidAgent(request, "XARN1234567")
          givenAuditConnector()
          givenVatCustomerInfoExists("101747641")
          givenAgentGroupExistsFor("foo")
          givenAdminUser("foo", "bar")
          givenPrincipalGroupIdExistsFor(agentEnrolmentKey(Arn("XARN1234567")), "foo")
          givenDelegatedGroupIdsExistFor(EnrolmentKey("HMRC-MTD-VAT", Vrn("101747641")), Set("foo"))

          val result = doAgentGetRequest(request.uri)
          result.status shouldBe 200
          result.json shouldBe Json.obj(
            "name"                       -> "CFG Solutions",
            "knownFacts"                 -> Json.arr("2020-01-01"),
            "knownFactType"              -> "Date",
            "hasPendingInvitation"       -> false,
            "hasExistingRelationshipFor" -> "HMRC-MTD-VAT"
          )
        }
      }

      "the client has both a pending invitation and an existing relationship for this agent & service" in {
        val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-VAT/details/101747641")
        givenAuthorisedAsValidAgent(request, "XARN1234567")
        givenAuditConnector()
        givenVatCustomerInfoExists("101747641")
        givenAgentGroupExistsFor("foo")
        givenAdminUser("foo", "bar")
        givenPrincipalGroupIdExistsFor(agentEnrolmentKey(Arn("XARN1234567")), "foo")
        givenDelegatedGroupIdsExistFor(EnrolmentKey("HMRC-MTD-VAT", Vrn("101747641")), Set("foo"))
        await(repository.create("XARN1234567", Vat, Vrn("101747641"), Vrn("101747641"), "My Name", LocalDate.now()))

        val result = doAgentGetRequest(request.uri)
        result.status shouldBe 200
        result.json shouldBe Json.obj(
          "name"                       -> "CFG Solutions",
          "knownFacts"                 -> Json.arr("2020-01-01"),
          "knownFactType"              -> "Date",
          "hasPendingInvitation"       -> true,
          "hasExistingRelationshipFor" -> "HMRC-MTD-VAT"
        )
      }
    }

    "return 404 status when client details were not found" in {
      val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-VAT/details/101747641")
      givenAuthorisedAsValidAgent(request, "XARN1234567")
      givenAuditConnector()
      givenVatCustomerInfoError("101747641", NOT_FOUND)
      givenAgentGroupExistsFor("foo")
      givenAdminUser("foo", "bar")
      givenPrincipalGroupIdExistsFor(agentEnrolmentKey(Arn("XARN1234567")), "foo")
      givenDelegatedGroupIdsExistFor(EnrolmentKey("HMRC-MTD-VAT", Vrn("101747641")), Set("foo"))

      val result = doAgentGetRequest(request.uri)
      result.status shouldBe 404
      result.body shouldBe empty
    }

    "return 500 status" when {

      "there was an unexpected failure retrieving client details" in {
        val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-VAT/details/101747641")
        givenAuthorisedAsValidAgent(request, "XARN1234567")
        givenAuditConnector()
        givenVatCustomerInfoError("101747641", INTERNAL_SERVER_ERROR)
        givenAgentGroupExistsFor("foo")
        givenAdminUser("foo", "bar")
        givenPrincipalGroupIdExistsFor(agentEnrolmentKey(Arn("XARN1234567")), "foo")
        givenDelegatedGroupIdsExistFor(EnrolmentKey("HMRC-MTD-VAT", Vrn("101747641")), Set("foo"))

        val result = doAgentGetRequest(request.uri)
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

        val result = doAgentGetRequest(request.uri)
        result.status shouldBe 500
      }
    }

    "return 401 status if the user is not authorised" in {
      val request = FakeRequest("GET", "/agent-client-relationships/client/HMRC-MTD-VAT/details/101747641")
      requestIsNotAuthenticated()

      val result = doAgentGetRequest(request.uri)
      result.status shouldBe 401
    }
  }
}
