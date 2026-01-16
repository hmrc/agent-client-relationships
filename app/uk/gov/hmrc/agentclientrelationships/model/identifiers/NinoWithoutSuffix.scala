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

package uk.gov.hmrc.agentclientrelationships.model.identifiers

import play.api.libs.json.Reads
import play.api.libs.json.Writes
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.domain.SimpleName
import uk.gov.hmrc.domain.SimpleObjectReads
import uk.gov.hmrc.domain.SimpleObjectWrites
import uk.gov.hmrc.domain.TaxIdentifier

case class NinoWithoutSuffix(nino: String)
extends TaxIdentifier
with SimpleName {

  require(NinoWithoutSuffix.isValid(nino), s"$nino is not a valid nino.")

  private val suffixlessNinoLength = 8

  private val ninoWithoutSpace = nino.replace(" ", "")
  override def value: String = ninoWithoutSpace.take(suffixlessNinoLength)

  // When using this make sure whatever uses the nino does not actually care about the suffix
  def anySuffixValue: String =
    ninoWithoutSpace.length match {
      case len if len > suffixlessNinoLength => ninoWithoutSpace
      case _ => value + "A"
    }

  override def toString: String = nino

  override val name: String = "nino-without-suffix"

  override def hashCode(): Int = value.hashCode

  override def equals(obj: Any): Boolean =
    obj match {
      case that: NinoWithoutSuffix => value == that.value
      case _ => false
    }

  def variations: Seq[String] = value +: Nino.validSuffixes.map(value + _)

}

object NinoWithoutSuffix
extends (String => NinoWithoutSuffix) {

  implicit val ninoWrite: Writes[NinoWithoutSuffix] = new SimpleObjectWrites[NinoWithoutSuffix](_.value)
  implicit val ninoRead: Reads[NinoWithoutSuffix] = new SimpleObjectReads[NinoWithoutSuffix]("nino-without-suffix", NinoWithoutSuffix.apply)

  def isValid(nino: String): Boolean = nino != null && (Nino.isValid(nino + "A") || Nino.isValid(nino))

}
