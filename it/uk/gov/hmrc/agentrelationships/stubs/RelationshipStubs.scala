package uk.gov.hmrc.agentrelationships.stubs

import uk.gov.hmrc.agentmtdidentifiers.model.{CgtRef, MtdItId, Urn, Utr, Vrn}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}

trait RelationshipStubs extends EnrolmentStoreProxyStubs with UsersGroupsSearchStubs {

  def givenPrincipalUser(taxIdentifier: TaxIdentifier, groupId: String, userId: String = "any") = {
    givenPrincipalGroupIdExistsFor(taxIdentifier, groupId)
    givenPrincipalUserIdExistFor(taxIdentifier, userId)
  }

  def givenDelegatedGroupIdsNotExistForMtdItId(mtdItId: MtdItId) =
    givenDelegatedGroupIdsNotExistFor(mtdItId)

  def givenDelegatedGroupIdsExistForMtdItId(mtdItId: MtdItId, ids: String*) =
    givenDelegatedGroupIdsExistFor(mtdItId, Set("bar", "foo") ++ ids.toSet)

  def givenDelegatedGroupIdsNotExistForNino(nino: Nino) =
    givenDelegatedGroupIdsNotExistFor(nino)

  def givenDelegatedGroupIdsNotExistForMtdVatId(vrn: Vrn) =
    givenDelegatedGroupIdsNotExistFor(vrn)

  def givenDelegatedGroupIdsExistForMtdVatId(vrn: Vrn) =
    givenDelegatedGroupIdsExistFor(vrn, Set("bar", "foo"))

  def givenMTDITEnrolmentAllocationSucceeds(mtdItId: MtdItId, agentCode: String) =
    givenEnrolmentAllocationSucceeds("foo", "any", "HMRC-MTD-IT", "MTDITID", mtdItId.value, agentCode)

  def givenMTDVATEnrolmentAllocationSucceeds(vrn: Vrn, agentCode: String) =
    givenEnrolmentAllocationSucceeds("foo", "any", "HMRC-MTD-VAT", "VRN", vrn.value, agentCode)

  def givenDelegatedGroupIdsExistForTrust(utr: Utr, ids: String*) =
    givenDelegatedGroupIdsExistFor(utr, Set("bar", "foo") ++ ids.toSet)

  def givenDelegatedGroupIdsExistForTrustNT(urn: Urn, ids: String*) =
    givenDelegatedGroupIdsExistFor(urn, Set("bar", "foo") ++ ids.toSet)

  def givenDelegatedGroupIdsExistForCgt(cgtRef: CgtRef, ids: String*) =
    givenDelegatedGroupIdsExistFor(cgtRef, Set("bar", "foo") ++ ids.toSet)

  def givenDelegatedGroupIdsNotExistForTrust(utr: Utr) =
    givenDelegatedGroupIdsNotExistFor(utr)

  def givenDelegatedGroupIdsNotExistForTrustNT(urn: Urn) =
    givenDelegatedGroupIdsNotExistFor(urn)

  def givenDelegatedGroupIdsNotExistForCgt(cgtRef: CgtRef) =
    givenDelegatedGroupIdsNotExistFor(cgtRef)

  def givenTrustEnrolmentAllocationSucceeds(utr: Utr, agentCode: String) =
    givenEnrolmentAllocationSucceeds("foo", "any", "HMRC-TERS-ORG", "SAUTR", utr.value, agentCode)

  def givenTrustNTEnrolmentAllocationSucceeds(urn: Urn, agentCode: String) =
    givenEnrolmentAllocationSucceeds("foo", "any", "HMRC-TERSNT-ORG", "URN", urn.value, agentCode)

  def givenCGTEnrolmentAllocationSucceeds(cgtRef: CgtRef, agentCode: String) =
    givenEnrolmentAllocationSucceeds("foo", "any", "HMRC-CGT-PD", "CGTPDRef", cgtRef.value, agentCode)

  def givenAgentIsAllocatedAndAssignedToClient(taxIdentifier: TaxIdentifier, agentCode: String) =
    givenDelegatedGroupIdsExistFor(taxIdentifier, Set("foo"))

  def givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn: Vrn, agentCode: String) = {
    givenDelegatedGroupIdsExistForKey(s"HMCE-VATDEC-ORG~VATRegNo~${vrn.value}", Set("oldvatfoo"))
    givenGroupInfo("oldvatfoo", agentCode)
  }
}
