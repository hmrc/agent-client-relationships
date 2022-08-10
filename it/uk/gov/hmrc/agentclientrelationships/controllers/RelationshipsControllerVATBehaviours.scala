package uk.gov.hmrc.agentclientrelationships.controllers

import play.api.test.Helpers._
import uk.gov.hmrc.agentclientrelationships.audit.AgentClientRelationshipEvent
import uk.gov.hmrc.agentclientrelationships.repository.RelationshipReference.VatRef
import uk.gov.hmrc.agentclientrelationships.repository.{RelationshipCopyRecord, SyncStatus}
import uk.gov.hmrc.domain.AgentCode

import scala.concurrent.ExecutionContext.Implicits.global

// TODO. All of the following tests should be rewritten directly against a RelationshipsController instance (with appropriate mocks/stubs)
// rather than instantiating a whole app and sending a real HTTP request. It makes test setup and debug very difficult.

trait RelationshipsControllerVATBehaviours { this: RelationshipsBaseControllerISpec =>

  def relationshipControllerVATSpecificBehaviours(): Unit = {
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

      //HAPPY PATHS WHEN CHECKING HMRC-VATDEC-ORG

      "return 200 when agent not allocated to client in es but relationship exists in HMCE-VATDEC-ORG" in {
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
        givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
        givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
        givenAgentCanBeAllocatedInIF(vrn, arn)
        givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")
        givenAdminUser("foo", "any")
        getVrnIsKnownInETMPFor(vrn)
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo", withThisGgUserId = "any", withThisAgentCode = "bar")

        def query() =
          repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

        await(query()) shouldBe empty

        val result = doRequest
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
            "Journey"                 -> "CopyExistingESRelationship",
            "vrnExistsInEtmp"         -> "true"
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
        givenDelegatedGroupIdsNotExistFor(vrn)
        givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
        givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
        givenAgentCanBeAllocatedInIF(vrn, arn)
        getVrnIsKnownInETMPFor(vrn)
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo")

        def query() =
          repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

        await(query()) shouldBe empty

        val result = doRequest
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
            "service"                 -> "mtd-vat",
            "vrn"                     -> vrn.value,
            "oldAgentCodes"           -> oldAgentCode,
            "ESRelationship"          -> "true",
            "etmpRelationshipCreated" -> "true",
            "enrolmentDelegated"      -> "false",
            "AgentDBRecord"           -> "true",
            "Journey"                 -> "CopyExistingESRelationship",
            "vrnExistsInEtmp"         -> "true"
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
        givenAgentCanNotBeAllocatedInIF(status = 404)
        givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")
        givenAdminUser("foo", "any")
        getVrnIsKnownInETMPFor(vrn)
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo", withThisGgUserId = "any", withThisAgentCode = "bar")

        def query() =
          repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

        await(query()) shouldBe empty

        val result = doRequest
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
        givenAgentCanBeAllocatedInIF(vrn, arn)
        givenEnrolmentAllocationFailsWith(404)("foo", "any", "HMRC-MTD-VAT", "VRN", vrn.value, "bar")
        givenAdminUser("foo", "any")
        getVrnIsKnownInETMPFor(vrn)
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo", withThisGgUserId = "any", withThisAgentCode = "bar")

        def query() =
          repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

        await(query()) shouldBe empty

        val result = doRequest
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
            "Journey"                 -> "CopyExistingESRelationship",
            "vrnExistsInEtmp"         -> "true"
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

      "return 200 when relationship exists only in HMCE-VATDEC-ORG and relationship copy attempt fails because vrn is not known in ETMP" in {
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
        givenArnIsKnownFor(arn, AgentCode(oldAgentCode))
        givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
        getVrnIsNotKnownInETMPFor(vrn)
        givenAdminUser("foo", "any")

        def query() =
          repo.find("arn" -> arn.value, "clientIdentifier" -> vrn.value, "clientIdentifierType" -> mtdVatIdType)

        await(query()) shouldBe empty

        val result = doRequest
        result.status shouldBe 200

        await(query()) shouldBe empty

        verifyAuditRequestSent(
          1,
          event = AgentClientRelationshipEvent.CreateRelationship,
          detail = Map(
            "arn"                     -> arn.value,
            "service"                 -> "mtd-vat",
            "vrn"                     -> vrn.value,
            "oldAgentCodes"           -> oldAgentCode,
            "ESRelationship"          -> "true",
            "etmpRelationshipCreated" -> "",
            "enrolmentDelegated"      -> "",
            "AgentDBRecord"           -> "",
            "Journey"                 -> "CopyExistingESRelationship",
            "vrnExistsInEtmp"         -> "false"
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
        givenAdminUser("foo", "any")
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo", withThisGgUserId = "any", withThisAgentCode = "bar")

        await(repo.insert(relationshipCopiedSuccessfullyForMtdVat))
        val result = doRequest
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
        givenAdminUser("foo", "any")
        givenAgentCanBeAllocatedInIF(vrn, arn)
        givenMTDVATEnrolmentAllocationSucceeds(vrn, "bar")
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo", withThisGgUserId = "any", withThisAgentCode = "bar")

        await(repo.insert(relationshipCopiedSuccessfullyForMtdVat))
        val result = doRequest
        result.status shouldBe 404
        (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
      }

      "return 404 when credentials are not found but relationship copy was made before" in {
        givenPrincipalGroupIdNotExistsFor(arn)
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo", withThisGgUserId = "any", withThisAgentCode = "bar")

        await(repo.insert(relationshipCopiedSuccessfullyForMtdVat))
        val result = doRequest
        result.status shouldBe 404
      }

      "return 404 when mapping service is unavailable" in {
        givenPrincipalUser(arn, "foo")
        givenGroupInfo("foo", "bar")
        givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
        givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn, oldAgentCode)
        givenServiceReturnsServiceUnavailable()
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo", withThisGgUserId = "any", withThisAgentCode = "bar")

        val result = doRequest
        result.status shouldBe 404
      }
    }

    "GET /agent/:arn/service/HMCE-VATDEC-ORG/client/vrn/:vrn" should {

      val requestPath = s"/agent-client-relationships/agent/${arn.value}/service/HMCE-VATDEC-ORG/client/vrn/${vrn.value}"

      def doRequest = doAgentGetRequest(requestPath)

      "return 404 when agent not allocated to client in es" in {
        givenDelegatedGroupIdsNotExistForMtdVatId(vrn)
        givenDelegatedGroupIdsNotExistForKey(s"HMCE-VATDEC-ORG~VATRegNo~${vrn.value}")
        givenUserIsSubscribedAgent(arn, withThisGroupId = "foo", withThisGgUserId = "any", withThisAgentCode = "bar")
        val result = doRequest
        result.status shouldBe 404
        (result.json \ "code").as[String] shouldBe "RELATIONSHIP_NOT_FOUND"
      }

      "return 404 when agent not allocated to client in agent-mapping but allocated in es" in {
        givenAgentIsAllocatedAndAssignedToClient(vrn, "bar")
        givenArnIsUnknownFor(arn)
        givenDelegatedGroupIdsNotExistForKey(s"HMCE-VATDEC-ORG~VATRegNo~${vrn.value}")
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
  }
}
