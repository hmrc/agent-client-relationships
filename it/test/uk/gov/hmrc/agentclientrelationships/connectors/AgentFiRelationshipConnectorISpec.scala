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

package uk.gov.hmrc.agentclientrelationships.connectors

import org.apache.pekko.Done
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.model.ActiveRelationship
import uk.gov.hmrc.agentclientrelationships.model.InactiveRelationship
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.agentclientrelationships.stubs.AfiRelationshipStub
import uk.gov.hmrc.agentclientrelationships.stubs.DataStreamStub
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentclientrelationships.support.WireMockSupport
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.time.LocalDate
import java.time.LocalDateTime

class AgentFiRelationshipConnectorISpec
extends UnitSpec
with GuiceOneServerPerSuite
with WireMockSupport
with AfiRelationshipStub
with DataStreamStub {

  override implicit lazy val app: Application = appBuilder.build()

  val httpClient: HttpClientV2 = app.injector.instanceOf[HttpClientV2]
  val metrics: Metrics = app.injector.instanceOf[Metrics]
  val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  protected def appBuilder: GuiceApplicationBuilder = new GuiceApplicationBuilder().configure(
    "microservice.services.enrolment-store-proxy.port" -> wireMockPort,
    "microservice.services.tax-enrolments.port" -> wireMockPort,
    "microservice.services.users-groups-search.port" -> wireMockPort,
    "microservice.services.des.port" -> wireMockPort,
    "microservice.services.auth.port" -> wireMockPort,
    "microservice.services.agent-fi-relationship.port" -> wireMockPort,
    "microservice.services.des.environment" -> "stub",
    "microservice.services.des.authorization-token" -> "token",
    "microservice.services.agent-mapping.port" -> wireMockPort,
    "auditing.consumer.baseUri.host" -> wireMockHost,
    "auditing.consumer.baseUri.port" -> wireMockPort,
    "features.copy-relationship.mtd-it" -> true,
    "features.recovery-enable" -> false,
    "agent.cache.expires" -> "1 millis",
    "agent.cache.enabled" -> false
  )

  private implicit val request: RequestHeader = FakeRequest()

  val agentFiRelationshipConnector = app.injector.instanceOf[AgentFiRelationshipConnector]

  val arn: Arn = Arn("ABCDE123456")
  val service: String = Service.PersonalIncomeRecord.id
  val clientId = "AA000001B"

  "deleteRelationship" should {
    "return a true if PersonalIncomeRecord has been deleted" in {
      givenAuditConnector()
      givenTerminateAfiRelationshipSucceeds(
        arn,
        service,
        clientId
      )
      await(
        agentFiRelationshipConnector.deleteRelationship(
          arn,
          service,
          clientId
        )
      ) shouldBe true
    }
    "return a false if PersonalIncomeRecord has not been found" in {
      givenAuditConnector()
      givenTerminateAfiRelationshipFails(
        arn,
        service,
        clientId,
        NOT_FOUND
      )
      await(
        agentFiRelationshipConnector.deleteRelationship(
          arn,
          service,
          clientId
        )
      ) shouldBe false
    }
    "throw an if PersonalIncomeRecord fails to delete" in {
      givenAuditConnector()
      givenTerminateAfiRelationshipFails(
        arn,
        service,
        clientId,
        INTERNAL_SERVER_ERROR
      )
      intercept[UpstreamErrorResponse](
        await(
          agentFiRelationshipConnector.deleteRelationship(
            arn,
            service,
            clientId
          )
        )
      )
    }
  }

  "createRelationship" should {
    "return unit if PersonalIncomeRecord has been created" in {
      givenAuditConnector()
      givenCreateAfiRelationshipSucceeds(
        arn,
        service,
        clientId
      )
      await(
        agentFiRelationshipConnector.createRelationship(
          arn,
          service,
          clientId,
          LocalDateTime.now()
        )
      ) shouldBe Done
    }
    "throw an if PersonalIncomeRecord fails to create" in {
      givenAuditConnector()
      givenCreateAfiRelationshipFails(
        arn,
        service,
        clientId
      )
      intercept[UpstreamErrorResponse](
        await(
          agentFiRelationshipConnector.createRelationship(
            arn,
            service,
            clientId,
            LocalDateTime.now()
          )
        )
      )
    }
  }

  "getRelationship" should {
    "return an active relationship if active PersonalIncomeRecord exists" in {
      givenAuditConnector()
      givenAfiRelationshipIsActive(
        arn,
        service,
        clientId,
        fromCesa = false
      )
      await(
        agentFiRelationshipConnector.getRelationship(
          arn,
          service,
          clientId
        )
      ) shouldBe Some(
        ActiveRelationship(
          Arn("ABCDE123456"),
          None,
          Some(LocalDate.parse("2017-12-08"))
        )
      )
    }
    "return None if active PersonalIncomeRecord does not exist" in {
      givenAuditConnector()
      givenAfiRelationshipNotFound(
        arn = arn,
        service = service,
        clientId = clientId
      )
      await(
        agentFiRelationshipConnector.getRelationship(
          arn,
          service,
          clientId
        )
      ) shouldBe None
    }
  }

  "getRelationship" should {
    "return a list of inactive PersonalIncomeRecord if they exist" in {
      givenAuditConnector()
      givenInactiveAfiRelationship(arn)
      await(agentFiRelationshipConnector.getInactiveRelationships) shouldBe Seq(
        InactiveRelationship(
          Arn("ABCDE123456"),
          Some(LocalDate.parse("2015-09-21")),
          None,
          "AB123456A",
          "personal",
          "PERSONAL-INCOME-RECORD"
        ),
        InactiveRelationship(
          Arn("ABCDE123456"),
          Some(LocalDate.parse("2018-09-24")),
          None,
          "GZ753451B",
          "personal",
          "PERSONAL-INCOME-RECORD"
        )
      )
    }
    "return Nil if inactive PersonalIncomeRecord do not exist" in {
      givenAuditConnector()
      givenInactiveAfiRelationshipNotFound
      await(agentFiRelationshipConnector.getInactiveRelationships) shouldBe Nil
    }
  }

}
