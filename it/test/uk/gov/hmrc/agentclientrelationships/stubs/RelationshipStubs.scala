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

import uk.gov.hmrc.agentclientrelationships.model.EnrolmentKey
import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Service, Vrn}
import uk.gov.hmrc.domain.Nino

trait RelationshipStubs extends EnrolmentStoreProxyStubs with UsersGroupsSearchStubs {

  def givenPrincipalAgentUser(arn: Arn, groupId: String, userId: String = "any") = {
    givenPrincipalGroupIdExistsFor(agentEnrolmentKey(arn), groupId)
    givenPrincipalUserIdExistFor(agentEnrolmentKey(arn), userId)
  }

  def givenDelegatedGroupIdsNotExistForMtdItId(mtdItId: MtdItId) = givenDelegatedGroupIdsNotExistFor(
    EnrolmentKey(Service.MtdIt, mtdItId)
  )

  def givenDelegatedGroupIdsNotExistForMtdItIdSupp(mtdItId: MtdItId) = givenDelegatedGroupIdsNotExistFor(
    EnrolmentKey(Service.MtdItSupp, mtdItId)
  )

  def givenDelegatedGroupIdsExistForMtdItId(mtdItId: MtdItId, ids: String*) = givenDelegatedGroupIdsExistFor(
    EnrolmentKey(Service.MtdIt, mtdItId),
    Set("bar", "foo") ++ ids.toSet
  )

  def givenDelegatedGroupIdsNotExistForNino(nino: Nino) = givenDelegatedGroupIdsNotExistFor(
    EnrolmentKey(Service.MtdIt, nino)
  )

  def givenDelegatedGroupIdsNotExistForMtdVatId(vrn: Vrn) = givenDelegatedGroupIdsNotExistFor(
    EnrolmentKey(Service.Vat, vrn)
  )

  def givenDelegatedGroupIdsExistForMtdVatId(vrn: Vrn) = givenDelegatedGroupIdsExistFor(
    EnrolmentKey(Service.Vat, vrn),
    Set("bar", "foo")
  )

  def givenMTDITEnrolmentAllocationSucceeds(mtdItId: MtdItId, agentCode: String) = givenEnrolmentAllocationSucceeds(
    "foo",
    "any",
    EnrolmentKey(Service.MtdIt, mtdItId),
    agentCode
  )

  def givenMTDITSUPPEnrolmentAllocationSucceeds(mtdItId: MtdItId, agentCode: String) = givenEnrolmentAllocationSucceeds(
    "foo",
    "any",
    EnrolmentKey(Service.MtdItSupp, mtdItId),
    agentCode
  )

  def givenMTDVATEnrolmentAllocationSucceeds(vrn: Vrn, agentCode: String) = givenEnrolmentAllocationSucceeds(
    "foo",
    "any",
    EnrolmentKey(Service.Vat, vrn),
    agentCode
  )

  def givenDelegatedGroupIdsExistForEnrolmentKey(enrolmentKey: EnrolmentKey, ids: String*) =
    givenDelegatedGroupIdsExistFor(enrolmentKey, Set("bar", "foo") ++ ids.toSet)

  def givenDelegatedGroupIdsNotExistForEnrolmentKey(enrolmentKey: EnrolmentKey) = givenDelegatedGroupIdsNotExistFor(
    enrolmentKey
  )

  def givenServiceEnrolmentAllocationSucceeds(enrolmentKey: EnrolmentKey, agentCode: String) =
    givenEnrolmentAllocationSucceeds("foo", "any", enrolmentKey, agentCode)

  def givenAgentIsAllocatedAndAssignedToClient(enrolmentKey: EnrolmentKey, agentCode: String) =
    givenDelegatedGroupIdsExistFor(enrolmentKey, Set("foo"))

  def givenAgentIsAllocatedAndAssignedToClientForHMCEVATDECORG(vrn: Vrn, agentCode: String) = {
    givenDelegatedGroupIdsExistFor(EnrolmentKey(s"HMCE-VATDEC-ORG~VATRegNo~${vrn.value}"), Set("oldvatfoo"))
    givenGroupInfo("oldvatfoo", agentCode)
  }
}
