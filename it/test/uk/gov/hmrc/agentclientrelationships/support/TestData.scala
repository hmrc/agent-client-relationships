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

package uk.gov.hmrc.agentclientrelationships.support

import uk.gov.hmrc.agentclientrelationships.model.invitationLink.{AgencyDetails, AgentDetailsDesResponse, BusinessAddress}
import uk.gov.hmrc.agentmtdidentifiers.model.{SuspensionDetails, Utr}

trait TestData {

  val emptyAgencyDetails: AgencyDetails = AgencyDetails(None, None, None, None)

  val agentDetails: AgencyDetails = AgencyDetails(
    Some("My Agency"),
    Some("abc@abc.com"),
    Some("07345678901"),
    Some(BusinessAddress("25 Any Street", Some("Central Grange"), Some("Telford"), None, Some("TF4 3TR"), "GB"))
  )

  val suspensionDetails: SuspensionDetails = SuspensionDetails(suspensionStatus = false, None)

  val suspensionDetailsSuspended: SuspensionDetails = SuspensionDetails(suspensionStatus = true, None)

  val agentRecord: AgentDetailsDesResponse = AgentDetailsDesResponse(
    uniqueTaxReference = Some(Utr("0123456789")),
    agencyDetails = Some(agentDetails),
    suspensionDetails = Some(suspensionDetails)
  )

  val suspendedAgentRecordOption: AgentDetailsDesResponse = AgentDetailsDesResponse(
    uniqueTaxReference = Some(Utr("0123456789")),
    agencyDetails = Some(agentDetails),
    suspensionDetails = Some(suspensionDetailsSuspended)
  )

}
