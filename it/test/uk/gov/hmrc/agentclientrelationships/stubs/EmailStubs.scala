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

package uk.gov.hmrc.agentclientrelationships.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import org.scalatest.concurrent.Eventually.eventually
import play.api.libs.json.Json
import uk.gov.hmrc.agentclientrelationships.model.EmailInformation
import uk.gov.hmrc.agentclientrelationships.support.TestData

trait EmailStubs
extends TestData {

  def verifyRejectInvitationSent(
    emailInformation: EmailInformation,
    count: Int = 1
  ) = eventually {
    verify(
      count,
      postRequestedFor(urlPathEqualTo(s"/hmrc/email"))
        .withRequestBody(similarToJson(Json.toJson(emailInformation).toString))
    )
  }

  private def similarToJson(value: String) = equalToJson(
    value.stripMargin,
    true,
    true
  )

  def givenEmailSent(
    emailInformation: EmailInformation,
    status: Int = 202
  ): StubMapping = stubFor(
    post("/hmrc/email")
      .withRequestBody(similarToJson(Json.toJson(emailInformation).toString()))
      .willReturn(aResponse().withStatus(status))
  )

}
