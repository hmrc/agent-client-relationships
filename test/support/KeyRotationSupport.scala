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

package support

import org.scalatest.BeforeAndAfterEach
import org.scalatest.Suite
import uk.gov.hmrc.crypto.Crypted
import uk.gov.hmrc.crypto.Decrypter
import uk.gov.hmrc.crypto.Encrypter
import uk.gov.hmrc.crypto.PlainBytes
import uk.gov.hmrc.crypto.PlainContent
import uk.gov.hmrc.crypto.PlainText
import uk.gov.hmrc.crypto.SymmetricCryptoFactory

/** Provides the ability to change the current encryption key for a given repository.
  *
  * Creating multiple repositories in order to change the key can cause race conditions and other issues around the Mongo collection initialisation. It's much
  * more lightweight to have a single repository and change the key on demand. This trait provides a simple way to achieve that in tests. You can also change
  * the key multiple times without having to instantiate a new repository each time.
  */
trait KeyRotationSupport
extends BeforeAndAfterEach:
  this: Suite =>

  val DefaultSecretKey = "HV6NcLoiRka1OjZmYRtleBa9gxxZeyadSjoHDN1fi+4="

  object KeyRotationCrypto
  extends Encrypter
  with Decrypter:

    @volatile private var ref: Encrypter & Decrypter = SymmetricCryptoFactory.aesGcmCrypto(DefaultSecretKey)

    def rotateSecretKey(
      to: String,
      keepPreviousKey: Boolean = false
    ): Unit =
      val aes = SymmetricCryptoFactory.aesGcmCrypto(to)
      val prev = ref
      ref = if !keepPreviousKey then aes else SymmetricCryptoFactory.composeCrypto(aes, Seq(prev))

    override def encrypt(plain: PlainContent): Crypted = ref.encrypt(plain)
    override def decrypt(reversiblyEncrypted: Crypted): PlainText = ref.decrypt(reversiblyEncrypted)
    override def decryptAsBytes(reversiblyEncrypted: Crypted): PlainBytes = ref.decryptAsBytes(reversiblyEncrypted)

  override def beforeEach(): Unit =
    super.beforeEach()
    KeyRotationCrypto.rotateSecretKey(to = DefaultSecretKey)
