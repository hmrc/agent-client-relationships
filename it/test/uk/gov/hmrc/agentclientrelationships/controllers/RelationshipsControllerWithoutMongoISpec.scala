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
import org.apache.pekko.Done
import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.model.PartialAuthRelationship
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCMTDIT
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Service.HMRCMTDITSUPP
import uk.gov.hmrc.agentclientrelationships.model.identifiers._
import uk.gov.hmrc.agentclientrelationships.repository.PartialAuthRepository
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecordRepository
import uk.gov.hmrc.agentclientrelationships.stubs._
import uk.gov.hmrc.agentclientrelationships.support.Resource
import uk.gov.hmrc.domain.SaAgentReference
import uk.gov.hmrc.mongo.MongoComponent

import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class TestRelationshipCopyRecordRepository @Inject() (moduleComponent: MongoComponent)
extends RelationshipCopyRecordRepository(moduleComponent) {
  override def create(record: RelationshipCopyRecord): Future[Done] = Future
    .failed(new Exception("Could not connect the mongo db."))
}

class RelationshipsControllerWithoutMongoISpec
extends BaseControllerISpec
with HipStub {

  override val additionalOverrides: AbstractModule =
    new AbstractModule {
      override def configure(): Unit = {
        bind(classOf[RelationshipCopyRecordRepository]).to(classOf[TestRelationshipCopyRecordRepository])
      }
    }

  def repo: RelationshipCopyRecordRepository = app.injector.instanceOf[RelationshipCopyRecordRepository]

  val partialAuthRepo: PartialAuthRepository = app.injector.instanceOf[PartialAuthRepository]

  override def beforeEach() = {
    super.beforeEach()
    prepareDatabase()
    ()
  }

  def partialAuthRelationship(service: String): PartialAuthRelationship = PartialAuthRelationship(
    Instant.now().truncatedTo(ChronoUnit.SECONDS),
    arn.value,
    service,
    nino.value,
    active = true,
    lastUpdated = Instant.now().truncatedTo(ChronoUnit.SECONDS)
  )

  "GET /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:identifierValue" should {

    val requestPath = s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-IT/client/MTDITID/${mtdItId.value}"

    "return 200 when relationship exists only in cesa and relationship copy attempt fails because of mongo" in {
      givenPrincipalAgentUser(arn, "foo")
      givenGroupInfo("foo", "bar")
      givenDelegatedGroupIdsNotExistForMtdItId(mtdItId)
      givenNinoIsKnownFor(mtdItId, nino)
      givenMtdItIdIsKnownFor(nino, mtdItId)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenAgentCanBeAllocated(mtdItId, arn)
      givenMTDITEnrolmentAllocationSucceeds(mtdItId, "bar")
      givenAuditConnector()
      givenAdminUser("foo", "any")
      givenUserAuthorised()

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

  "GET /agent/:arn/service/IR-SA/client/ni/:identifierValue" should {

    val requestPath = s"/agent-client-relationships/agent/${arn.value}/service/IR-SA/client/ni/$nino"

    val enrolmentKey: EnrolmentKey = EnrolmentKey("IR-SA", Seq(Identifier("NINO", nino.value)))

    "return 200 when relationship exists only in cesa and relationship copy is never attempted" in {
      givenAgentRecord(arn, agentRecordResponse)
      givenArnIsKnownFor(arn, SaAgentReference("foo"))
      givenClientHasRelationshipWithAgentInCESA(nino, "foo")
      givenAuditConnector()
      givenUserAuthorised()

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
      givenAgentRecord(arn, agentRecordResponse)
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
      givenAuditConnector()
      givenUserAuthorised()
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
      givenAgentRecord(arn, agentRecordResponse)
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
      givenAuditConnector()
      givenUserAuthorised()
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
      givenAgentRecord(arn, agentRecordResponse)
      givenClientHasNoActiveRelationshipWithAgentInCESA(nino)
      givenAuditConnector()
      givenUserAuthorised()

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

    "return 401 when auth token is missing" in {
      givenAuditConnector()
      requestIsNotAuthenticated()

      await(repo.findBy(arn, enrolmentKey)) shouldBe empty

      val result = doAgentRequest(requestPath)
      result.status shouldBe 401

      await(repo.findBy(arn, enrolmentKey)) shouldBe empty
    }
  }

  private def doAgentRequest(route: String) = new Resource(route, port).get()

}
