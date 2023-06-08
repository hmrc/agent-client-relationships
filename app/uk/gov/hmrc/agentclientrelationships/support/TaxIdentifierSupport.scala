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
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}

trait TaxIdentifierSupport {

  protected def buildEnrolmentKey(service: Service, taxIdentifier: TaxIdentifier): String =
    (service, taxIdentifier) match {
      case (_, Arn(value))                       => s"HMRC-AS-AGENT~AgentReferenceNumber~$value"
      case (Service.MtdIt, MtdItId(value))       => s"HMRC-MTD-IT~MTDITID~$value"
      case (Service.Vat, Vrn(value))             => s"HMRC-MTD-VAT~VRN~$value"
      case (Service.MtdIt, Nino(value))          => s"HMRC-MTD-IT~NINO~$value"
      case (Service.Trust, Utr(value))           => s"HMRC-TERS-ORG~SAUTR~$value"
      case (Service.TrustNT, Urn(value))         => s"HMRC-TERSNT-ORG~URN~$value"
      case (Service.CapitalGains, CgtRef(value)) => s"HMRC-CGT-PD~CGTPDRef~$value"
      case (Service.Ppt, PptRef(value))          => s"HMRC-PPT-ORG~EtmpRegistrationNumber~$value"
      case _                                     => throw new IllegalArgumentException(s"Service/tax id combination not supported: $service $taxIdentifier")
    }

  protected def identifierNickname(taxIdentifier: TaxIdentifier): String = taxIdentifier match {
    case _: Arn     => "ARN"
    case _: MtdItId => "MTDITID"
    case _: Vrn     => "VRN"
    case _: Nino    => "NINO"
    case _: Utr     => "SAUTR"
    case _: Urn     => "URN"
    case _: CgtRef  => "CGTPDRef"
    case _: PptRef  => "EtmpRegistrationNumber"
    case _          => throw new IllegalArgumentException(s"Tax identifier not supported $taxIdentifier")
  }

}

object TaxIdentifierSupport {
  def from(value: String, `type`: String): TaxIdentifier = `type` match {
    case "MTDITID"                => MtdItId(value)
    case "NINO"                   => Nino(value)
    case "VRN"                    => Vrn(value)
    case "AgentReferenceNumber"   => Arn(value)
    case "SAUTR"                  => Utr(value)
    case "URN"                    => Urn(value)
    case "CGTPDRef"               => CgtRef(value)
    case "EtmpRegistrationNumber" => PptRef(value)
    case _                        => throw new Exception("Invalid tax identifier type " + `type`)
  }
}
