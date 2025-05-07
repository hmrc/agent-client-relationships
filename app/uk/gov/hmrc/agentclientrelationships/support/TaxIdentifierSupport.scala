/*
 * Copyright 2023 HM Revenue & Customs
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

import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.TaxIdentifier

import scala.util.Try

object TaxIdentifierSupport {

  def identifierNickname(taxIdentifier: TaxIdentifier): String =
    taxIdentifier match {
      case _: Arn => "ARN"
      case taxId =>
        Try(ClientIdentifier(taxId).enrolmentId).getOrElse(
          throw new IllegalArgumentException("unsupported tax identifier: " + taxId)
        )
    }

  def from(value: String, `type`: String): TaxIdentifier =
    `type` match {
      case "AgentReferenceNumber" => Arn(value)
      case _ =>
        ClientIdType
          .supportedTypes
          .find(_.enrolmentId == `type`)
          .map(_.createUnderlying(value))
          .getOrElse(throw new IllegalArgumentException("unsupported tax identifier type: " + `type`))
    }
}
