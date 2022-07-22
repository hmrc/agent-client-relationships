/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.agentclientrelationships.services

import com.kenshoo.play.metrics.Metrics
import org.mockito.ArgumentMatchers.{any, eq => equ}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import uk.gov.hmrc.agentclientrelationships.config.AppConfig
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _}
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentmtdidentifiers.model.{AccessGroupSummaries, AccessGroupSummary, Arn, Client, Enrolment, EnrolmentKey, Identifier, Vrn}
import uk.gov.hmrc.domain._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class CheckRelationshipServiceSpec
    extends AnyFreeSpecLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterEach
    with ResettingMockitoSugar {

  val arn: Arn = Arn("AARN0000002")
  private val taxIdentifier: TaxIdentifier = Vrn("101747641")
  private val client: Client = Client(EnrolmentKey.enrolmentKey("HMRC-MTD-VAT", taxIdentifier.value), "Friendly Client")
  private val enrolment: Enrolment =
    Enrolment("HMRC-MTD-VAT", "activated", "Edward Stone", Seq(Identifier("VRN", taxIdentifier.value)))
  val userId = "testUserId"
  val groupId = "testGroupId"
  val agentCode: AgentCode = AgentCode("ABC1234")
  val agentUser: AgentUser = AgentUser(userId, groupId, agentCode, arn)

  val metrics = mock[Metrics]

  implicit val hc = HeaderCarrier()

  def configWithGranPermsFlag(ff: Boolean): AppConfig = {
    val servicesConfig = mock[ServicesConfig]
    val configuration = mock[Configuration]
    when(servicesConfig.getBoolean("features.enable-granular-permissions")).thenReturn(ff)
    when(servicesConfig.getString(any[String])).thenReturn("")
    new AppConfig(configuration, servicesConfig)
  }

  "checkForRelationship" - {
    "when relationship exists between client and agent" - {
      "when Granular Permission feature flag is ON" - {
        "when agent is opted IN to Granular Permissions" - {
          "should return 200 (even if the client is not assigned to the user in EACD) when the client is unallocated (not in any access groups)" in {
            val es = mock[EnrolmentStoreProxyConnector]
            when(es.getDelegatedGroupIdsFor(equ(taxIdentifier))(any[HeaderCarrier], any[ExecutionContext]))
              .thenReturn(Future.successful(Set(groupId)))
            when(
              es.getEnrolmentsAssignedToUser(any[String], any[Option[String]])(
                any[HeaderCarrier],
                any[ExecutionContext])).thenReturn(Future.successful(Seq.empty))
            val ap = resettingMock[AgentPermissionsConnector]
            when(ap.granularPermissionsOptinRecordExists(any[Arn])(any[HeaderCarrier], any[ExecutionContext]))
              .thenReturn(Future.successful(true))
            when(ap.getGroupsSummaries(equ(arn))(any[HeaderCarrier], any[ExecutionContext]))
              .thenReturn(Future.successful(Some(AccessGroupSummaries(Seq.empty, Set(client)))))

            val crs = new CheckRelationshipsService(es, ap, configWithGranPermsFlag(true), metrics)
            crs.checkForRelationship(taxIdentifier, agentUser).futureValue shouldBe true
          }
          "should return 404 if the client is in at least an access groups but the user has not been assigned the client" in {
            val es = mock[EnrolmentStoreProxyConnector]
            when(es.getDelegatedGroupIdsFor(equ(taxIdentifier))(any[HeaderCarrier], any[ExecutionContext]))
              .thenReturn(Future.successful(Set(groupId)))
            when(
              es.getEnrolmentsAssignedToUser(any[String], any[Option[String]])(
                any[HeaderCarrier],
                any[ExecutionContext])).thenReturn(Future.successful(Seq.empty))
            val ap = resettingMock[AgentPermissionsConnector]
            when(ap.granularPermissionsOptinRecordExists(any[Arn])(any[HeaderCarrier], any[ExecutionContext]))
              .thenReturn(Future.successful(true))
            when(ap.getGroupsSummaries(equ(arn))(any[HeaderCarrier], any[ExecutionContext]))
              .thenReturn(Future.successful(
                Some(AccessGroupSummaries(Seq(AccessGroupSummary("MyAG-id", "MyAG", 15, 5)), Set.empty))))

            val crs = new CheckRelationshipsService(es, ap, configWithGranPermsFlag(true), metrics)
            crs.checkForRelationship(taxIdentifier, agentUser).futureValue shouldBe false
          }
          "should return 200 if the client is in at least an access groups and the user has been assigned the client" in {
            val es = mock[EnrolmentStoreProxyConnector]
            when(es.getDelegatedGroupIdsFor(equ(taxIdentifier))(any[HeaderCarrier], any[ExecutionContext]))
              .thenReturn(Future.successful(Set(groupId)))
            when(
              es.getEnrolmentsAssignedToUser(any[String], any[Option[String]])(
                any[HeaderCarrier],
                any[ExecutionContext])).thenReturn(Future.successful(Seq(enrolment)))
            val ap = resettingMock[AgentPermissionsConnector]
            when(ap.granularPermissionsOptinRecordExists(any[Arn])(any[HeaderCarrier], any[ExecutionContext]))
              .thenReturn(Future.successful(true))
            when(ap.getGroupsSummaries(equ(arn))(any[HeaderCarrier], any[ExecutionContext]))
              .thenReturn(Future.successful(
                Some(AccessGroupSummaries(Seq(AccessGroupSummary("MyAG-id", "MyAG", 15, 5)), Set.empty))))

            val crs = new CheckRelationshipsService(es, ap, configWithGranPermsFlag(true), metrics)
            crs.checkForRelationship(taxIdentifier, agentUser).futureValue shouldBe true
          }
        }
        "when agent is opted OUT from Granular Permissions" - {
          "should return 200 as long as the client/agent relationship exists" in {
            val es = mock[EnrolmentStoreProxyConnector]
            when(es.getDelegatedGroupIdsFor(equ(taxIdentifier))(any[HeaderCarrier], any[ExecutionContext]))
              .thenReturn(Future.successful(Set(groupId)))
            val ap = mock[AgentPermissionsConnector]
            when(ap.granularPermissionsOptinRecordExists(any[Arn])(any[HeaderCarrier], any[ExecutionContext]))
              .thenReturn(Future.successful(false))
            val crs = new CheckRelationshipsService(es, ap, configWithGranPermsFlag(true), metrics)
            crs.checkForRelationship(taxIdentifier, agentUser).futureValue shouldBe true
          }
        }
      }
      "when Granular Permission feature flag is OFF" - {
        "should return 200 as long as the client/agent relationship exists" in {
          val es = mock[EnrolmentStoreProxyConnector]
          when(es.getDelegatedGroupIdsFor(equ(taxIdentifier))(any[HeaderCarrier], any[ExecutionContext]))
            .thenReturn(Future.successful(Set(groupId)))
          val ap = mock[AgentPermissionsConnector]
          val crs = new CheckRelationshipsService(es, ap, configWithGranPermsFlag(false), metrics)
          crs.checkForRelationship(taxIdentifier, agentUser).futureValue shouldBe true
        }
      }
    }
    "when relationship does not exist between client and agent" - {
      "should return 404 regardless of Granular Permission feature flag or opt-in status" in {
        val es = mock[EnrolmentStoreProxyConnector]
        when(es.getDelegatedGroupIdsFor(equ(taxIdentifier))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Set.empty[String]))
        val ap = mock[AgentPermissionsConnector]
        val relationshipsService = new CheckRelationshipsService(es, ap, configWithGranPermsFlag(false), metrics)
        relationshipsService.checkForRelationship(taxIdentifier, agentUser).futureValue shouldBe false
      }
    }
  }
}
