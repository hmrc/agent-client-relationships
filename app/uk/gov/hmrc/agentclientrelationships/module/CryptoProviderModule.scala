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

package uk.gov.hmrc.agentclientrelationships.module

import play.api.Configuration
import play.api.Environment
import play.api.inject.Binding
import play.api.inject.Module
import uk.gov.hmrc.crypto.Crypted
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.Hasher
import uk.gov.hmrc.crypto.OnewayCryptoFactory
import uk.gov.hmrc.crypto.PlainBytes
import uk.gov.hmrc.crypto.PlainContent
import uk.gov.hmrc.crypto.PlainText
import uk.gov.hmrc.crypto.Scrambled
import uk.gov.hmrc.crypto.SymmetricCryptoFactory
import uk.gov.hmrc.crypto.Verifier

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.util.hashing.MurmurHash3

class CryptoProviderModule
extends Module {

  def aesEcbCryptoInstance(configuration: Configuration): Encrypter
    & Decrypter =
    if (configuration.underlying.getBoolean("fieldLevelEncryption.enable"))
      SymmetricCryptoFactory.aesCryptoFromConfig("fieldLevelEncryption", configuration.underlying)
    else
      NoCrypto

  def aesGcmCryptoInstanceWithFallback(configuration: Configuration): Encrypter
    & Decrypter =
    if (configuration.underlying.getBoolean("fieldLevelEncryption.enable"))
      SymmetricCryptoFactory.composeCrypto(
        SymmetricCryptoFactory.aesGcmCryptoFromConfig("fieldLevelEncryption", configuration.underlying),
        Seq(aesEcbCryptoInstance(configuration))
      )
    else
      NoCrypto

  def sha512HmacInstance(configuration: Configuration): Hasher & Verifier =
    if (configuration.underlying.getBoolean("fieldLevelEncryption.enable"))
      OnewayCryptoFactory.shaCryptoFromConfig("fieldLevelEncryption", configuration.underlying)
    else
      NonCryptographicHash
  override def bindings(
    environment: Environment,
    configuration: Configuration
  ): Seq[Binding[?]] = Seq(
    bind[Encrypter & Decrypter].qualifiedWith("aes").toInstance(aesEcbCryptoInstance(configuration)),
    bind[Encrypter & Decrypter].qualifiedWith("aesGcm").toInstance(aesGcmCryptoInstanceWithFallback(configuration)),
    bind[Hasher & Verifier].toInstance(sha512HmacInstance(configuration))
  )

}

/** Encrypter/decrypter that does nothing (i.e. leaves content in plaintext). Only to be used for debugging.
  */
trait NoCrypto
extends Encrypter
with Decrypter {

  def encrypt(plain: PlainContent): Crypted =
    plain match {
      case PlainText(text) => Crypted(text)
      case PlainBytes(bytes) => Crypted(new String(Base64.getEncoder.encode(bytes), StandardCharsets.UTF_8))
    }
  def decrypt(notEncrypted: Crypted): PlainText = PlainText(notEncrypted.value)
  def decryptAsBytes(nullEncrypted: Crypted): PlainBytes = PlainBytes(Base64.getDecoder.decode(nullEncrypted.value))

}

object NoCrypto
extends NoCrypto

object NonCryptographicHash
extends Hasher,
  Verifier {

  override def hash(plain: PlainText): Scrambled = Scrambled(MurmurHash3.stringHash(plain.value).toString)

  override def verify(
    plain: PlainText,
    scrambled: Scrambled
  ): Boolean = hash(plain) == scrambled

}
