/*
 * Copyright 2024 HM Revenue & Customs
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

import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.*
import uk.gov.hmrc.agentclientrelationships.support.hmac.*
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.Hasher
import uk.gov.hmrc.crypto.Verifier
import uk.gov.hmrc.crypto.json.JsonEncryption.stringDecrypter
import uk.gov.hmrc.crypto.json.JsonEncryption.stringEncrypter
import uk.gov.hmrc.crypto.json.JsonEncryption.stringEncrypterDecrypter
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import java.time.Instant

case class PartialAuthRelationship(
  created: Instant,
  arn: String,
  service: String,
  nino: String,
  active: Boolean,
  lastUpdated: Instant
)

object PartialAuthRelationship:

  given OFormat[PartialAuthRelationship] = Json.format[PartialAuthRelationship]

  def mongoFormat(using
    crypto: Encrypter & Decrypter,
    hmac: Hasher & Verifier
  ): Format[PartialAuthRelationship] =
    given mongoInstantFormat: Format[Instant] = MongoJavatimeFormats.instantFormat

    val plainTextOrEncrypted = Format(stringDecrypter orElse Format.of[String], stringEncrypter)

    (__ \ "created").format[Instant].and((__ \ "arn").format[String](using plainTextOrEncrypted)).and(
      (__ \ "service").format[String]
    ).and((__ \ "nino").format[String](using stringEncrypterDecrypter)).and((__ \ "active").format[Boolean]).and((__ \ "lastUpdated").format[Instant])
      .apply(PartialAuthRelationship.apply, Tuple.fromProductTyped)
      .withVerifiedHash((__ \ "ninoHash").format[String], _.nino)
      .withVerifiedHash((__ \ "ninoArnHash").format[String], r => s"${r.nino}:${r.arn}")
