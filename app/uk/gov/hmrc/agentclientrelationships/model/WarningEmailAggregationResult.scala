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

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads
import play.api.libs.json.__
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter

case class WarningEmailAggregationResult(
  arn: String,
  invitations: Seq[Invitation]
)

object WarningEmailAggregationResult {
  implicit def reads(implicit
    crypto: Encrypter with Decrypter
  ): Reads[WarningEmailAggregationResult] =
    ((__ \ "_id").read[String] and (__ \ "invitations").read[Seq[Invitation]](Reads.seq(Invitation.mongoFormat))).apply(
      WarningEmailAggregationResult.apply _
    )
}
