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
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.services.ClientDetailsService
import uk.gov.hmrc.agentclientrelationships.stubs.ClientDetailsStub
import uk.gov.hmrc.auth.core.AuthConnector

import scala.concurrent.ExecutionContext

class ClientDetailsControllerISpec extends RelationshipsBaseControllerISpec with ClientDetailsStub {

  val clientDetailsService: ClientDetailsService = app.injector.instanceOf[ClientDetailsService]
  val authConnector: AuthConnector = app.injector.instanceOf[AuthConnector]
  implicit val appConfig: AppConfig = app.injector.instanceOf[AppConfig]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  val controller = new ClientDetailsController(clientDetailsService, authConnector, stubControllerComponents())

  ".findClientDetails" should {

    "return 200 status and valid JSON when API calls are successful and relevant checks have passed" in {
      givenAuditConnector()
      givenItsaBusinessDetailsExists("AA000001B")

      val requestBody = """{"clientDetails":[{"key":"postcode","value":"AA1 1AA"},{"key":"nino","value":"AA000001B"}]}"""

      val result = doAgentPostRequest("/agent-client-relationships/client/HMRC-MTD-IT/details", requestBody)
      result.status shouldBe 200
      result.json shouldBe Json.obj("name" -> "Erling Haal", "isOverseas" -> false)
    }

    "return 400 status when there was a problem matching client details" in {
      givenAuditConnector()
      givenItsaBusinessDetailsExists("AA000001B")

      val requestBody = """{"clientDetails":[{"key":"postcode","value":"BB2 2BB"},{"key":"nino","value":"AA000001B"}]}"""

      val result = doAgentPostRequest("/agent-client-relationships/client/HMRC-MTD-IT/details", requestBody)
      result.status shouldBe 400
      result.body shouldBe empty
    }

    "return 404 status when client details were not found" in {
      givenAuditConnector()
      givenItsaBusinessDetailsError("AA000001B", NOT_FOUND)
      givenItsaCitizenDetailsError("AA000001B", NOT_FOUND)
      givenItsaDesignatoryDetailsError("AA000001B", NOT_FOUND)

      val requestBody = """{"clientDetails":[{"key":"postcode","value":"AA1 1AA"},{"key":"nino","value":"AA000001B"}]}"""

      val result = doAgentPostRequest("/agent-client-relationships/client/HMRC-MTD-IT/details", requestBody)
      result.status shouldBe 404
      result.body shouldBe empty
    }

    "return an unexpected status and valid JSON passed down from the connector" in {
      givenAuditConnector()
      givenItsaBusinessDetailsError("AA000001B", INTERNAL_SERVER_ERROR)

      val requestBody = """{"clientDetails":[{"key":"postcode","value":"AA1 1AA"},{"key":"nino","value":"AA000001B"}]}"""

      val result = doAgentPostRequest("/agent-client-relationships/client/HMRC-MTD-IT/details", requestBody)
      result.status shouldBe 500
      result.json shouldBe Json.obj(
        "statusCode" -> 500,
        "message" -> "Downstream call for service: HMRC-MTD-IT failed with status: 500 and error message: Unexpected error during 'getItsaBusinessDetails'"
      )
    }
  }
}
