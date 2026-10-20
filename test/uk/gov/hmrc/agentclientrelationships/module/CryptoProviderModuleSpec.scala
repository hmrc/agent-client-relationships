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

package uk.gov.hmrc.agentclientrelationships.module

import play.api.Configuration
import uk.gov.hmrc.agentclientrelationships.support.UnitSpec
import uk.gov.hmrc.crypto.Crypted
import uk.gov.hmrc.crypto.PlainText

class CryptoProviderModuleSpec
extends UnitSpec:

  private val module = new CryptoProviderModule

  private val encryptionEnabledConfig = Configuration.from(
    Map(
      "fieldLevelEncryption.enable" -> true,
      "fieldLevelEncryption.key" -> "edkOOwt7uvzw1TXnFIN6aRVHkfWcgiOrbBvkEQvO65g=",
      "fieldLevelEncryption.previousKeys" -> Seq.empty[String]
    )
  )

  private val encryptionDisabledConfig = Configuration.from(
    Map(
      "fieldLevelEncryption.enable" -> false
    )
  )

  private val sampleText = PlainText("client secret")
  "CryptoProviderModule.aesEcbCryptoInstance" should:
    "encrypt deterministically and decrypt back to the original plaintext when encryption is enabled" in:
      val ecbCrypto = module.aesEcbCryptoInstance(encryptionEnabledConfig)

      val encryptedOnce = ecbCrypto.encrypt(sampleText)
      val encryptedTwice = ecbCrypto.encrypt(sampleText)

      encryptedOnce shouldBe encryptedTwice
      ecbCrypto.decrypt(encryptedOnce) shouldBe sampleText

    "pass plaintext through unchanged when encryption is disabled" in:
      val ecbCrypto = module.aesEcbCryptoInstance(encryptionDisabledConfig)

      val encrypted = ecbCrypto.encrypt(sampleText)

      encrypted shouldBe Crypted(sampleText.value)
      ecbCrypto.decrypt(encrypted) shouldBe sampleText

  "CryptoProviderModule.aesGcmCryptoInstanceWithFallback" should:
    "encrypt non-deterministically and decrypt each value back to the original plaintext when encryption is enabled" in:
      val gcmCrypto = module.aesGcmCryptoInstanceWithFallback(encryptionEnabledConfig)

      val encryptedOnce = gcmCrypto.encrypt(sampleText)
      val encryptedTwice = gcmCrypto.encrypt(sampleText)

      encryptedOnce should not be encryptedTwice
      gcmCrypto.decrypt(encryptedOnce) shouldBe sampleText
      gcmCrypto.decrypt(encryptedTwice) shouldBe sampleText

    "decrypt values produced by the ECB crypto from the same configuration" in:
      val ecbCrypto = module.aesEcbCryptoInstance(encryptionEnabledConfig)
      val gcmCrypto = module.aesGcmCryptoInstanceWithFallback(encryptionEnabledConfig)

      val ecbEncrypted = ecbCrypto.encrypt(sampleText)

      gcmCrypto.decrypt(ecbEncrypted) shouldBe sampleText

    "behave like a passthrough crypto when encryption is disabled" in:
      val gcmCrypto = module.aesGcmCryptoInstanceWithFallback(encryptionDisabledConfig)

      val encrypted = gcmCrypto.encrypt(sampleText)

      encrypted shouldBe Crypted(sampleText.value)
      gcmCrypto.decrypt(encrypted) shouldBe sampleText

  "NoCrypto" should:
    "round-trip raw bytes without altering their content" in:
      val encrypted = NoCrypto.encrypt(PlainText("client secret"))
      NoCrypto.decrypt(encrypted) shouldBe PlainText("client secret")
