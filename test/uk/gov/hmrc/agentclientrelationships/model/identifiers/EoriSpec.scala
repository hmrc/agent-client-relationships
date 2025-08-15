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

package uk.gov.hmrc.agentclientrelationships.model.identifiers

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EoriSpec
extends AnyFlatSpec
with Matchers {

  it should "be true for a valid EORI" in {
    Eori.isValid("A0000000000000a") shouldBe true
  }

  it should "be false when it has more than 17 digits" in {
    Eori.isValid("00000000000000000000") shouldBe false
  }

  it should "be false when it is empty" in {
    Eori.isValid("") shouldBe false
  }

  it should "be false when it has non-alphanumeric characters" in {
    Eori.isValid("00000000000000!") shouldBe false
  }

}
