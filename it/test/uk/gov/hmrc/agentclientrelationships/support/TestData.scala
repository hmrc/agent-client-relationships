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

import play.api.libs.json.Format
import play.api.libs.json.Json
import play.api.libs.json.OFormat
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgencyDetails
import uk.gov.hmrc.agentclientrelationships.model.invitationLink.AgentDetailsDesResponse
import uk.gov.hmrc.agentmtdidentifiers.model.SuspensionDetails
import uk.gov.hmrc.agentmtdidentifiers.model.Utr

trait TestData {

  case class TestBusinessAddress(
    addressLine1: String,
    addressLine2: Option[String],
    addressLine3: Option[String] = None,
    addressLine4: Option[String] = None,
    postalCode: Option[String],
    countryCode: String
  )

  object TestBusinessAddress {
    implicit val format: OFormat[TestBusinessAddress] = Json.format
  }

  case class TestAgencyDetails(
    agencyName: Option[String],
    agencyEmail: Option[String],
    agencyTelephone: Option[String],
    agencyAddress: Option[TestBusinessAddress]
  )

  object TestAgencyDetails {
    implicit val format: OFormat[TestAgencyDetails] = Json.format
  }

  case class TestAgentDetailsDesResponse(
    uniqueTaxReference: Option[Utr],
    agencyDetails: Option[TestAgencyDetails],
    suspensionDetails: Option[SuspensionDetails]
  )

  object TestAgentDetailsDesResponse {
    implicit val format: Format[TestAgentDetailsDesResponse] = Json.format[TestAgentDetailsDesResponse]
  }

  val suspensionDetails: SuspensionDetails = SuspensionDetails(suspensionStatus = false, None)

  val suspensionDetailsSuspended: SuspensionDetails = SuspensionDetails(suspensionStatus = true, None)

  val agencyDetailsResponse: TestAgencyDetails = TestAgencyDetails(
    Some("My Agency"),
    Some("abc@abc.com"),
    Some("07345678901"),
    Some(
      TestBusinessAddress(
        "25 Any Street",
        Some("Central Grange"),
        Some("Telford"),
        None,
        Some("TF4 3TR"),
        "GB"
      )
    )
  )

  val existingAgencyDetailsResponse: TestAgencyDetails = TestAgencyDetails(
    Some("ExistingAgent"),
    Some("abc@example.com"),
    Some("07345678901"),
    Some(
      TestBusinessAddress(
        "25 Any Street",
        Some("Central Grange"),
        Some("Telford"),
        None,
        Some("TF4 3TR"),
        "GB"
      )
    )
  )

  val agentRecordResponse: TestAgentDetailsDesResponse = TestAgentDetailsDesResponse(
    uniqueTaxReference = Some(Utr("0123456789")),
    agencyDetails = Some(agencyDetailsResponse),
    suspensionDetails = Some(suspensionDetails)
  )

  val existingAgentRecordResponse: TestAgentDetailsDesResponse = TestAgentDetailsDesResponse(
    uniqueTaxReference = Some(Utr("0123456989")),
    agencyDetails = Some(existingAgencyDetailsResponse),
    suspensionDetails = Some(suspensionDetails)
  )

  val agentRecordResponseWithNoAgentName: TestAgentDetailsDesResponse = TestAgentDetailsDesResponse(
    uniqueTaxReference = Some(Utr("0123456789")),
    agencyDetails = Some(agencyDetailsResponse.copy(agencyName = None)),
    suspensionDetails = Some(suspensionDetails)
  )

  val suspendedAgentRecordResponse: TestAgentDetailsDesResponse = TestAgentDetailsDesResponse(
    uniqueTaxReference = Some(Utr("0123456789")),
    agencyDetails = Some(agencyDetailsResponse),
    suspensionDetails = Some(suspensionDetailsSuspended)
  )

  val agentDetails: AgencyDetails = AgencyDetails("My Agency", "abc@abc.com")

  val existingAgentDetails: AgencyDetails = AgencyDetails("ExistingAgent", "abc@abc.com")

  val agentRecord: AgentDetailsDesResponse = AgentDetailsDesResponse(
    agencyDetails = agentDetails,
    suspensionDetails = Some(suspensionDetails)
  )

  val existingAgentRecord: AgentDetailsDesResponse = AgentDetailsDesResponse(
    agencyDetails = existingAgentDetails,
    suspensionDetails = Some(suspensionDetails)
  )

  val suspendedAgentRecordOption: AgentDetailsDesResponse = AgentDetailsDesResponse(
    agencyDetails = agentDetails,
    suspensionDetails = Some(suspensionDetailsSuspended)
  )

}
