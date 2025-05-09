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

package uk.gov.hmrc.agentclientrelationships.model

import play.api.Logging
import play.api.libs.json._

sealed trait InvitationStatus

case object Pending
extends InvitationStatus

case object Expired
extends InvitationStatus

case object Rejected
extends InvitationStatus

case object Accepted
extends InvitationStatus

case object Cancelled
extends InvitationStatus

case object DeAuthorised
extends InvitationStatus

case object PartialAuth
extends InvitationStatus

object InvitationStatus
extends Logging {

  def apply(status: String): InvitationStatus =
    status.toLowerCase match {
      case "pending" => Pending
      case "rejected" => Rejected
      case "accepted" => Accepted
      case "cancelled" => Cancelled
      case "expired" => Expired
      case "deauthorised" => DeAuthorised
      case "partialauth" => PartialAuth
      case value =>
        logger.warn(s"Status of [$value] is not a valid InvitationStatus")
        throw new IllegalArgumentException
    }

  def unapply(status: InvitationStatus): String =
    status match {
      case Pending => "Pending"
      case Rejected => "Rejected"
      case Accepted => "Accepted"
      case Cancelled => "Cancelled"
      case Expired => "Expired"
      case DeAuthorised => "Deauthorised"
      case PartialAuth => "Partialauth"
    }

  implicit val format: Format[InvitationStatus] =
    new Format[InvitationStatus] {
      override def reads(json: JsValue): JsResult[InvitationStatus] = JsSuccess(apply(json.as[String]))
      override def writes(status: InvitationStatus): JsValue = JsString(unapply(status))
    }

}
