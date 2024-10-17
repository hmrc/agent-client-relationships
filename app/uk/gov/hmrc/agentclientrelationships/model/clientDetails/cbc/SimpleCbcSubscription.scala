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

package uk.gov.hmrc.agentclientrelationships.model.clientDetails.cbc

import play.api.libs.json._

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

case class SimpleCbcSubscription(tradingName: Option[String], otherNames: Seq[String], isGBUser: Boolean, emails: Seq[String]) {
  def anyAvailableName: Option[String] = tradingName.orElse(otherNames.headOption)
}

object SimpleCbcSubscription {
  implicit val reads: Reads[SimpleCbcSubscription] = { json =>
    val isGBUser = (json \ "displaySubscriptionForCBCResponse" \ "responseDetail" \ "isGBUser").as[Boolean]
    val tradingName = (json \ "displaySubscriptionForCBCResponse" \ "responseDetail" \ "tradingName").asOpt[String]
    val primaryContact =
      (json \ "displaySubscriptionForCBCResponse" \ "responseDetail" \ "primaryContact").as[Seq[CbcContact]]
    val secondaryContact =
      (json \ "displaySubscriptionForCBCResponse" \ "responseDetail" \ "secondaryContact").as[Seq[CbcContact]]
    val contacts = primaryContact ++ secondaryContact

    val otherNames: Seq[String] = contacts.collect {
      case CbcContact(_, Some(ind: CbcIndividual), _) => ind.name
      case CbcContact(_, _, Some(org: CbcOrganisation)) => org.organisationName
    }

    val emails = contacts.map(_.email).collect { case eml => eml }

    JsSuccess(SimpleCbcSubscription(tradingName, otherNames, isGBUser, emails))
  }
}

//-----------------------------------------------------------------------------

case class DisplaySubscriptionForCBCRequest(displaySubscriptionForCBCRequest: DisplaySubscriptionDetails)

object DisplaySubscriptionForCBCRequest {
  implicit val writes: Writes[DisplaySubscriptionForCBCRequest] = Json.writes[DisplaySubscriptionForCBCRequest]
}

//-----------------------------------------------------------------------------

case class DisplaySubscriptionDetails(requestCommon: RequestCommonForSubscription,
                                      requestDetail: ReadSubscriptionRequestDetail)

object DisplaySubscriptionDetails {
  implicit val writes: Writes[DisplaySubscriptionDetails] = Json.writes[DisplaySubscriptionDetails]
}

//-----------------------------------------------------------------------------

case class RequestCommonForSubscription(regime: String,
                                        receiptDate: String,
                                        acknowledgementReference: String,
                                        originatingSystem: String,
                                        conversationID: Option[String])

object RequestCommonForSubscription {
  // Format: ISO 8601 YYYY-MM-DDTHH:mm:ssZ e.g. 2020-09-23T16:12:11Zs
  private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

  implicit val writes: Writes[RequestCommonForSubscription] = Json.writes[RequestCommonForSubscription]

  def apply(): RequestCommonForSubscription = {
    // Generate a 32 chars UUID without hyphens
    val acknowledgementReference = UUID.randomUUID().toString.replace("-", "")

    RequestCommonForSubscription(
      regime = "CBC",
      receiptDate = ZonedDateTime.now().format(formatter),
      acknowledgementReference = acknowledgementReference,
      originatingSystem = "MDTP",
      conversationID = None
    )
  }
}

//-----------------------------------------------------------------------------

case class ReadSubscriptionRequestDetail(IDType: String, IDNumber: String)

object ReadSubscriptionRequestDetail {
  implicit val writes: Writes[ReadSubscriptionRequestDetail] = Json.writes[ReadSubscriptionRequestDetail]
}

//-----------------------------------------------------------------------------

case class CbcIndividual(firstName: String, lastName: String) { def name: String = s"$firstName $lastName" }
object CbcIndividual { implicit val reads: Reads[CbcIndividual] = Json.reads[CbcIndividual] }
case class CbcOrganisation(organisationName: String)
object CbcOrganisation { implicit val reads: Reads[CbcOrganisation] = Json.reads[CbcOrganisation] }
case class CbcContact(email: String, individual: Option[CbcIndividual], organisation: Option[CbcOrganisation])
object CbcContact { implicit val reads: Reads[CbcContact] = Json.reads[CbcContact] }
