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

package uk.gov.hmrc.agentclientrelationships.model

import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, MtdItId, Vrn}
import uk.gov.hmrc.auth.core.Enrolment
import uk.gov.hmrc.domain.TaxIdentifier

sealed trait EnrolmentType {
  def enrolmentKey: String
  def identifierKey: String
}

case object EnrolmentAsAgent extends EnrolmentType {
  override val enrolmentKey = "HMRC-AS-AGENT"
  override val identifierKey = "AgentReferenceNumber"
}

case object EnrolmentMtdIt extends EnrolmentType {
  override val enrolmentKey = "HMRC-MTD-IT"
  override val identifierKey = "MTDITID"
}

case object EnrolmentMtdVat extends EnrolmentType {
  override val enrolmentKey = "HMRC-MTD-VAT"
  override val identifierKey = "MTDVATID"
}

object EnrolmentType {
  def enrolmentTypeFor(identifier: TaxIdentifier): EnrolmentType = identifier match {
    case _ @ MtdItId(_) => EnrolmentMtdIt
    case _ @ Vrn(_)     => EnrolmentMtdVat
    case _ @ Arn(_)     => EnrolmentAsAgent
    case _ => throw new IllegalArgumentException(s"Unhandled TaxIdentifier type ${identifier.getClass.getName}")
  }

  def findEnrolmentIdentifier(enrolmentType: EnrolmentType, enrolments: Set[Enrolment]): Option[TaxIdentifier] = {
    val maybeEnrolment: Option[Enrolment] = enrolments.find(_.key equals enrolmentType.enrolmentKey)

    maybeEnrolment
      .flatMap(_.identifiers.find(_.key equals enrolmentType.identifierKey))
      .map(enrolmentIdentifier => identifierForValue(enrolmentType, enrolmentIdentifier.value))
  }

  private def identifierForValue(enrolmentType: EnrolmentType, identifierValue: String): TaxIdentifier = {
    enrolmentType match {
      case EnrolmentAsAgent => Arn(identifierValue)
      case EnrolmentMtdIt => MtdItId(identifierValue)
      case EnrolmentMtdVat => Vrn(identifierValue)
    }
  }
}
