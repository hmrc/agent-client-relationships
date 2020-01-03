/*
 * Copyright 2020 HM Revenue & Customs
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

import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, CgtRef, MtdItId, Utr, Vrn}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}

trait TaxIdentifierSupport {

  protected def enrolmentKeyPrefixFor(taxIdentifier: TaxIdentifier): String = taxIdentifier match {
    case _: Arn     => "HMRC-AS-AGENT~AgentReferenceNumber"
    case _: MtdItId => "HMRC-MTD-IT~MTDITID"
    case _: Vrn     => "HMRC-MTD-VAT~VRN"
    case _: Nino    => "HMRC-MTD-IT~NINO"
    case _: Utr     => "HMRC-TERS-ORG~SAUTR"
    case _: CgtRef  => "HMRC-CGT-PD~CGTPDRef"
    case _          => throw new IllegalArgumentException(s"Tax identifier not supported $taxIdentifier")
  }

  protected def identifierNickname(taxIdentifier: TaxIdentifier): String = taxIdentifier match {
    case _: Arn     => "ARN"
    case _: MtdItId => "MTDITID"
    case _: Vrn     => "VRN"
    case _: Nino    => "NINO"
    case _: Utr     => "SAUTR"
    case _: CgtRef  => "CGTPDRef"
    case _          => throw new IllegalArgumentException(s"Tax identifier not supported $taxIdentifier")
  }

}

object TaxIdentifierSupport {
  def from(value: String, `type`: String): TaxIdentifier = `type` match {
    case "MTDITID"              => MtdItId(value)
    case "NINO"                 => Nino(value)
    case "VRN"                  => Vrn(value)
    case "AgentReferenceNumber" => Arn(value)
    case "SAUTR"                => Utr(value)
    case "CGTPDRef"             => CgtRef(value)
    case _                      => throw new Exception("Invalid tax identifier type " + `type`)
  }
}
