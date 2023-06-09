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

import uk.gov.hmrc.agentmtdidentifiers.model.IdentifierKeys._
import uk.gov.hmrc.agentmtdidentifiers.model.Service._
import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}

trait TaxIdentifierSupport {

  protected def enrolmentKeyPrefixFor(taxIdentifier: TaxIdentifier): String = taxIdentifier match {
    case _: Arn        => s"$HMRC_AS_AGENT~AgentReferenceNumber"
    case _: MtdItId    => s"$HMRC_MTD_IT~$mtdItId"
    case _: Vrn        => s"$HMRC_MTD_VAT~$vrn"
    case _: Nino       => s"$HMRC_MTD_IT~$nino"
    case _: Utr        => s"$HMRC_TERS_ORG~$sautr"
    case _: Urn        => s"$HMRC_TERSNT_ORG~$urn"
    case _: CgtRef     => s"$HMRC_CGT_PD~$cgtPdRef"
    case _: PptRef     => s"$HMRC_PPT_ORG~$etmpRegNum"
    case _: CbcId      => s"$HMRC_CBC_ORG~$cbcId"
    case _: CbcNonUkId => s"$HMRC_CBC_NON_UK_ORG~$cbcId"
    case _             => throw new IllegalArgumentException(s"Tax identifier not supported $taxIdentifier")
  }

  protected def identifierNickname(taxIdentifier: TaxIdentifier): String = taxIdentifier match {
    case _: Arn     => "ARN"
    case _: MtdItId => mtdItId
    case _: Vrn     => vrn
    case _: Nino    => nino
    case _: Utr     => sautr
    case _: Urn     => urn
    case _: CgtRef  => cgtPdRef
    case _: PptRef  => etmpRegNum
    case _: CbcId   => cbcId
    case _          => throw new IllegalArgumentException(s"Tax identifier not supported $taxIdentifier")
  }

}

object TaxIdentifierSupport {
  def from(value: String, `type`: String): TaxIdentifier = `type` match {
    case IdentifierKeys.mtdItId    => MtdItId(value)
    case IdentifierKeys.nino       => Nino(value)
    case IdentifierKeys.vrn        => Vrn(value)
    case "AgentReferenceNumber"    => Arn(value)
    case IdentifierKeys.sautr      => Utr(value)
    case IdentifierKeys.urn        => Urn(value)
    case IdentifierKeys.cgtPdRef   => CgtRef(value)
    case IdentifierKeys.etmpRegNum => PptRef(value)
    case _                         => throw new Exception("Invalid tax identifier type " + `type`)
  }
}
