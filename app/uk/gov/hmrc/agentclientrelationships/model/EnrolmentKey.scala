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

import play.api.libs.json._
import uk.gov.hmrc.agentmtdidentifiers.model.{ClientIdType, ClientIdentifier, Identifier, Service}
import uk.gov.hmrc.domain.TaxIdentifier

// TODO DG we should really pick a version of these 'enrolment key' data types that we like the design of,
// and move it to agent-mtd-identifiers. BUT don't just move this. It needs to be well-designed and reviewed.
case class EnrolmentKey(service: String, identifiers: Seq[Identifier]) {
  lazy val tag =
    s"$service~${identifiers.sorted.map(identifier => s"${identifier.key}~${identifier.value}").mkString("~")}"
  override def toString: String = tag
  def singleIdentifier: Identifier = // Note: unsafe (i.e. can throw exceptions)
    if (identifiers.length == 1) identifiers.head else throw new RuntimeException("No single identifier")
  def singleTaxIdentifier: TaxIdentifier = { // Note: unsafe (i.e. can throw exceptions)
    val identifier = singleIdentifier
    ClientIdType.supportedTypes.find(_.enrolmentId == identifier.key).get.createUnderlying(identifier.value)
  }
}

object EnrolmentKey {

  def apply(s: String): EnrolmentKey =
    parse(s).getOrElse(throw new IllegalArgumentException("Invalid enrolment key: " + s))

  def apply(service: Service, taxIdentifiers: TaxIdentifier*): EnrolmentKey =
    EnrolmentKey(
      service.id,
      taxIdentifiers.map(taxIdentifier => Identifier(ClientIdentifier(taxIdentifier).enrolmentId, taxIdentifier.value))
    )

  def from(service: String, identifiers: (String, String)*): EnrolmentKey =
    EnrolmentKey(service, identifiers.map { case (k, v) => Identifier(k, v) })

  def parse(s: String): Option[EnrolmentKey] = {
    val parts = s.split("~")
    if (parts.nonEmpty && parts.size >= 3 && parts.size % 2 == 1) {
      val service = parts.head
      val identifiers = parts.tail.sliding(2, 2).map(a => Identifier(a(0), a(1))).toSeq
      Some(EnrolmentKey(service, identifiers))
    } else None
  }

  implicit val writes: Writes[EnrolmentKey] = new Writes[EnrolmentKey] {
    override def writes(ek: EnrolmentKey): JsValue = JsString(ek.toString)
  }

  implicit val reads: Reads[EnrolmentKey] = new Reads[EnrolmentKey] {
    override def reads(json: JsValue): JsResult[EnrolmentKey] = json match {
      case JsString(value) => parse(value).fold[JsResult[EnrolmentKey]](JsError("Invalid enrolment key"))(JsSuccess(_))
      case _               => JsError("STRING_VALUE_EXPECTED")
    }
  }
}
