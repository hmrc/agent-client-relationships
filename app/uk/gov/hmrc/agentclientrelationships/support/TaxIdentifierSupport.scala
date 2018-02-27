/*
 * Copyright 2018 HM Revenue & Customs
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

import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.domain.{Nino, TaxIdentifier}

trait TaxIdentifierSupport {

  protected def enrolmentKeyPrefixFor(taxIdentifier: TaxIdentifier): String = taxIdentifier match {
    case _: Arn => "HMRC-AS-AGENT~AgentReferenceNumber"
    case _: MtdItId => "HMRC-MTD-IT~MTDITID"
    case _: Vrn => "HMRC-MTD-VAT~MTDVATID"
    case _: Nino => "HMRC-MTD-IT~NINO"
    case _ => throw new IllegalArgumentException(s"Tax identifier not supported $taxIdentifier")
  }

  protected def identifierName(taxIdentifier: TaxIdentifier): String = taxIdentifier match {
    case _: Arn => "ARN"
    case _: MtdItId => "MTDITID"
    case _: Vrn => "MTDVATID"
    case _: Nino => "NINO"
    case _ => throw new IllegalArgumentException(s"Tax identifier not supported $taxIdentifier")
  }

}