/*
 * Copyright 2025 HM Revenue & Customs
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

import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.agentmtdidentifiers.model.Service.{MtdIt, Vat}
import uk.gov.hmrc.agentmtdidentifiers.model.{MtdItId, Vrn}
import uk.gov.hmrc.auth.core.{Enrolment, EnrolmentIdentifier, Enrolments}

class EnrolmentsWithNinoSpec
extends UnitSpec {

  val nino = "AA111111A"
  val mtdItId = "XAIT1234567"
  val vrn = "123456789"
  val ptEnrolment: Enrolment = Enrolment("HMRC-PT", Seq(EnrolmentIdentifier("NINO", nino)), "active")
  val itsaEnrolment: Enrolment = Enrolment("HMRC-MTD-IT", Seq(EnrolmentIdentifier("MTDITID", mtdItId)), "active")
  val vatEnrolment: Enrolment = Enrolment("HMRC-MTD-VAT", Seq(EnrolmentIdentifier("VRN", vrn)), "active")

  ".getNino" should {

    "return the NINO from the HMRC-PT enrolment if it exists" in {
      val model = new EnrolmentsWithNino(Enrolments(Set(ptEnrolment)), Some(nino))
      model.getNino shouldBe Some(nino)
    }

    "return the NINO from the model (auth retrieval) if the HMRC-PT enrolment does not exist" in {
      val model = new EnrolmentsWithNino(Enrolments(Set()), Some(nino))
      model.getNino shouldBe Some(nino)
    }

    "return None if both NINO sources were empty" in {
      val model = new EnrolmentsWithNino(Enrolments(Set()), None)
      model.getNino shouldBe None
    }
  }

  ".getIdentifierMap" should {

    "return a Map of services and tax identifiers for the provided supported services" in {
      val model = new EnrolmentsWithNino(Enrolments(Set(ptEnrolment, itsaEnrolment, vatEnrolment)), Some(nino))
      val supportedServices = Seq(MtdIt, Vat)
      val expectedMap = Map(MtdIt -> MtdItId(mtdItId), Vat -> Vrn(vrn))

      model.getIdentifierMap(supportedServices) shouldBe expectedMap
    }

    "return an empty Map when no enrolments match the supported services" in {
      val model = new EnrolmentsWithNino(Enrolments(Set(ptEnrolment)), Some(nino))
      val supportedServices = Seq(MtdIt, Vat)
      val expectedMap = Map()

      model.getIdentifierMap(supportedServices) shouldBe expectedMap
    }
  }
}
