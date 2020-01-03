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

package uk.gov.hmrc.agentclientrelationships.model

import uk.gov.hmrc.agentmtdidentifiers.model.{Arn, Eori, MtdItId, Utr, Vrn}
import uk.gov.hmrc.auth.core.{Enrolment, EnrolmentIdentifier}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.play.test.UnitSpec

class TypeOfEnrolmentSpec extends UnitSpec {

  "enrolmentTypeFor" should {
    "return EnrolmentMtdIt for an MtdItId identifier" in {
      TypeOfEnrolment(MtdItId("")) shouldBe EnrolmentMtdIt
    }

    "return EnrolmentMtdVat for an Vrn identifier" in {
      TypeOfEnrolment(Vrn("101747696")) shouldBe EnrolmentMtdVat
    }

    "return EnrolmentAsAgent for an Arn identifier" in {
      TypeOfEnrolment(Arn("TARN000001")) shouldBe EnrolmentAsAgent
    }

    "return EnrolmentNino for a Nino identifier" in {
      TypeOfEnrolment(Nino("AA000000A")) shouldBe EnrolmentNino
    }

    "throw an exception for an unhandled identifier" in {
      intercept[IllegalArgumentException] {
        TypeOfEnrolment(Eori("AA000000A"))
      }
    }
  }

  "findEnrolmentIdentifier" should {
    val allEnrolmentTypes = Seq(EnrolmentMtdIt, EnrolmentMtdVat, EnrolmentAsAgent)

    "return Arn for EnrolmentAsAgent if the HMRC-AS-AGENT enrolment exists with an AgentReferenceNumber identifier" in {
      val enrolments =
        Set(Enrolment("HMRC-AS-AGENT", Seq(EnrolmentIdentifier("AgentReferenceNumber", "arn123")), "activated"))

      EnrolmentAsAgent.extractIdentifierFrom(enrolments) shouldBe Some(Arn("arn123"))
    }

    "return Vrn for EnrolmentMtdVat if the HMRC-MTD-VAT enrolment exists with an VRN identifier" in {
      val enrolments = Set(Enrolment("HMRC-MTD-VAT", Seq(EnrolmentIdentifier("VRN", "101747696")), "activated"))

      EnrolmentMtdVat.extractIdentifierFrom(enrolments) shouldBe Some(Vrn("101747696"))
    }

    "return MtdItId for EnrolmentMtdIt if the HMRC-MTD-IT enrolment exists with an MTDITID identifier" in {
      val enrolments = Set(Enrolment("HMRC-MTD-IT", Seq(EnrolmentIdentifier("MTDITID", "123456789")), "activated"))

      EnrolmentMtdIt.extractIdentifierFrom(enrolments) shouldBe Some(MtdItId("123456789"))
    }

    "return None if the required enrolment does not exist" in {
      val enrolments = Set(Enrolment("HMRC-FOO-BAR", Seq(EnrolmentIdentifier("Foo", "Bar")), "activated"))

      allEnrolmentTypes.foreach(_.extractIdentifierFrom(enrolments) shouldBe None)
    }

    "return None if the required enrolment exists but the required identifier does not exist" in {
      val enrolments = Set(
        Enrolment("HMRC-AS-AGENT", Seq(EnrolmentIdentifier("NotAgentReferenceNumber", "arn123")), "activated"),
        Enrolment("HMRC-MTD-IT", Seq(EnrolmentIdentifier("NotMTDITID", "123")), "activated"),
        Enrolment("HMRC-MTD-VAT", Seq(EnrolmentIdentifier("NotVRN", "123")), "activated")
      )

      allEnrolmentTypes.foreach(_.extractIdentifierFrom(enrolments) shouldBe None)
    }
  }
}
