/*
 * Copyright 2026 HM Revenue & Customs
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

import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.JsonValidationError
import play.api.libs.json.OFormat
import uk.gov.hmrc.crypto.Hasher
import uk.gov.hmrc.crypto.Verifier
import uk.gov.hmrc.crypto.PlainText
import uk.gov.hmrc.crypto.Scrambled

object hmac:
  extension [A](modelFormat: OFormat[A])
    def withVerifiedHash(
      hashField: OFormat[String],
      accessor: A => String
    )(using hmac: Hasher & Verifier): OFormat[A] =
      val read =
        (modelFormat and hashField).tupled
          .collect(JsonValidationError("could not verify hash integrity")):
            case (model, hashValue) if hmac.verify(PlainText(accessor(model)), Scrambled(hashValue)) => model

      val write = (modelFormat and hashField).tupled.contramap[A]: model =>
        (model, hmac.hash(PlainText(accessor(model))).value)

      OFormat(read, write)
