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

package uk.gov.hmrc.agentclientrelationships.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import uk.gov.hmrc.agentmtdidentifiers.model.Identifier
class EnrolmentKeySpec extends AnyFlatSpec with Matchers {

  "EnrolmentKey" should "toString with mixed case" in {
    val enrolmentKeyStr = EnrolmentKey(
      "HMRC-CBC-ORG",
      Seq(Identifier("UTR", "1234567890"), Identifier("cbcId", "XCBCX1234567890"))).toString

    enrolmentKeyStr shouldBe "HMRC-CBC-ORG~UTR~1234567890~cbcId~XCBCX1234567890"
  }

  it should "return correct 'default' service identifier" in {
    val cbcKey =
      EnrolmentKey("HMRC-CBC-ORG", Seq(Identifier("UTR", "1234567890"), Identifier("cbcId", "XCBCX1234567890")))
    val cbcNonUKKey = EnrolmentKey("HMRC-CBC-NONUK-ORG", Seq(Identifier("cbcId", "XCBCX1234567891")))
    // vrn is case sensitive
    val mtdVatKey = EnrolmentKey("HMRC-MTD-VAT", Seq(Identifier("VRN", "123456789")))

    cbcKey.oneIdentifier() shouldBe Identifier("cbcId", "XCBCX1234567890")
    cbcNonUKKey.oneIdentifier() shouldBe Identifier("cbcId", "XCBCX1234567891")
    mtdVatKey.oneIdentifier() shouldBe Identifier("VRN", "123456789")
  }

  it should "return correct identifier with supplied key" in {
    val cbcKey =
      EnrolmentKey("HMRC-CBC-ORG", Seq(Identifier("UTR", "1234567890"), Identifier("cbcId", "XCBCX1234567890")))

    cbcKey.oneIdentifier(Some("UTR")) shouldBe Identifier("UTR", "1234567890")
  }

  it should "return first identifier if not supported service" in {
    val unknownKey = EnrolmentKey("HMRC-UNKNOWN", Seq(Identifier("ReferenceNumber", "123456789")))
    val eKey = EnrolmentKey("HMRC-AS-AGENT", Seq(Identifier("AgentReferenceNumber", "ARN123456789")))

    eKey.oneIdentifier() shouldBe Identifier("AgentReferenceNumber", "ARN123456789")
    unknownKey.oneIdentifier() shouldBe Identifier("ReferenceNumber", "123456789")
  }

  it should "throw an exception if unknown key" in {
    an[Exception] shouldBe thrownBy(
      EnrolmentKey("HMRC-UNKNOWN", Seq(Identifier("ReferenceNumber", "123456789"))).oneIdentifier(Some("bad key"))
    )
  }

}
