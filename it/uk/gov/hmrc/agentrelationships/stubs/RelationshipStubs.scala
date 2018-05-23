package uk.gov.hmrc.agentrelationships.stubs

import uk.gov.hmrc.agentmtdidentifiers.model.{MtdItId, Vrn}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}

trait RelationshipStubs extends EnrolmentStoreProxyStubs with UsersGroupsSearchStubs {

  def givenPrincipalUser(taxIdentifier: TaxIdentifier, groupId: String, userId: String = "any") = {
    givenPrincipalGroupIdExistsFor(taxIdentifier, groupId)
    givenPrincipalUserIdExistFor(taxIdentifier, userId)
  }

  def givenDelegatedGroupIdsNotExistForMtdItId(mtdItId: MtdItId) =
    givenDelegatedGroupIdsNotExistFor(mtdItId)

  def givenDelegatedGroupIdsNotExistForNino(nino: Nino) =
    givenDelegatedGroupIdsNotExistFor(nino)

  def givenDelegatedGroupIdsNotExistForMtdVatId(vrn: Vrn) =
    givenDelegatedGroupIdsNotExistFor(vrn)

  def givenMTDITEnrolmentAllocationSucceeds(mtdItId: MtdItId, agentCode: String) =
    givenEnrolmentAllocationSucceeds("foo", "any", "HMRC-MTD-IT", "MTDITID", mtdItId.value, agentCode)

  def givenMTDVATEnrolmentAllocationSucceeds(vrn: Vrn, agentCode: String) =
    givenEnrolmentAllocationSucceeds("foo", "any", "HMRC-MTD-VAT", "VRN", vrn.value, agentCode)

  def givenAgentIsAllocatedAndAssignedToClient(taxIdentifier: TaxIdentifier, agentCode: String) =
    givenDelegatedGroupIdsExistFor(taxIdentifier, Set("foo"))

  def givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn: Vrn, agentCode: String) = {
    givenDelegatedGroupIdsExistForKey(s"HMCE-VATDEC-ORG~VATRegNo~${vrn.value}", Set("oldvatfoo"))
    givenGroupInfo("oldvatfoo", agentCode)
  }
}
