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

import play.api.libs.functional.syntax.*
import play.api.libs.json.Format
import play.api.libs.json.JsSuccess
import play.api.libs.json.Reads
import play.api.libs.json.__
import uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.Hasher
import uk.gov.hmrc.crypto.Verifier
import uk.gov.hmrc.crypto.json.JsonEncryption.stringDecrypter
import uk.gov.hmrc.crypto.json.JsonEncryption.stringEncrypter

case class WarningEmailAggregationResult(
  arn: String,
  invitations: Seq[Invitation]
)

object WarningEmailAggregationResult:
  given reads(using
    crypto: Encrypter & Decrypter,
    hmac: Hasher & Verifier
  ): Reads[WarningEmailAggregationResult] =

    val arnReads = Reads[String] { json =>
      json.validate[String].flatMap { arn =>
        if Arn.isValid(arn) then JsSuccess(arn)
        else stringDecrypter.reads(json)
      }
    }

    val plainTextOrEncrypted = Format(arnReads, stringEncrypter)

    ((__ \ "_id").read[String](using plainTextOrEncrypted) and (__ \ "invitations").read[Seq[Invitation]](using Reads.seq(using Invitation.mongoFormat))).apply(
      WarningEmailAggregationResult.apply
    )
