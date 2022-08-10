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
import uk.gov.hmrc.agentclientrelationships.connectors._
import uk.gov.hmrc.agentclientrelationships.model.UserId
import uk.gov.hmrc.agentclientrelationships.repository.{SyncStatus => _}
import uk.gov.hmrc.agentclientrelationships.support.ResettingMockitoSugar
import uk.gov.hmrc.agentmtdidentifiers.model.{AccessGroupSummaries, AccessGroupSummary, Arn, Client, Enrolment, EnrolmentKey, Identifier, Vrn}
import uk.gov.hmrc.domain._
import uk.gov.hmrc.http.HeaderCarrier

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
  val userId = UserId("testUserId")
  val groupId = "testGroupId"
  val agentCode: AgentCode = AgentCode("ABC1234")

  val metrics = mock[Metrics]

  implicit val hc = HeaderCarrier()

  "checkForRelationship (user level)" - {
    "when relationship exists between client and agent" - {
      "should return 200 (even if the client is not assigned to the user in EACD) when the client is unallocated (not in any access groups)" in {
        val es = mock[EnrolmentStoreProxyConnector]
        when(es.getDelegatedGroupIdsFor(equ(taxIdentifier))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Set(groupId)))
        when(
          es.getEnrolmentsAssignedToUser(any[String], any[Option[String]])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Seq.empty))
        when(es.getPrincipalGroupIdFor(equ(arn))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(groupId))
        val ap = resettingMock[AgentPermissionsConnector]
        when(ap.getGroupsSummaries(equ(arn))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Some(AccessGroupSummaries(Seq.empty, Set(client)))))
        val gs = mock[UsersGroupsSearchConnector]
        when(gs.getGroupUsers(any[String])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Seq(UserDetails(userId = Some(userId.value)))))

        val crs = new CheckRelationshipsService(es, ap, gs, metrics)
        crs.checkForRelationship(arn, Some(userId), taxIdentifier).futureValue shouldBe true
      }
      "should return 404 if the client is in at least an access groups but the user has not been assigned the client" in {
        val es = mock[EnrolmentStoreProxyConnector]
        when(es.getDelegatedGroupIdsFor(equ(taxIdentifier))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Set(groupId)))
        when(
          es.getEnrolmentsAssignedToUser(any[String], any[Option[String]])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Seq.empty))
        when(es.getPrincipalGroupIdFor(equ(arn))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(groupId))
        val ap = resettingMock[AgentPermissionsConnector]
        when(ap.getGroupsSummaries(equ(arn))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(
            Future.successful(Some(AccessGroupSummaries(Seq(AccessGroupSummary("MyAG-id", "MyAG", 15, 5)), Set.empty))))
        val gs = mock[UsersGroupsSearchConnector]
        when(gs.getGroupUsers(any[String])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Seq(UserDetails(userId = Some(userId.value)))))

        val crs = new CheckRelationshipsService(es, ap, gs, metrics)
        crs.checkForRelationship(arn, Some(userId), taxIdentifier).futureValue shouldBe false
      }
      "should return 200 if the client is in at least an access groups and the user has been assigned the client" in {
        val es = mock[EnrolmentStoreProxyConnector]
        when(es.getDelegatedGroupIdsFor(equ(taxIdentifier))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Set(groupId)))
        when(
          es.getEnrolmentsAssignedToUser(any[String], any[Option[String]])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Seq(enrolment)))
        when(es.getPrincipalGroupIdFor(equ(arn))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(groupId))
        val ap = resettingMock[AgentPermissionsConnector]
        when(ap.getGroupsSummaries(equ(arn))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(
            Future.successful(Some(AccessGroupSummaries(Seq(AccessGroupSummary("MyAG-id", "MyAG", 15, 5)), Set.empty))))
        val gs = mock[UsersGroupsSearchConnector]
        when(gs.getGroupUsers(any[String])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Seq(UserDetails(userId = Some(userId.value)))))

        val crs = new CheckRelationshipsService(es, ap, gs, metrics)
        crs.checkForRelationship(arn, Some(userId), taxIdentifier).futureValue shouldBe true
      }
    }
    "when relationship does not exist between client and agent" - {
      "should return 404" in {
        val es = mock[EnrolmentStoreProxyConnector]
        when(es.getDelegatedGroupIdsFor(equ(taxIdentifier))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Set.empty[String]))
        when(es.getPrincipalGroupIdFor(equ(arn))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(groupId))
        val ap = mock[AgentPermissionsConnector]
        val gs = mock[UsersGroupsSearchConnector]
        when(gs.getGroupUsers(any[String])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Seq(UserDetails(userId = Some(userId.value)))))

        val relationshipsService = new CheckRelationshipsService(es, ap, gs, metrics)
        relationshipsService.checkForRelationship(arn, Some(userId), taxIdentifier).futureValue shouldBe false
      }
    }
    "when user does not belong to the agent's group" - {
      "should return 404" in {
        val es = mock[EnrolmentStoreProxyConnector]
        when(es.getDelegatedGroupIdsFor(equ(taxIdentifier))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Set(groupId)))
        when(
          es.getEnrolmentsAssignedToUser(any[String], any[Option[String]])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Seq.empty))
        when(es.getPrincipalGroupIdFor(equ(arn))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(groupId))
        val ap = resettingMock[AgentPermissionsConnector]
        when(ap.getGroupsSummaries(equ(arn))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Some(AccessGroupSummaries(Seq.empty, Set(client)))))
        val gs = mock[UsersGroupsSearchConnector]
        when(gs.getGroupUsers(any[String])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Seq(UserDetails(userId = Some("someOtherUserId")))))

        val crs = new CheckRelationshipsService(es, ap, gs, metrics)
        crs.checkForRelationship(arn, Some(userId), taxIdentifier).futureValue shouldBe false
      }
    }
  }

  "checkForRelationship (agency level)" - {
    "when relationship exists between client and agent" - {
      "should return 200" in {
        val es = mock[EnrolmentStoreProxyConnector]
        when(es.getDelegatedGroupIdsFor(equ(taxIdentifier))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Set(groupId)))
        when(es.getPrincipalGroupIdFor(equ(arn))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(groupId))
        val ap = resettingMock[AgentPermissionsConnector]
        val gs = mock[UsersGroupsSearchConnector]
        when(gs.getGroupUsers(any[String])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Seq(UserDetails(userId = Some(userId.value)))))

        val crs = new CheckRelationshipsService(es, ap, gs, metrics)
        crs.checkForRelationship(arn, None, taxIdentifier).futureValue shouldBe true
      }
    }
    "when relationship does not exist between client and agent" - {
      "should return 404" in {
        val es = mock[EnrolmentStoreProxyConnector]
        when(es.getDelegatedGroupIdsFor(equ(taxIdentifier))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Set.empty[String]))
        when(es.getPrincipalGroupIdFor(equ(arn))(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(groupId))
        val ap = mock[AgentPermissionsConnector]
        val gs = mock[UsersGroupsSearchConnector]
        when(gs.getGroupUsers(any[String])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(Seq(UserDetails(userId = Some(userId.value)))))

        val relationshipsService = new CheckRelationshipsService(es, ap, gs, metrics)
        relationshipsService.checkForRelationship(arn, None, taxIdentifier).futureValue shouldBe false
      }
    }
  }
}
