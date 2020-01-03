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

import uk.gov.hmrc.agentmtdidentifiers.model._
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.play.test.UnitSpec

class TaxIdentifierSupportSpec extends UnitSpec with TaxIdentifierSupport {

  "enrolmentKeyPrefixFor" should {
    "return HMRC-AS-AGENT~AgentReferenceNumber when tax identifier is of Arn type" in {
      enrolmentKeyPrefixFor(Arn("foo")) shouldBe "HMRC-AS-AGENT~AgentReferenceNumber"
    }

    "return HMRC-MTD-IT~MTDITID when tax identifier is of MtdItId type" in {
      enrolmentKeyPrefixFor(MtdItId("foo")) shouldBe "HMRC-MTD-IT~MTDITID"
    }

    "return HMRC-MTD-VAT~VRN when tax identifier is of Vrn type" in {
      enrolmentKeyPrefixFor(Vrn("foo")) shouldBe "HMRC-MTD-VAT~VRN"
    }

    "return HMRC-MTD-IT~NINO when tax identifier is of Nino type" in {
      enrolmentKeyPrefixFor(Nino("AB123456A")) shouldBe "HMRC-MTD-IT~NINO"
    }

    "return HMRC-CGT-PD~CGTPDRef when tax identifier is of CgtRef type" in {
      enrolmentKeyPrefixFor(CgtRef("XMCGTP123456789")) shouldBe "HMRC-CGT-PD~CGTPDRef"
    }

    "return IllegalArgumentException when tax identifier is not supported" in {
      an[IllegalArgumentException] should be thrownBy
        await(enrolmentKeyPrefixFor(Eori("foo")))
    }
  }

  "identifierNickname" should {
    "return ARN when tax identifier is of Arn type" in {
      identifierNickname(Arn("foo")) shouldBe "ARN"
    }

    "return MTDITID when tax identifier is of MtdItId type" in {
      identifierNickname(MtdItId("foo")) shouldBe "MTDITID"
    }

    "return VRN when tax identifier is of Vrn type" in {
      identifierNickname(Vrn("foo")) shouldBe "VRN"
    }

    "return NINO when tax identifier is of Nino type" in {
      identifierNickname(Nino("AB123456A")) shouldBe "NINO"
    }

    "return CGTPDRef when tax identifier is of CgtRef type" in {
      identifierNickname(CgtRef("XMCGTP123456789")) shouldBe "CGTPDRef"
    }

    "return IllegalArgumentException when tax identifier is not supported" in {
      an[IllegalArgumentException] should be thrownBy
        await(identifierNickname(Eori("foo")))
    }
  }

  "from" should {
    "return appropriate tax identifier when given value and type" in {
      TaxIdentifierSupport.from("foo", "MTDITID") shouldBe MtdItId("foo")

      TaxIdentifierSupport.from("AB123456A", "NINO") shouldBe Nino("AB123456A")

      TaxIdentifierSupport.from("foo", "VRN") shouldBe Vrn("foo")

      TaxIdentifierSupport.from("foo", "AgentReferenceNumber") shouldBe Arn("foo")
    }

    "throw an exception when tax identifier type is not supported" in {
      an[Exception] shouldBe thrownBy {
        TaxIdentifierSupport.from("foo", "UNSUPPORTED")
      }
    }
  }
}
