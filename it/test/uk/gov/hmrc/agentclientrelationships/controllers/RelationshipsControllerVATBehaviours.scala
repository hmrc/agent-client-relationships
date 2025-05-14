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

import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipReference.VatRef
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipCopyRecord
import uk.gov.hmrc.agentclientrelationships.repository.SyncStatus
import uk.gov.hmrc.agentclientrelationships.stubs.HipStub
import uk.gov.hmrc.agentmtdidentifiers.model.Service
import uk.gov.hmrc.domain.AgentCode

// TODO. All of the following tests should be rewritten directly against a RelationshipsController instance (with appropriate mocks/stubs)
// rather than instantiating a whole app and sending a real HTTP request. It makes test setup and debug very difficult.

trait RelationshipsControllerVATBehaviours {
  this: RelationshipsBaseControllerISpec
    with HipStub =>

  def relationshipControllerVATSpecificBehaviours(): Unit = {
    val relationshipCopiedSuccessfullyForMtdVat = RelationshipCopyRecord(
      arn.value,
      Some(EnrolmentKey(Service.Vat, vrn)),
      syncToETMPStatus = Some(SyncStatus.Success),
      syncToESStatus = Some(SyncStatus.Success)
    )

    "GET /agent/:arn/service/HMRC-MTD-VAT/client/VRN/:vrn" should {

      val requestPath: String = s"/agent-client-relationships/agent/${arn.value}/service/HMRC-MTD-VAT/client/VRN/${vrn.value}"

      def doRequest = doGetRequest(requestPath)

      // HAPPY PATHS WHEN CHECKING HMRC-VATDEC-ORG

      "return 200 when agent not allocated to client in es but relationship exists in HMCE-VATDEC-ORG" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
        givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
        givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
        givenAgentCanBeAllocated(vrn, arn)
        givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")
        givenAdminUser("foo", "any")
        getVrnIsKnownInETMPFor(vrn)
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        await(repo.findBy(arn, vatEnrolmentKey)) shouldBe empty

        val result = doRequest
        result.status shouldBe 200

        await(repo.findBy(arn, vatEnrolmentKey)).get should have(
          Symbol("arn")(arn.value),
          Symbol("enrolmentKey")(Some(vatEnrolmentKey)),
          Symbol("references")(Some(Set(VatRef(AgentCode(oldAgentCode))))),
          Symbol("syncToETMPStatus")(Some(SyncStatus.Success)),
          Symbol("syncToESStatus")(Some(SyncStatus.Success))
        )

        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.CreateRelationship,
          detail = Map(
            "agentReferenceNumber" -> arn.value,
            "credId" -> "any",
            "agentCode" -> "bar",
            "oldAgentCodes" -> oldAgentCode,
            "service" -> "mtd-vat",
            "vrn" -> vrn.value,
            "ESRelationship" -> "true",
            "etmpRelationshipCreated" -> "true",
            "enrolmentDelegated" -> "true",
            "howRelationshipCreated" -> "CopyExistingESRelationship",
            "vrnExistsInEtmp" -> "true"
          ),
          tags = Map("transactionName" -> "create-relationship", "path" -> requestPath)
        )

        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.CheckES,
          detail = Map(
            "agentReferenceNumber" -> arn.value,
            "credId" -> "any",
            "agentCode" -> "bar",
            "oldAgentCodes" -> oldAgentCode,
            "vrn" -> vrn.value,
            "ESRelationship" -> "true"
          ),
          tags = Map("transactionName" -> "check-es", "path" -> requestPath)
        )
      }

      "return 200 when agent credentials unknown but relationship exists in HMCE-VATDEC-ORG" in {
        givenPrincipalGroupIdNotExistsFor(agentEnrolmentKey(arn))
        givenDelegatedGroupIdsNotExistFor(EnrolmentKey(Service.Vat, vrn))
        givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
        givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
        givenAgentCanBeAllocated(vrn, arn)
        getVrnIsKnownInETMPFor(vrn)
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo")

        await(repo.findBy(arn, vatEnrolmentKey)) shouldBe None

        val result = doRequest
        result.status shouldBe 200

        // the status is 200 but the relationship copy cannot have succeeded with an unknown agent
        await(repo.findBy(arn, vatEnrolmentKey)) shouldBe None

        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.CheckES,
          detail = Map(
            "agentReferenceNumber" -> arn.value,
            "vrn" -> vrn.value,
            "oldAgentCodes" -> oldAgentCode,
            "ESRelationship" -> "true"
          ),
          tags = Map("transactionName" -> "check-es", "path" -> requestPath)
        )
      }

      // HAPPY PATHS WHEN RELATIONSHIP COPY ATTEMPT FAILS

      "return 200 when relationship exists only in HMCE-VATDEC-ORG and relationship copy attempt fails because of etmp" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
        givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
        givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
        givenAgentCanNotBeAllocated(status = 404)
        givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")
        givenAdminUser("foo", "any")
        getVrnIsKnownInETMPFor(vrn)
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        await(repo.findBy(arn, vatEnrolmentKey)) shouldBe None

        val result = doRequest
        result.status shouldBe 200

        await(repo.findBy(arn, vatEnrolmentKey)).get should have(
          Symbol("arn")(arn.value),
          Symbol("enrolmentKey")(Some(vatEnrolmentKey)),
          Symbol("references")(Some(Set(VatRef(AgentCode(oldAgentCode))))),
          Symbol("syncToETMPStatus")(Some(SyncStatus.Failed)),
          Symbol("syncToESStatus")(None)
        )
      }

      "return 200 when relationship exists only in HMCE-VATDEC-ORG and relationship copy attempt fails because of es" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
        givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
        givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
        givenAgentCanBeAllocated(vrn, arn)
        givenEnrolmentAllocationFailsWith(404)(
          "foo",
          "any",
          EnrolmentKey(Service.Vat, vrn),
          "bar"
        )
        givenAdminUser("foo", "any")
        getVrnIsKnownInETMPFor(vrn)
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        await(repo.findBy(arn, vatEnrolmentKey)) shouldBe None

        val result = doRequest
        result.status shouldBe 200

        await(repo.findBy(arn, vatEnrolmentKey)).get should have(
          Symbol("arn")(arn.value),
          Symbol("enrolmentKey")(Some(vatEnrolmentKey)),
          Symbol("references")(Some(Set(VatRef(AgentCode(oldAgentCode))))),
          Symbol("syncToETMPStatus")(Some(SyncStatus.Success)),
          Symbol("syncToESStatus")(Some(SyncStatus.Failed))
        )

        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.CreateRelationship,
          detail = Map(
            "agentReferenceNumber" -> arn.value,
            "credId" -> "any",
            "agentCode" -> "bar",
            "service" -> "mtd-vat",
            "vrn" -> vrn.value,
            "oldAgentCodes" -> oldAgentCode,
            "ESRelationship" -> "true",
            "etmpRelationshipCreated" -> "true",
            "enrolmentDelegated" -> "false",
            "howRelationshipCreated" -> "CopyExistingESRelationship",
            "vrnExistsInEtmp" -> "true"
          ),
          tags = Map("transactionName" -> "create-relationship", "path" -> requestPath)
        )

        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.CheckES,
          detail = Map(
            "agentReferenceNumber" -> arn.value,
            "credId" -> "any",
            "agentCode" -> "bar",
            "ESRelationship" -> "true",
            "vrn" -> vrn.value,
            "oldAgentCodes" -> oldAgentCode
          ),
          tags = Map("transactionName" -> "check-es", "path" -> requestPath)
        )
      }

      "return 200 when relationship exists only in HMCE-VATDEC-ORG and relationship copy attempt fails because vrn is not known in ETMP" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
        givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
        givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
        getVrnIsNotKnownInETMPFor(vrn)
        givenAdminUser("foo", "any")

        await(repo.findBy(arn, vatEnrolmentKey)) shouldBe None

        val result = doRequest
        result.status shouldBe 200

        await(repo.findBy(arn, vatEnrolmentKey)) shouldBe None

        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.CreateRelationship,
          detail = Map(
            "agentReferenceNumber" -> arn.value,
            "service" -> "mtd-vat",
            "vrn" -> vrn.value,
            "oldAgentCodes" -> oldAgentCode,
            "ESRelationship" -> "true",
            "howRelationshipCreated" -> "CopyExistingESRelationship",
            "vrnExistsInEtmp" -> "false"
          ),
          tags = Map("transactionName" -> "create-relationship", "path" -> requestPath)
        )

        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.CheckES,
          detail = Map(
            "agentReferenceNumber" -> arn.value,
            "credId" -> "any",
            "agentCode" -> "bar",
            "ESRelationship" -> "true",
            "vrn" -> vrn.value,
            "oldAgentCodes" -> oldAgentCode
          ),
          tags = Map("transactionName" -> "check-es", "path" -> requestPath)
        )
      }

      "return 404 when relationship is not found in es but relationship copy was made before" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        await(repo.collection.insertOne(relationshipCopiedSuccessfullyForMtdVat).toFuture())
        val result = doRequest
        result.status shouldBe 404
        (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
      }

      "return 404 when relationship was previously copied from HMCE-VATDEC-ORG to ETMP & ES but has since been deleted from ETMP & ES " +
        "(even though the relationship upon which the copy was based still exists in HMCE-VATDEC-ORG)" in {
          givenPrincipalAgentUser(arn, "foo")
          givenGroupInfo("foo", "bar")
          givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
          givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
          givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
          givenAdminUser("foo", "any")
          givenAgentCanBeAllocated(vrn, arn)
          givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")
          givenUserIsSubscribedAgent(
            arn,
            withThisGroupId = "foo",
            withThisGgUserId = "any",
            withThisAgentCode = "bar"
          )

          await(repo.collection.insertOne(relationshipCopiedSuccessfullyForMtdVat).toFuture())
          val result = doRequest
          result.status shouldBe 404
          (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
        }

      "return 404 when credentials are not found but relationship copy was made before" in {
        givenPrincipalGroupIdNotExistsFor(agentEnrolmentKey(arn))
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        await(repo.collection.insertOne(relationshipCopiedSuccessfullyForMtdVat).toFuture())
        val result = doRequest
        result.status shouldBe 404
      }

      "return 404 when mapping service is unavailable" in {
        givenPrincipalAgentUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
        givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
        givenServiceReturnsServiceUnavailable()
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )

        val result = doRequest
        result.status shouldBe 404
      }
    }

    "GET /agent/:arn/service/HMCE-VATDEC-ORG/client/vrn/:vrn" should {

      val requestPath = s"/agent-client-relationships/agent/${arn.value}/service/HMCE-VATDEC-ORG/client/vrn/${vrn.value}"

      def doRequest = doGetRequest(requestPath)

      "return 404 when agent not allocated to client in es" in {
        givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
        givenDelegatedGroupIdsNotExistFor(EnrolmentKey(s"HMCE-VATDEC-ORG~VATRegNo~${vrn.value}"))
        givenUserIsSubscribedAgent(
          arn,
          withThisGroupId = "foo",
          withThisGgUserId = "any",
          withThisAgentCode = "bar"
        )
        val result = doRequest
        result.status shouldBe 404
        (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
      }

      "return 404 when agent not allocated to client in agent-mapping but allocated in es" in {
        givenAgentIsAllocatedAndAssignedToClient(EnrolmentKey(Service.Vat, vrn), "bar")
        givenArnIsUnknownFor(arn)
        givenDelegatedGroupIdsNotExistFor(EnrolmentKey(s"HMCE-VATDEC-ORG~VATRegNo~${vrn.value}"))
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo")
        val result = doRequest
        result.status shouldBe 404
        (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
      }

      "return 404 when mapping is unavailable" in {
        givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, "foo")
        givenServiceReturnsServiceUnavailable()
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo")

        val result = doRequest
        result.status shouldBe 404
      }

      "return 200 when agent credentials unknown but relationship exists in mapping" in {
        givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
        givenArnIsKnownFor(arn, AgentCode(oldAgentCode))

        val result = doRequest
        result.status shouldBe 200

        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.CheckES,
          detail = Map(
            "agentReferenceNumber" -> arn.value,
            "vrn" -> vrn.value,
            "oldAgentCodes" -> oldAgentCode,
            "ESRelationship" -> "true"
          ),
          tags = Map("transactionName" -> "check-es", "path" -> requestPath)
        )
      }
    }
  }
}
