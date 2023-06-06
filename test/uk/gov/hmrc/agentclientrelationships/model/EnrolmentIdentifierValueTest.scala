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

import uk.gov.hmrc.agentclientrelationships.support.BaseSpec
import uk.gov.hmrc.agentmtdidentifiers.model.{CbcId, CbcNonUkId, CgtRef}

class EnrolmentIdentifierValueTest extends BaseSpec {

  "mtd" should {
    "match asCgtRef id " in {
      EnrolmentIdentifierValue("whatever").asCgtRef shouldBe CgtRef("whatever")
    }
    "match cbc id " in {
      EnrolmentIdentifierValue("XACBC1234567890").asCbcId shouldBe CbcId("XACBC1234567890")
    }
    "match non ukcbc id " in {
      EnrolmentIdentifierValue("XACBC1234567890").asCbcNonUkId shouldBe CbcNonUkId("XACBC1234567890")
    }
  }
}
